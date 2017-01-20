/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
* */
package io.github.mandar2812.PlasmaML.omni

import java.io.File
import java.text.SimpleDateFormat
import java.util.{Calendar, Date, GregorianCalendar}

import breeze.linalg.{DenseMatrix, DenseVector}
import com.github.tototoshi.csv.CSVWriter
import com.quantifind.charts.Highcharts._
import io.github.mandar2812.dynaml.evaluation.RegressionMetrics
import io.github.mandar2812.dynaml.DynaMLPipe
import io.github.mandar2812.dynaml.kernels._
import io.github.mandar2812.dynaml.models.gp.{GPNarModel, GPRegression}
import io.github.mandar2812.dynaml.optimization.{GradBasedGlobalOptimizer, GridSearch}
import io.github.mandar2812.dynaml.pipes.{DataPipe, StreamDataPipe}
import org.apache.log4j.Logger


/**
  * @author mandar2812
  * date: 22/11/15.
  *
  * Test a GP-NAR model on the Omni Data set
  */
object TestOmniAR {

  def apply(trainstart: String = "2008/01/01/00",
            trainend: String = "2008/01/10/23",
            start: String = "2006/12/28/00",
            end: String = "2006/12/29/23",
            kernel: LocalScalarKernel[DenseVector[Double]],
            delta: Int, timeLag:Int,
            noise: LocalScalarKernel[DenseVector[Double]],
            column: Int, grid: Int,
            step: Double, globalOpt: String,
            stepSize: Double = 0.05,
            maxIt: Int = 200,
            action: String = "test") =
    runExperiment(trainstart, trainend, start, end,
      kernel, delta, timeLag, stepPred = 0, noise,
      column, grid, step, globalOpt,
      Map("tolerance" -> "0.0001",
        "step" -> stepSize.toString,
        "maxIterations" -> maxIt.toString), action)

