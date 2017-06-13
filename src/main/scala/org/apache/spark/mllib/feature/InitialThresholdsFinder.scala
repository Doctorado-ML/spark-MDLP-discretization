/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.mllib.feature

import org.apache.spark.Partitioner
import org.apache.spark.rdd.RDD
import InitialThresholdsFinder._

object InitialThresholdsFinder {
  /**
    * @return true if f1 and f2 define a boundary.
    *   It is a boundary if there is more than one class label present when the two are combined.
    */
  private val isBoundary = (f1: Array[Long], f2: Array[Long]) => {
    (f1, f2).zipped.map(_ + _).count(_ != 0) > 1
  }


  // If one of the unique values is NaN, use the other one, otherwise take the midpoint.
  def midpoint(x1: Float, x2: Float): Float = {
    if (x1.isNaN) x2
    else if (x2.isNaN) x1
    else (x1 + x2) / 2.0F
  }
}

/**
  * Find the initial thresholds. Look at the unique points and find ranges where the distribution
  * of labels is identical and split only where they are not.
  */
class InitialThresholdsFinder() extends Serializable{

  /**
    * Computes the initial candidate cut points by feature.
    *
    * @param points RDD with distinct points by feature ((feature, point), class values).
    * @param nLabels number of class labels
    * @param nFeatures expected number of features
    * @param maxByPart maximum number of values allowed in a partition
    * @return RDD of candidate points.
    */
  def findInitialThresholds(points: RDD[((Int, Float), Array[Long])], nFeatures: Int, nLabels: Int, maxByPart: Int) = {

    val featureInfo = createFeatureInfoList(points, maxByPart, nFeatures)
    val totalPartitions = featureInfo.last._5 + featureInfo.last._6

    // Get the first element cuts and their order index by partition for the boundary points evaluation
    val pointsWithIndex = points.zipWithIndex().map( v => ((v._1._1._1, v._1._1._2, v._2), v._1._2))

    /** This custom partitioner will partition by feature and subdivide features into smaller partitions if large */
    class FeaturePartitioner[V]()
      extends Partitioner {

      def getPartition(key: Any): Int = {
        val (featureIdx, cut, sortIdx) = key.asInstanceOf[(Int, Float, Long)]
        val (_, _, sumValuesBefore, partitionSize, _, sumPreviousNumParts) = featureInfo(featureIdx)
        val partKey = sumPreviousNumParts + (Math.max(0, sortIdx - sumValuesBefore - 1) / partitionSize).toInt
        partKey
      }

      override def numPartitions: Int = totalPartitions
    }

    // partition by feature (possibly sub-partitioned features) instead of default partitioning strategy
    val partitionedPoints = pointsWithIndex.partitionBy(new FeaturePartitioner())

    val result = partitionedPoints.mapPartitionsWithIndex({ (index, it) =>
      if (it.hasNext) {
        var ((lastFeatureIdx, lastX, _), lastFreqs) = it.next()
        //println("the first value of part " + index + " is " + lastX)
        var result = Seq.empty[((Int, Float), Array[Long])]
        var accumFreqs = lastFreqs

        for (((fIdx, x, _), freqs) <- it) {
          if (isBoundary(freqs, lastFreqs)) {
            // new boundary point: midpoint between this point and the previous one
            result = ((lastFeatureIdx, midpoint(x, lastX)), accumFreqs.clone) +: result
            accumFreqs = Array.fill(nLabels)(0L)
          }

          lastX = x
          lastFeatureIdx = fIdx
          lastFreqs = freqs
          accumFreqs = (accumFreqs, freqs).zipped.map(_ + _)
        }

        // The last X is either on a feature or a partition boundary
        result = ((lastFeatureIdx, lastX), accumFreqs.clone) +: result
        result.reverse.toIterator
      } else {
        Iterator.empty
      }
    })
    result
  }


