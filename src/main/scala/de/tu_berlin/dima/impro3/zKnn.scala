package de.tu_berlin.dima.impro3

/**
 * Created by jguenthe on 20.07.2015.
 */

import org.apache.flink.api.common.functions.RichMapFunction
import org.apache.flink.api.common.operators.Order
import org.apache.flink.api.scala.DataSetUtils._
import org.apache.flink.api.scala._
import org.apache.flink.configuration.Configuration
import org.apache.flink.ml.common.LabeledVector
import org.apache.flink.ml.math.DenseVector
import org.apache.flink.ml.metrics.distances.DistanceMetric

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random


object zKnn extends Serializable {

  /**
   * Computes the nearest neighbors in the data-set for the data-point against which KNN
   * has to be applied
   *
   * @param dataSet : RDD of Vectors of Int
   * @param dataPoint : Vector of Int
   * @param len : Number of data-points of the dataSet on which knnJoin is to be done
   * @param randomSize : the number of iterations which has to be carried out
   *
   * @return an RDD of Vectors of Int on which simple KNN needs to be applied with respect
   *         to the data-point
   */
  def knnJoin(dataSet: DataSet[LabeledVector],
              dataPoint: LabeledVector,
              len: Int,
              randomSize: Int, metric: DistanceMetric): DataSet[LabeledVector] = {
    val size = dataSet.first(1).collect().toSeq(0).vector.size
    val rand = new Array[Int](size)
    val randomValue = new Random

    val model = zScore.computeScore(dataSet)
    val dataScore = zScore.scoreOfDataPoint(dataPoint.vector)

    for (count <- 0 to size - 1) rand(count) = 0

    var compute = knnJoin_perIteration(dataSet, dataPoint, DenseVector(rand), len, model, dataScore)
    val bcKey = "RAND"

    //TODO replace with iterate
    for (i <- 2 to randomSize) {
      //random vector
      for (i <- 0 to size - 1) rand(i) = randomValue.nextInt(10)
      var kLooped = -1
      val updatedData: DataSet[LabeledVector] = dataSet.map(
        mapper = new RichMapFunction[LabeledVector, LabeledVector]() {
          var rand: mutable.Buffer[Array[Int]] = null

          override def open(config: Configuration): Unit = {
            rand = getRuntimeContext
              .getBroadcastVariable[Array[Int]]("RAND").asScala
          }

          def map(in: LabeledVector): LabeledVector = {
            kLooped = -1
            val size = in.vector.size
            val arr = rand.toArray.head
            val strings = in.vector.map(word => word._2 + arr({
              kLooped = kLooped + 1
              //Todo second boarcastset
              kLooped % size
            }));
            val newVector = strings.map(el => el.toDouble).toArray
            new LabeledVector(in.label, new DenseVector(newVector))
          }

        }
      ).withBroadcastSet(ExecutionEnvironment.getExecutionEnvironment.fromElements(rand), bcKey) // 2. Broadcast the DataSet


      val newDataValue = dataPoint.vector.map(word => word._2 + rand({
        kLooped = kLooped + 1
        kLooped % size
      })).toArray

      val newDataPoint = new LabeledVector(dataPoint.label, DenseVector(newDataValue))

      //
      val modelLooped = zScore.computeScore(updatedData)
      val dataScorelooped = zScore.scoreOfDataPoint(newDataPoint.vector)

      val looped = knnJoin_perIteration(updatedData, newDataPoint, DenseVector(rand),
        len, modelLooped, dataScorelooped)
      var index = -1;
      /*      val cleaned = looped.map(line => {
              index = -1
              val vec = line.vector.map(word => word._2.toInt - rand({
                index = index + 1
                index % size
              })).toArray
              new LabeledVector(line.label, DenseVector(vec))
            })*/

      val cleaned = looped.map(mapper = new RichMapFunction[LabeledVector, LabeledVector] {
        var rand: mutable.Buffer[Array[Int]] = null

        override def open(config: Configuration): Unit = {
          rand = getRuntimeContext
            .getBroadcastVariable[Array[Int]]("RAND").asScala
        }

        override def map(line: LabeledVector): LabeledVector = {
          var index = -1
          val size = line.vector.size
          val arr = rand.toArray.head
          val vec = line.vector.map(word => word._2 - arr({
            index = index + 1
            index % size
          })).toArray
          new LabeledVector(line.label, DenseVector(vec))

        }
      }).withBroadcastSet(ExecutionEnvironment.getExecutionEnvironment.fromElements(rand), bcKey)

      compute = compute.union(cleaned)
    }

    //    zKNN(removeRedundantEntries(compute), dataPoint.vector, len, metric)
    removeRedundantEntries(compute)
  }

