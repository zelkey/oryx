/*
 * Copyright (c) 2014, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.oryx.ml.mllib.als;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.typesafe.config.Config;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.DoubleFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.mllib.recommendation.ALS;
import org.apache.spark.mllib.recommendation.MatrixFactorizationModel;
import org.apache.spark.mllib.recommendation.Rating;
import org.apache.spark.rdd.RDD;
import org.apache.spark.util.StatCounter;
import org.dmg.pmml.PMML;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.reflect.ClassTag$;

import com.cloudera.oryx.common.collection.Pair;
import com.cloudera.oryx.lambda.QueueProducer;
import com.cloudera.oryx.lambda.fn.Functions;
import com.cloudera.oryx.ml.MLUpdate;
import com.cloudera.oryx.ml.param.HyperParamRange;
import com.cloudera.oryx.ml.param.HyperParamRanges;
import com.cloudera.oryx.common.pmml.PMMLUtils;

/**
 * A specialization of {@link MLUpdate} that creates a matrix factorization model of its
 * input, using the Alternating Least Squares algorithm.
 */
public final class ALSUpdate extends MLUpdate<String> {

  private static final Logger log = LoggerFactory.getLogger(ALSUpdate.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern COMMA = Pattern.compile(",");

  private final int iterations;
  private final boolean implicit;
  private final List<HyperParamRange> hyperParamRanges;
  private final boolean noKnownItems;

  public ALSUpdate(Config config) {
    super(config);
    iterations = config.getInt("als.iterations");
    implicit = config.getBoolean("als.implicit");
    Preconditions.checkArgument(iterations > 0);
    hyperParamRanges = Arrays.asList(
        HyperParamRanges.fromConfig(config, "als.hyperparams.features"),
        HyperParamRanges.fromConfig(config, "als.hyperparams.lambda"),
        HyperParamRanges.fromConfig(config, "als.hyperparams.alpha"));
    noKnownItems = config.getBoolean("als.no-known-items");
  }

  @Override
  public List<HyperParamRange> getHyperParameterRanges() {
    return hyperParamRanges;
  }

  @Override
  public PMML buildModel(JavaSparkContext sparkContext,
                         JavaRDD<String> trainData,
                         List<Number> hyperParams,
                         Path candidatePath) {
    log.info("Building model with params {}", hyperParams);

    int features = hyperParams.get(0).intValue();
    double lambda = hyperParams.get(1).doubleValue();
    double alpha = hyperParams.get(2).doubleValue();
    Preconditions.checkArgument(features > 0);
    Preconditions.checkArgument(lambda >= 0.0);
    Preconditions.checkArgument(alpha > 0.0);

    JavaRDD<Rating> trainRatingData = parsedToRatingRDD(trainData.map(PARSE_FN));
    trainRatingData = aggregateScores(trainRatingData);
    MatrixFactorizationModel model;
    if (implicit) {
      model = ALS.trainImplicit(trainRatingData.rdd(), features, iterations, lambda, alpha);
    } else {
      model = ALS.train(trainRatingData.rdd(), features, iterations, lambda);
    }
    return mfModelToPMML(model, features, lambda, alpha, implicit, candidatePath);
  }

  @Override
  public double evaluate(JavaSparkContext sparkContext,
                         PMML model,
                         Path modelParentPath,
                         JavaRDD<String> testData) {
    log.info("Evaluating model");
    JavaRDD<Rating> testRatingData = parsedToRatingRDD(testData.map(PARSE_FN));
    testRatingData = aggregateScores(testRatingData);
    MatrixFactorizationModel mfModel = pmmlToMFModel(sparkContext, model, modelParentPath);
    double eval;
    if (implicit) {
      double auc = AUC.areaUnderCurve(sparkContext, mfModel, testRatingData);
      log.info("AUC: {}", auc);
      eval = auc;
    } else {
      double rmse = RMSE.rmse(mfModel, testRatingData);
      log.info("RMSE: {}", rmse);
      eval = 1.0 / rmse;
    }
    return eval;
  }

  @Override
  public void publishAdditionalModelData(JavaSparkContext sparkContext,
                                         PMML pmml,
                                         JavaRDD<String> newData,
                                         JavaRDD<String> pastData,
                                         Path modelParentPath,
                                         QueueProducer<String, String> modelUpdateQueue) {

    JavaRDD<String> allData = pastData == null ? newData : newData.union(pastData);

    log.info("Sending user / X data as model updates");
    String xPathString = PMMLUtils.getExtensionValue(pmml, "X");
    JavaPairRDD<Integer,double[]> userRDD =
        fromRDD(readFeaturesRDD(sparkContext, new Path(modelParentPath, xPathString)));

    if (noKnownItems) {
      userRDD.foreach(new EnqueueFeatureVecsFn("X", modelUpdateQueue));
    } else {
      log.info("Sending known item data with model updates");
      JavaPairRDD<Integer,Collection<Integer>> knownItems = knownsRDD(allData, true);
      userRDD.join(knownItems).foreach(
          new EnqueueFeatureVecsAndKnownItemsFn("X", modelUpdateQueue));
    }

    log.info("Sending item / Y data as model updates");
    String yPathString = PMMLUtils.getExtensionValue(pmml, "Y");
    JavaPairRDD<Integer,double[]> productRDD =
        fromRDD(readFeaturesRDD(sparkContext, new Path(modelParentPath, yPathString)));

    // For now, there is no use in sending known users for each item
    //if (noKnownItems) {
    productRDD.foreach(new EnqueueFeatureVecsFn("Y", modelUpdateQueue));
    //} else {
    //  log.info("Sending known user data with model updates");
    //  JavaPairRDD<Integer,Collection<Integer>> knownUsers = knownsRDD(allData, false);
    //  productRDD.join(knownUsers).foreach(
    //      new EnqueueFeatureVecsAndKnownItemsFn("Y", modelUpdateQueue));
    //}
  }

  /**
   * Implementation which splits based solely on time. It will return approximately
   * the earliest {@link #getTestFraction()} of input, ordered by timestamp, as new training
   * data and the rest as test data.
   */
  @Override
  protected Pair<JavaRDD<String>,JavaRDD<String>> splitNewDataToTrainTest(JavaRDD<String> newData) {
    // Rough approximation; assumes timestamps are fairly evenly distributed
    StatCounter maxMin = newData.mapToDouble(new DoubleFunction<String>() {
      @Override
      public double call(String line) throws Exception {
        return TO_TIMESTAMP_FN.call(line).doubleValue();
      }
    }).stats();

    long minTime = (long) maxMin.min();
    long maxTime = (long) maxMin.max();
    log.info("New data timestamp range: {} - {}", minTime, maxTime);
    final long approxTestTrainBoundary = minTime + (long) (getTestFraction() * (maxTime - minTime));
    log.info("Splitting at timestamp {}", approxTestTrainBoundary);

    JavaRDD<String> newTrainData = newData.filter(new Function<String,Boolean>() {
      @Override
      public Boolean call(String line) throws Exception {
        return TO_TIMESTAMP_FN.call(line) < approxTestTrainBoundary;
      }
    });
    JavaRDD<String> testData = newData.filter(new Function<String,Boolean>() {
      @Override
      public Boolean call(String line) throws Exception {
        return TO_TIMESTAMP_FN.call(line) >= approxTestTrainBoundary;
      }
    });

    return new Pair<>(newTrainData, testData);
  }

  /**
   * @param parsedRDD parsed input as {@code String[]}
   * @return {@link Rating}s ordered by timestamp
   */
  private static JavaRDD<Rating> parsedToRatingRDD(JavaRDD<String[]> parsedRDD) {
    return parsedRDD.mapToPair(new PairFunction<String[],Long,Rating>() {
      @Override
      public Tuple2<Long,Rating> call(String[] tokens) {
        return new Tuple2<>(
            Long.valueOf(tokens[3]),
            new Rating(Integer.parseInt(tokens[0]),
                       Integer.parseInt(tokens[1]),
                       // Empty value means 'delete'; propagate as NaN
                       tokens[2].isEmpty() ? Double.NaN : Double.parseDouble(tokens[2])));
      }
    }).sortByKey().values();
  }

  /**
   * Combines {@link Rating}s with the same user/item into one, with score as the sum of
   * all of the scores.
   */
  private JavaRDD<Rating> aggregateScores(JavaRDD<Rating> original) {
    JavaPairRDD<Tuple2<Integer,Integer>,Double> tuples =
        original.mapToPair(new RatingToTupleDouble());

    JavaPairRDD<Tuple2<Integer,Integer>,Double> aggregated;
    if (implicit) {
      // For implicit, values are scores to be summed. Relies on the values being encountered
      // order to handle deletes correctly. When encountering NaN (delete), the sum will
      // become NaN and be removed. Except that NaN + b = b according to this function to
      // account for brand new values after a delete.
      aggregated = tuples.reduceByKey(Functions.SUM_DOUBLE);
    } else {
      // For non-implicit, last wins.
      aggregated = tuples.foldByKey(Double.NaN, Functions.<Double>last());
    }

    return aggregated.filter(new Function<Tuple2<Tuple2<Integer, Integer>, Double>, Boolean>() {
      @Override
      public Boolean call(Tuple2<Tuple2<Integer,Integer>,Double> userItemScore) {
        return !Double.isNaN(userItemScore._2());
      }
    }).map(TUPLE_TO_RATING_FN);
  }

  /**
   * There is no actual serialization of a massive factored matrix model into PMML.
   * Instead, we create an ad-hoc serialization where the model just contains pointers
   * to files that contain the matrix data, as Extensions.
   */
  private static PMML mfModelToPMML(MatrixFactorizationModel model,
                                    int features,
                                    double lambda,
                                    double alpha,
                                    boolean implicit,
                                    Path candidatePath) {
    saveFeaturesRDD(model.userFeatures(), new Path(candidatePath, "X"));
    saveFeaturesRDD(model.productFeatures(), new Path(candidatePath, "Y"));

    PMML pmml = PMMLUtils.buildSkeletonPMML();
    PMMLUtils.addExtension(pmml, "X", "X/");
    PMMLUtils.addExtension(pmml, "Y", "Y/");
    PMMLUtils.addExtension(pmml, "features", Integer.toString(features));
    PMMLUtils.addExtension(pmml, "lambda", Double.toString(lambda));
    PMMLUtils.addExtension(pmml, "implicit", Boolean.toString(implicit));
    if (implicit) {
      PMMLUtils.addExtension(pmml, "alpha", Double.toString(alpha));
    }
    addIDsExtension(pmml, "XIDs", model.userFeatures());
    addIDsExtension(pmml, "YIDs", model.productFeatures());
    return pmml;
  }

  private static void addIDsExtension(PMML pmml,
                                      String key,
                                      RDD<Tuple2<Object,double[]>> features) {
    List<String> ids = fromRDD(features).keys().map(Functions.toStringValue()).collect();
    PMMLUtils.addExtensionContent(pmml, key, ids);
  }

  private static void saveFeaturesRDD(RDD<Tuple2<Object,double[]>> features, Path path) {
    log.info("Saving features RDD to {}", path);
    fromRDD(features).map(new Function<Tuple2<Object,double[]>, String>() {
      @Override
      public String call(Tuple2<Object, double[]> keyAndVector) throws IOException {
        Object key = keyAndVector._1();
        double[] vector = keyAndVector._2();
        return MAPPER.writeValueAsString(Arrays.asList(key, vector));
      }
    }).saveAsTextFile(path.toString(), GzipCodec.class);
  }

  private static MatrixFactorizationModel pmmlToMFModel(JavaSparkContext sparkContext,
                                                        PMML pmml,
                                                        Path modelParentPath) {
    String xPathString = PMMLUtils.getExtensionValue(pmml, "X");
    String yPathString = PMMLUtils.getExtensionValue(pmml, "Y");

    RDD<Tuple2<Integer,double[]>> userRDD =
        readFeaturesRDD(sparkContext, new Path(modelParentPath, xPathString));
    RDD<Tuple2<Integer,double[]>> productRDD =
        readFeaturesRDD(sparkContext, new Path(modelParentPath, yPathString));

    // Cast is needed for some reason with this Scala API returning array
    @SuppressWarnings("unchecked")
    Tuple2<?,double[]> first = (Tuple2<?,double[]>) ((Object[]) userRDD.take(1))[0];
    int rank = first._2().length;
    return new MatrixFactorizationModel(
        rank, massageToObjectKey(userRDD), massageToObjectKey(productRDD));
  }

  private static RDD<Tuple2<Integer,double[]>> readFeaturesRDD(JavaSparkContext sparkContext,
                                                              Path path) {
    log.info("Loading features RDD from {}", path);
    JavaRDD<String> featureLines = sparkContext.textFile(path.toString());
    return featureLines.map(new Function<String,Tuple2<Integer,double[]>>() {
      @Override
      public Tuple2<Integer,double[]> call(String line) throws IOException {
        List<?> update = MAPPER.readValue(line, List.class);
        Integer key = Integer.valueOf(update.get(0).toString());
        double[] vector = MAPPER.convertValue(update.get(1), double[].class);
        return new Tuple2<>(key, vector);
      }
    }).rdd();
  }

  private static <A,B> RDD<Tuple2<Object,B>> massageToObjectKey(RDD<Tuple2<A,B>> in) {
    // More horrible hacks to get around Scala-Java unfriendliness
    @SuppressWarnings("unchecked")
    RDD<Tuple2<Object,B>> result = (RDD<Tuple2<Object,B>>) (RDD<?>) in;
    return result;
  }

  private static JavaPairRDD<Integer,Collection<Integer>> knownsRDD(JavaRDD<String> csvRDD,
                                                                    final boolean knownItems) {
    return csvRDD.mapToPair(new PairFunction<String,Integer,Integer>() {
      @Override
      public Tuple2<Integer,Integer> call(String csv) {
        String[] tokens = COMMA.split(csv);
        Integer user = Integer.valueOf(tokens[0]);
        Integer item = Integer.valueOf(tokens[1]);
        return knownItems ? new Tuple2<>(user, item) : new Tuple2<>(item, user);
      }
    }).combineByKey(
        new Function<Integer,Collection<Integer>>() {
          @Override
          public Collection<Integer> call(Integer i) {
            Collection<Integer> set = new HashSet<>();
            set.add(i);
            return set;
          }
        },
        new Function2<Collection<Integer>,Integer,Collection<Integer>>() {
          @Override
          public Collection<Integer> call(Collection<Integer> set, Integer i) {
            set.add(i);
            return set;
          }
        },
        new Function2<Collection<Integer>,Collection<Integer>,Collection<Integer>>() {
          @Override
          public Collection<Integer> call(Collection<Integer> set1, Collection<Integer> set2) {
            set1.addAll(set2);
            return set1;
          }
        }
    );
  }

  private static <K,V> JavaPairRDD<K,V> fromRDD(RDD<Tuple2<K,V>> rdd) {
    return JavaPairRDD.fromRDD(rdd,
                               ClassTag$.MODULE$.<K>apply(Object.class),
                               ClassTag$.MODULE$.<V>apply(Object.class));
  }

  private static final Function<Tuple2<Tuple2<Integer,Integer>,Double>,Rating> TUPLE_TO_RATING_FN =
      new Function<Tuple2<Tuple2<Integer,Integer>,Double>,Rating>() {
        @Override
        public Rating call(Tuple2<Tuple2<Integer,Integer>,Double> userProductScore) {
          Tuple2<Integer,Integer> userProduct = userProductScore._1();
          return new Rating(userProduct._1(), userProduct._2(), userProductScore._2());
        }
      };

  /**
   * Parses with PARSE_FN and returns fourth field as timestamp
   */
  private static final Function<String,Long> TO_TIMESTAMP_FN =
      new Function<String,Long>() {
        @Override
        public Long call(String line) throws Exception {
          return Long.valueOf(PARSE_FN.call(line)[3]);
        }
      };

  /**
   * Parses 4-element CSV or JSON array to 4-element String[]
   */
  private static final Function<String,String[]> PARSE_FN =
      new Function<String,String[]>() {
        @Override
        public String[] call(String line) throws IOException {
          // Hacky, but effective way of differentiating simple CSV from JSON array
          String[] result;
          if (line.endsWith("]")) {
            // JSON
            result = MAPPER.readValue(line, String[].class);
          } else {
            // CSV
            result = COMMA.split(line);
          }
          Preconditions.checkArgument(result.length == 4);
          return result;
        }
      };

}