  def runExperiment(trainstart: String = "", trainend: String = "",
                    start: String = "", end: String = "",
                    kernel: LocalScalarKernel[DenseVector[Double]],
                    deltaT: Int = 2, timelag:Int = 0,
                    stepPred: Int = 3,
                    noise: LocalScalarKernel[DenseVector[Double]],
                    column: Int = 40, grid: Int = 5,
                    step: Double = 0.2, globalOpt: String = "ML",
                    opt: Map[String, String],
                    action: String = "test"): Seq[Seq[Double]] = {
    //Load Omni data into a stream
    //Extract the time and Dst values

    val names = Map(
      24 -> "Solar Wind Speed",
      16 -> "I.M.F Bz",
      40 -> "Dst",
      41 -> "AE",
      38 -> "Kp",
      39 -> "Sunspot Number",
      28 -> "Plasma Flow Pressure")

    val logger = Logger.getLogger(this.getClass)

    val greg: GregorianCalendar = new GregorianCalendar()
    val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy/MM/dd/HH")

    val trainDateS: Date = sdf.parse(trainstart)
    val trainDateE: Date = sdf.parse(trainend)

    greg.setTime(trainDateS)
    val traindayStart = greg.get(Calendar.DAY_OF_YEAR)
    val trainhourStart = greg.get(Calendar.HOUR_OF_DAY)
    val trainstampStart = (traindayStart * 24) + trainhourStart
    val yearTrain = greg.get(Calendar.YEAR)


    greg.setTime(trainDateE)
    val traindayEnd = greg.get(Calendar.DAY_OF_YEAR)
    val trainhourEnd = greg.get(Calendar.HOUR_OF_DAY)
    val trainstampEnd = (traindayEnd * 24) + trainhourEnd

    val dateS: Date = sdf.parse(start)
    val dateE: Date = sdf.parse(end)

    greg.setTime(dateS)
    val dayStart = greg.get(Calendar.DAY_OF_YEAR)
    val hourStart = greg.get(Calendar.HOUR_OF_DAY)
    val stampStart = (dayStart * 24) + hourStart
    val yearTest = greg.get(Calendar.YEAR)


    greg.setTime(dateE)
    val dayEnd = greg.get(Calendar.DAY_OF_YEAR)
    val hourEnd = greg.get(Calendar.HOUR_OF_DAY)
    val stampEnd = (dayEnd * 24) + hourEnd

    val preProcessPipe = DynaMLPipe.fileToStream >
      DynaMLPipe.replaceWhiteSpaces >
      DynaMLPipe.extractTrainingFeatures(
        List(0,1,2,column),
        Map(
          16 -> "999.9", 21 -> "999.9",
          24 -> "9999.", 23 -> "999.9",
          40 -> "99999", 22 -> "9999999.",
          25 -> "999.9", 28 -> "99.99",
          27 -> "9.999", 39 -> "999",
          45 -> "99999.99", 46 -> "99999.99",
          47 -> "99999.99")
      ) > DynaMLPipe.removeMissingLines >
      DynaMLPipe.extractTimeSeries((year,day,hour) => (day * 24) + hour)

    val processTraining = preProcessPipe >
      StreamDataPipe((couple: (Double, Double)) =>
        couple._1 >= trainstampStart && couple._1 <= trainstampEnd) >
      DynaMLPipe.deltaOperation(deltaT, timelag)

    val processTest = preProcessPipe >
      StreamDataPipe((couple: (Double, Double)) =>
        couple._1 >= stampStart && couple._1 <= stampEnd) >
      DynaMLPipe.deltaOperation(deltaT, timelag)


    //pipe training data to model and then generate test predictions
    //create RegressionMetrics instance and produce plots
    val modelTrainTest =
      (trainTest: ((Stream[(DenseVector[Double], Double)],
        Stream[(DenseVector[Double], Double)]),
        (DenseVector[Double], DenseVector[Double]))) => {

        kernel.blocked_hyper_parameters =
          if (!opt.contains("block") || opt("block").isEmpty) List()
          else opt("block").split(',').toList

        val model = new GPNarModel(deltaT, kernel, noise, trainTest._1._1)
        val num_training = trainTest._1._1.length

        // If a validation set is specified, process it
        // using the above pre-process Data Pipes and
        // feed it into the model instance.

        if(opt.contains("validationStart") && opt.contains("validationEnd")) {
          val validationDateS: Date = sdf.parse(opt("validationStart"))
          val validationDateE: Date = sdf.parse(opt("validationEnd"))

          greg.setTime(validationDateS)
          val valdayStart = greg.get(Calendar.DAY_OF_YEAR)
          val valhourStart = greg.get(Calendar.HOUR_OF_DAY)
          val valstampStart = (valdayStart * 24) + valhourStart
          val yearVal = greg.get(Calendar.YEAR)


          greg.setTime(validationDateE)
          val valdayEnd = greg.get(Calendar.DAY_OF_YEAR)
          val valhourEnd = greg.get(Calendar.HOUR_OF_DAY)
          val valstampEnd = (valdayEnd * 24) + valhourEnd

          val processValidation = preProcessPipe >
            StreamDataPipe((couple: (Double, Double)) =>
              couple._1 >= valstampStart && couple._1 <= valstampEnd) >
            DynaMLPipe.deltaOperation(deltaT, timelag)

          val featureDims = trainTest._2._1.length - 1

          val meanFeatures = trainTest._2._1(0 until featureDims)
          val stdDevFeatures = trainTest._2._2(0 until featureDims)

          val meanTargets = trainTest._2._1(-1)
          val stdDevTargets = trainTest._2._2(-1)

          // Set processTargets to a data pipe
          // which re scales the predicted outputs and actual
          // outputs to their orignal scales using the calculated
          // mean and standard deviation of the targets.
          model.processTargets = StreamDataPipe((predictionCouple: (Double, Double)) =>
            (predictionCouple._1*stdDevTargets + meanTargets,
              predictionCouple._2*stdDevTargets + meanTargets)
          )

          val standardizeValidationInstances = StreamDataPipe(
            (instance: (DenseVector[Double], Double)) => {

              ((instance._1 - meanFeatures) :/ stdDevFeatures,
                (instance._2 - meanTargets)/stdDevTargets)
            })

          /*model.scoresToEnergy = DataPipe((scoresAndLabels) => {
            scoresAndLabels.map((couple) => math.abs(couple._1-couple._2)).max
          })*/

          model.validationSet_((processValidation > standardizeValidationInstances) run
            "data/omni2_"+yearTrain+".csv")

        }


        val gs = globalOpt match {
          case "GS" => new GridSearch[model.type](model)
            .setGridSize(grid)
            .setStepSize(step)
            .setLogScale(opt("logScale").toBoolean)

          case "ML" => new GradBasedGlobalOptimizer[GPRegression](model)
        }

        val startConf = kernel.effective_state ++ noise.effective_state

        if (action != "energyLandscape") {
          val (_, conf) = gs.optimize(startConf, opt)

          model.persist(conf)

          val res = model.test(trainTest._1._2)

          val deNormalize = DataPipe((list: List[(Double, Double, Double, Double)]) =>
            list.map{l => (l._1*trainTest._2._2(-1) + trainTest._2._1(-1),
              l._2*trainTest._2._2(-1) + trainTest._2._1(-1),
              l._3*trainTest._2._2(-1) + trainTest._2._1(-1),
              l._4*trainTest._2._2(-1) + trainTest._2._1(-1))})

          val deNormalize1 = DataPipe((list: List[(Double, Double)]) =>
            list.map{l => (l._1*trainTest._2._2(-1) + trainTest._2._1(-1),
              l._2*trainTest._2._2(-1) + trainTest._2._1(-1))})

          val scoresAndLabelsPipe =
            DataPipe(
              (res: Seq[(DenseVector[Double], Double, Double, Double, Double)]) =>
                res.map(i => (i._3, i._2, i._4, i._5)).toList) > deNormalize

          val scoresAndLabels = scoresAndLabelsPipe.run(res)

          val metrics = new RegressionMetrics(scoresAndLabels.map(i => (i._1, i._2)),
            scoresAndLabels.length)

          val (name, name1) =
            if(names.contains(column)) (names(column), names(column))
            else ("Value","Time Series")

          metrics.setName(name)

          //Model Predicted Output, only in stepPred > 0
          var mpoRes: Seq[Double] = Seq()
          if(stepPred > 0) {
            //Now test the Model Predicted Output and its performance.
            val mpo = model.modelPredictedOutput(stepPred) _
            val testData = trainTest._1._2


            val predictedOutput:List[Double] = testData.grouped(stepPred).map((partition) => {
              val preds = mpo(partition.head._1).map(_._1)
              if(preds.length == partition.length) {
                preds.toList
              } else {
                preds.take(partition.length).toList
              }
            }).foldRight(List[Double]())(_++_)

            val outputs = testData.map(_._2).toList

            val res2 = predictedOutput zip outputs
            val scoresAndLabels2 = deNormalize1.run(res2)

            val mpoMetrics = new RegressionMetrics(scoresAndLabels2,
              scoresAndLabels2.length)

            logger.info("Printing Model Predicted Output (MPO) Performance Metrics")
            mpoMetrics.print()

            val timeObsMPO = scoresAndLabels2.map(_._2).zipWithIndex.min._2
            val timeModelMPO = scoresAndLabels2.map(_._1).zipWithIndex.min._2
            logger.info("Timing Error; MPO, "+stepPred+
              " hours ahead Prediction: "+(timeObsMPO-timeModelMPO))

            mpoMetrics.generateFitPlot()
            //Plotting time series prediction comparisons
            line((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._2))
            hold()
            line((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._1))
            line((1 to scoresAndLabels2.length).toList, scoresAndLabels2.map(_._1))
            legend(List("Time Series", "Predicted Time Series (one hour ahead)",
              "Predicted Time Series ("+stepPred+" hours ahead)"))
            unhold()

            mpoRes = Seq(yearTrain.toDouble, yearTest.toDouble, deltaT.toDouble,
              stepPred.toDouble, num_training.toDouble,
              trainTest._1._2.length.toDouble,
              mpoMetrics.mae, mpoMetrics.rmse, mpoMetrics.Rsq,
              mpoMetrics.corr, mpoMetrics.modelYield,
              timeObsMPO.toDouble - timeModelMPO.toDouble,
              scoresAndLabels2.map(_._1).min)

          }

          metrics.generateFitPlot()
          //Plotting time series prediction comparisons
          line((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._2))
          hold()
          line((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._1))
          spline((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._3))
          hold()
          spline((1 to scoresAndLabels.length).toList, scoresAndLabels.map(_._4))
          legend(List(name1, "Predicted "+name1+" (one hour ahead)", "Lower Bar", "Higher Bar"))
          unhold()

          val (timeObs, timeModel, peakValuePred, peakValueAct) = names(column) match {
            case "Dst" =>
              (scoresAndLabels.map(_._2).zipWithIndex.min._2,
                scoresAndLabels.map(_._1).zipWithIndex.min._2,
                scoresAndLabels.map(_._1).min,
                scoresAndLabels.map(_._2).min)
            case _ =>
              (scoresAndLabels.map(_._2).zipWithIndex.max._2,
                scoresAndLabels.map(_._1).zipWithIndex.max._2,
                scoresAndLabels.map(_._1).max,
                scoresAndLabels.map(_._2).max)
          }

          logger.info("Timing Error; OSA Prediction: "+(timeObs-timeModel))

          logger.info("Printing One Step Ahead (OSA) Performance Metrics")
          metrics.print()

          action match {
            case "test" =>
              Seq(
                Seq(yearTrain.toDouble, yearTest.toDouble, deltaT.toDouble,
                  1.0, num_training.toDouble, trainTest._1._2.length.toDouble,
                  metrics.mae, metrics.rmse, metrics.Rsq,
                  metrics.corr, metrics.modelYield,
                  timeObs.toDouble - timeModel.toDouble,
                  peakValuePred,
                  peakValueAct),
                mpoRes
              )

            case "predict" => scoresAndLabels.map(i => Seq(i._2, i._1))

            case "predict_error_bars" => scoresAndLabels.map(i => Seq(i._2, i._1, i._3, i._4))
          }

        } else {
          gs.getEnergyLandscape(startConf, opt).map(k => {
            Seq(k._1) ++ kernel.blocked_hyper_parameters.map(p => kernel.state(p)) ++
              kernel.hyper_parameters.filter(l => !kernel.blocked_hyper_parameters.contains(l)).map(k._2(_)) ++
              noise.hyper_parameters.map(k._2(_))
          })
        }

      }


    val trainTestPipe = DataPipe(processTraining, processTest) >
      DynaMLPipe.trainTestGaussianStandardization >
      DataPipe(modelTrainTest)

    trainTestPipe.run(("data/omni2_"+yearTrain+".csv",
      "data/omni2_"+yearTest+".csv"))

  }

}