  /**
    * Computes the initial candidate cut points by feature. This is a non-deterministic, but and faster version.
    * This version may generate some non-boundary points when processing limits in partitions (related to issue #14).
    * This approximate solution may slightly affect the final set of cutpoints, which will provoke
    * some unit tests to fail. It should not be relevant in large scenarios, where peformance is more valuable.
    * If you prefer a deterministic solution, please try 'findInitialThresholds' (which is totally exact but slower).
    *
    * @param sortedValues RDD with distinct points by feature ((feature, point), class values).
    * @param nLabels number of class labels
    * @param maxByPart maximum number of values allowed in a partition
    * @return RDD of candidate points.
    */
  def findFastInitialThresholds(sortedValues: RDD[((Int, Float), Array[Long])], nLabels: Int, maxByPart: Int) = {
    val numPartitions = sortedValues.partitions.length
    val sc = sortedValues.context
     // Get the first elements by partition for the boundary points evaluation
    val firstElements = sc.runJob(sortedValues, (it =>
      if (it.hasNext) Some(it.next()._1) else None): (Iterator[((Int, Float), Array[Long])]) => Option[(Int, Float)])

    val bcFirsts = sc.broadcast(firstElements)

    sortedValues.mapPartitionsWithIndex({ (index, it) =>
      if (it.hasNext) {
        var ((lastFeatureIdx, lastX), lastFreqs) = it.next()
        var result = Seq.empty[((Int, Float), Array[Long])]
        var accumFreqs = lastFreqs

        for (((featureIdx, x), freqs) <- it) {
          if (featureIdx != lastFeatureIdx) {
            // new attribute: add last point from the previous one
            result = ((lastFeatureIdx, lastX), accumFreqs.clone) +: result
            accumFreqs = Array.fill(nLabels)(0L)
          } else if (isBoundary(freqs, lastFreqs)) {
            // new boundary point: midpoint between this point and the previous one
            result = ((lastFeatureIdx, (x + lastX) / 2), accumFreqs.clone) +: result
            accumFreqs = Array.fill(nLabels)(0L)
          }

          lastFeatureIdx = featureIdx
          lastX = x
          lastFreqs = freqs
          accumFreqs = (accumFreqs, freqs).zipped.map(_ + _)
        }

        // Evaluate the last point in this partition with the first one in the next partition
        val lastPoint = if (index < (numPartitions - 1)) {
          bcFirsts.value(index + 1) match {
            case Some((featureIdx, x)) => if (featureIdx != lastFeatureIdx) lastX else (x + lastX) / 2
            case None => lastX // last point in the attribute
          }
        }else{
          lastX // last point in the dataset
        }
        (((lastFeatureIdx, lastPoint), accumFreqs.clone) +: result).reverse.toIterator
      } else {
        Iterator.empty
      }
    })
  }


  /**
    * @param points all unique points
    * @param maxByPart maximum number of values in a partition
    * @param nFeatures expected number of features.
    * @return a list of info for each partition. The values in the info tuple are:
    *  (featureIdx, numUniqueValues, sumValsBeforeFirst, partitionSize, numPartitionsForFeature, sumPreviousPartitions)
    */
  def createFeatureInfoList(points: RDD[((Int, Float), Array[Long])],
                            maxByPart: Int, nFeatures: Int): IndexedSeq[(Int, Long, Long, Int, Int, Int)] = {
    // First find the number of points in each partition, ordered by featureIdx
    var countsByFeatureIdx = points.map(_._1._1).countByValue().toArray
    // if there are features not represented, add them manually.
    // This can happen if there are some features with all 0 values (rare, but we need to handle it).
    val representedFeatures = countsByFeatureIdx.map(_._1).toSet
    if (countsByFeatureIdx.length < nFeatures) {
      for (i <- 0 until nFeatures) {
        if (!representedFeatures.contains(i)) {
          countsByFeatureIdx +:= (i, 1L)  // (featureIdx, single 0 value)
        }
      }
    }
    countsByFeatureIdx = countsByFeatureIdx.sortBy(_._1)

    var lastCount: Long = 0
    var sum: Long = 0
    var sumPreviousNumParts: Int = 0

    countsByFeatureIdx.map(x => {
      val partSize = Math.ceil(x._2 / Math.ceil(x._2 / maxByPart.toFloat)).toInt
      val numParts = Math.ceil(x._2 / partSize.toFloat).toInt
      val info = (x._1, x._2, sum + lastCount, partSize, numParts, sumPreviousNumParts)
      sum += lastCount
      sumPreviousNumParts += numParts
      lastCount = x._2
      info
    })
  }

}
