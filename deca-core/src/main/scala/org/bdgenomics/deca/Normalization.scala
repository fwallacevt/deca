package org.bdgenomics.deca

import breeze.linalg.sum
import breeze.numerics.sqrt
import breeze.stats.meanAndVariance
import org.apache.spark.SparkContext
import org.apache.spark.mllib.linalg.distributed.{ IndexedRow, IndexedRowMatrix }
import org.bdgenomics.adam.models.ReferenceRegion
import org.bdgenomics.deca.Timers._
import org.bdgenomics.deca.util.MLibUtils
import org.bdgenomics.utils.misc.Logging
import scala.util.control.Breaks._

object Normalization extends Serializable with Logging {

  def filterColumns(matrix: IndexedRowMatrix, targets: Array[ReferenceRegion],
                    minMean: Double = Double.MinValue, maxMean: Double = Double.MaxValue,
                    minSD: Double = 0, maxSD: Double = Double.MaxValue): (IndexedRowMatrix, Array[ReferenceRegion]) = {
    val colStats = matrix.toRowMatrix.computeColumnSummaryStatistics
    val colMeans = MLibUtils.mllibVectorToDenseBreeze(colStats.mean)
    val colSDs = sqrt(MLibUtils.mllibVectorToDenseBreeze(colStats.variance))

    val toKeep = (colMeans :>= minMean) :& (colMeans :<= maxMean) :& (colSDs :>= minSD) :& (colSDs :<= maxSD)
    val newTargets = targets.zipWithIndex.collect { case (target, index) if toKeep(index) => target }

    val toKeepBroadcast = SparkContext.getOrCreate().broadcast(toKeep)
    val filteredMatrix = new IndexedRowMatrix(matrix.rows.map(row => {
      val vector = MLibUtils.mllibVectorToDenseBreeze(row.vector)
      IndexedRow(row.index, MLibUtils.breezeVectorToMLlib(vector(toKeepBroadcast.value)))
    }))

    (filteredMatrix, newTargets)
  }

  def meanCenterColumns(matrix: IndexedRowMatrix): IndexedRowMatrix = {
    val colStats = matrix.toRowMatrix.computeColumnSummaryStatistics
    new IndexedRowMatrix(matrix.rows.map(row => {
      IndexedRow(
        row.index,
        MLibUtils.breezeVectorToMLlib(
          MLibUtils.mllibVectorToDenseBreeze(row.vector) - MLibUtils.mllibVectorToDenseBreeze(colStats.mean)))
    }))
  }

  def filterRows(matrix: IndexedRowMatrix,
                 minMean: Double = Double.MinValue, maxMean: Double = Double.MaxValue,
                 minSD: Double = 0, maxSD: Double = Double.MaxValue): IndexedRowMatrix = {
    new IndexedRowMatrix(matrix.rows.filter(row => {
      val stats = meanAndVariance(MLibUtils.mllibVectorToDenseBreeze(row.vector))
      val mean = stats.mean
      val sd = Math.sqrt(stats.variance)
      mean >= minMean && mean <= maxMean && sd >= minSD && sd <= maxSD
    }))
  }

  def zscoreRows(matrix: IndexedRowMatrix): IndexedRowMatrix = ComputeZScores.time {
    new IndexedRowMatrix(matrix.rows.map(row => {
      val vector = MLibUtils.mllibVectorToDenseBreeze(row.vector)
      val stats = meanAndVariance(vector)
      IndexedRow(row.index, MLibUtils.breezeVectorToMLlib((vector - stats.mean) / Math.sqrt(stats.variance)))
    }))
  }

  def pcaNormalization(kToRemove: Option[Int] = None, readMatrix: IndexedRowMatrix, pveMeanFactor: Double = 0.7): IndexedRowMatrix = PCANormalization.time {
    var toRemove = getOrElse(kToRemove)
    val n = Math.min(readMatrix.numRows, readMatrix.numCols)
    var k = 0
    if(toRemove) {
      k = toRemove
    } else {
      k = ((0.3528*n) / Math.pow(n, 0.39)) * 3
    }
    
    print(k)

    val ksvd = ComputeSVD.time {
      readMatrix.computeSVD(k.floor.toInt, computeU = false)
    }

    if(!toRemove) {
      // Determine components to remove
      toRemove = ksvd.s.size
      breakable {
        val kSize = ksvd.s.size - 1
        val S = MLibUtils.mllibVectorToDenseBreeze(ksvd.s)
        val componentVar = S :* S
        var componentSum: Double = 0
        if (kSize > 0) {
          componentSum = sum(componentVar(0 to kSize-1)) + ((n - kSize) * componentVar(kSize))
        } else {
          componentSum = n * componentVar(0)
        }

        val cutoff: Double = (componentSum / n) * pveMeanFactor
        for (c <- 0 until kSize) {
          if (componentVar(c) < cutoff) {
            toRemove = c
            break
          }
        }
      }
    }
    
    log.info("Removing top {} components in PCA normalization", toRemove)

    val V = MLibUtils.mllibMatrixToDenseBreeze(svd.V)

    // Broadcast subset of V matrix
    val sc = SparkContext.getOrCreate()
    val C = sc.broadcast(V(::, 0 until toRemove)) // Exclusive to toRemove

    // Remove components with row centric approach
    new IndexedRowMatrix(readMatrix.rows.map(row => {
      val rd = MLibUtils.mllibVectorToDenseBreeze(row.vector)
      var rdStar = rd.copy
      for (col <- 0 until C.value.cols) {
        val component = C.value(::, col)
        val loading = component dot rd
        rdStar :-= component * loading
      }
      IndexedRow(row.index, MLibUtils.breezeVectorToMLlib(rdStar))
    }))

  }

  def normalizeReadDepth(readMatrix: IndexedRowMatrix, targets: Array[ReferenceRegion],
                         minTargetMeanRD: Double = 10.0, maxTargetMeanRD: Double = 500.0,
                         minSampleMeanRD: Double = 25.0, maxSampleMeanRD: Double = 200.0,
                         minSampleSDRD: Double = 0.0, maxSampleSDRD: Double = 150.0,
                         pveMeanFactor: Double = 0.7,
                         maxTargetSDRDStar: Double = 30.0): (IndexedRowMatrix, Array[ReferenceRegion]) = NormalizeReadDepths.time {

    // Filter I: Filter extreme targets and samples, then mean center the data
    val (centeredRdMatrix, targFilteredRdTargets) = ReadDepthFilterI.time {
      val (targFilteredrdMatrix, targFilteredRdTargets) = filterColumns(readMatrix, targets,
        minMean = minTargetMeanRD, maxMean = maxTargetMeanRD)
      val sampFilteredRdMatrix = filterRows(targFilteredrdMatrix,
        minMean = minSampleMeanRD, maxMean = maxSampleMeanRD, minSD = minSampleSDRD, maxSD = maxSampleSDRD)
      (meanCenterColumns(sampFilteredRdMatrix), targFilteredRdTargets)
    }

    // PCA normalization
    val rdStarMatrix = pcaNormalization(centeredRdMatrix, pveMeanFactor = pveMeanFactor)

    // Filter II: Filter extremely variable targets
    val (targFilteredRdStarMatrix, targFilteredRdStarTargets) = ReadDepthFilterII.time {
      filterColumns(rdStarMatrix, targFilteredRdTargets, maxSD = maxTargetSDRDStar)
    }

    // Z-score by sample
    val zMatrix = Normalization.zscoreRows(targFilteredRdStarMatrix)

    (zMatrix, targFilteredRdStarTargets)
  }

}