object DstARExperiment {

  def apply(years: List[Int] = (2007 to 2014).toList,
            testYears: List[Int] = (2000 to 2015).toList,
            modelSizes: List[Int] = List(50, 100, 150),
            deltas: List[Int] = List(1, 2, 3),
            stepAhead: Int, bandwidth: Double,
            noise: Double, num_test: Int,
            column: Int, grid: Int, step: Double) = {

    val writer = CSVWriter.open(new File("data/OmniRes.csv"), append = true)

    years.foreach((year) => {
      testYears.foreach((testYear) => {
        deltas.foreach((delta) => {
          modelSizes.foreach((modelSize) => {
            TestOmniAR.runExperiment(
              year.toString+"/01/01/00",
              year.toString+"/01/10/23",
              testYear.toString+"/12/12/00",
              testYear.toString+"/12/12/23",
              new FBMKernel(bandwidth),
              delta, 0, stepAhead,
              new DiracKernel(noise),
              column,
              grid, step, "GS",
              Map("tolerance" -> "0.0001",
                "step" -> "0.1",
                "maxIterations" -> "100"))
              .foreach(res => writer.writeRow(res))
          })
        })
      })
    })

    writer.close()
  }

  def apply(trainstart: String, trainend: String,
            kernel: LocalScalarKernel[DenseVector[Double]],
            noise: LocalScalarKernel[DenseVector[Double]],
            deltas: List[Int],
            options: Map[String, String]) = {
    val writer =
      CSVWriter.open(new File("data/"+
        options("fileID")+
        "OmniARStormsRes.csv"),
        append = true)

    val initialKernelState = kernel.state
    val initialNoiseState = noise.state

    deltas.foreach(modelOrder => {
      val stormsPipe =
        DynaMLPipe.fileToStream >
          DynaMLPipe.replaceWhiteSpaces >
          StreamDataPipe((stormEventData: String) => {
            val stormMetaFields = stormEventData.split(',')

            val eventId = stormMetaFields(0)
            val startDate = stormMetaFields(1)
            val startHour = stormMetaFields(2).take(2)

            val endDate = stormMetaFields(3)
            val endHour = stormMetaFields(4).take(2)

            val minDst = stormMetaFields(5).toDouble

            val stormCategory = stormMetaFields(6)
            kernel.setHyperParameters(initialKernelState)
            noise.setHyperParameters(initialNoiseState)
            val res = TestOmniAR.runExperiment(trainstart, trainend,
              startDate+"/"+startHour, endDate+"/"+endHour,
              kernel, modelOrder, 0, 0, noise,
              40, options("grid").toInt, options("step").toDouble,
              options("globalOpt"), options, action = options("action"))

            if(options("action") == "test") {
              val row = Seq(
                eventId, stormCategory, modelOrder,
                res.head(4), res.head(7), res.head(9),
                res.head.last-minDst, minDst, res.head(11)
              )

              writer.writeRow(row)
            } else {
              writer.writeAll(res)
            }

          })

      stormsPipe.run("data/geomagnetic_storms.csv")
    })
  }
}