  /**
   * Computes the nearest neighbors in the data-set for the data-point against which KNN
   * has to be applied for A SINGLE ITERATION
   *
   * @param data : Dataset of Vectors of Int, which is the data-set in which knnJoin has to be
   *             undertaken
   * @param dataPoint : Vector of Int, which is the data-point with which knnJoin is done 
   *                  with the data-set
   * @param randPoint : Vector of Int, it's the random vector generated in each iteration
   * @param len : The number of data-points from the data-set on which knnJoin is to be done
   * @param zScore : RDD of (Long,Long), which is the ( <line_no> , <zscore> ) for each entry
   *               of the dataset
   * @param dataScore : Long value of z-score of the data-point
   *
   * @return an RDD of the nearest 2*len entries from the data-point on which KNN needs to be
   *         undertaken for that iteration
   */


  private def knnJoin_perIteration(data: DataSet[(LabeledVector)],
                                   dataPoint: LabeledVector,
                                   randPoint: org.apache.flink.ml.math.Vector,
                                   len: Int,
                                   zScore: DataSet[(Long, BigInt)],
                                   dataScore: BigInt) = {

    val greaterScoreTmp = zScore.filter(word => word._2 > dataScore).map(word => (word._2.bigInteger -> word._1)).
      sortPartition(0, Order.ASCENDING).map(w => (w._2))

    println("Score: " + greaterScoreTmp.count())

    val greaterScore = greaterScoreTmp.zipWithIndex.map(el => el._2 -> el._1)


    val lesserScoreTmp = zScore.filter(word => word._2 <= dataScore).map(wr => wr._1)

    println("Lesser Score:" + lesserScoreTmp.count())

    val lesserScore = lesserScoreTmp.zipWithIndex


    if (greaterScore.count() > len && lesserScore.count() > len) {
      val trimtmp = greaterScore.filter(word => word._2 < len).map(word => word._1).
        union(lesserScore.filter(word => word._2 < len).map(word => word._1))


      val trim = trimtmp.map(el => (el, 1))
      val join = data.map(element => (element.label.toLong, element.vector)).join(trim).where(0).equalTo(0) {
        (left, right) => {
          new LabeledVector(right._1, left._2)
        }
      }
      join
    }
    else if (greaterScore.count() < len) {
      val lenMod = len + (len - greaterScore.count)
      val trimtmp = greaterScore.map(word => word._1)
        .union(lesserScore.filter(word => word._2 < lenMod)
        .map(word => word._1))

      val trim = trimtmp.map(el => (el, 1))
      val join = data.map(element => (element.label.toLong, element.vector)).join(trim).where(0).equalTo(0) {
        (left, right) => {
          new LabeledVector(right._1, left._2)
        }
      }
      join
    }
    else {
      val lenMod = len + (len - lesserScore.count)
      val trimtmp = greaterScore.filter(word => word._2 < lenMod).map(el => el._1)
        .union(lesserScore.map(el => (el._1)))

      val trim = trimtmp.map(el => (el, 1))
      val join = data.map(element => (element.label.toLong, element.vector)).join(trim).where(0).equalTo(0) {
        (left, right) => {
          new LabeledVector(right._1, left._2)
        }
      }
      join
    }

  }

  private def removeRedundantEntries(DataSet: DataSet[LabeledVector]): DataSet[LabeledVector] = {
    return DataSet.map(vr => (vr.label, vr.vector)).distinct(0).map(el => new LabeledVector(el._1, el._2))
  }

  def neighbors(reducedData: DataSet[LabeledVector],
                dataPoint: org.apache.flink.ml.math.Vector
                , k: Int, metric: DistanceMetric): DataSet[(Long, Long, Double)] = {

    val distData = reducedData.map(word => metric.distance(dataPoint, word.vector) -> word)
      .sortPartition(0, Order.ASCENDING)
      .zipWithIndex.filter(wl => wl._1 < k).map(wl => (dataPoint.size.toLong, wl._2._2.label.toLong, wl._2._1))

    println("Got data " + distData.toString)
    distData

    /** Spark Code
      * val distData = reducedData.map(word => euclideanDist(dataPoint, word) -> word)
            .sortByKey(true)
            .zipWithIndex()
            .filter(word => word._2 < k).map(word => word._1._2)
    distData
      */

  }

}
