package io.github.mandar2812.PlasmaML.dynamics.diffusion

import breeze.linalg.{DenseMatrix, DenseVector}
import breeze.numerics.Bessel
import breeze.stats.distributions._
import com.quantifind.charts.Highcharts.{histogram, hold, legend, line, scatter, spline, title, unhold, xAxis, yAxis}
import io.github.mandar2812.dynaml.pipes.{DataPipe, StreamDataPipe}
import io.github.mandar2812.dynaml.DynaMLPipe._
import io.github.mandar2812.dynaml.probability.{GaussianRV, MultinomialRV, RandomVariable}
import io.github.mandar2812.dynaml.utils.{combine, getStats}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import ammonite.ops._
import org.apache.log4j.Logger

/**
  * <h3>Radial Diffusion Experiments</h3>
  *
  * A set of convenient work-flows and functions for setting
  * up inference routines in the radial diffusion setting.
  * */
object RDExperiment {

  import io.github.mandar2812.PlasmaML.dynamics.diffusion.RDSettings._

  private val logger = Logger.getLogger(this.getClass)

  /**
    * Create the radial diffusion solver
    * from the domain descriptions.
    *
    * @param lShellLimits The lower and upper limits of L-shell
    * @param timeLimits The lower and upper limits of time
    * @param nL The number bins in L-shell
    * @param nT Number of bins in time
    * */
  def solver(
    lShellLimits: (Double, Double),
    timeLimits: (Double, Double),
    nL: Int, nT: Int): RadialDiffusion =
    new RadialDiffusion(lShellLimits, timeLimits, nL, nT)

  /**
    * Generate synthetic PSD data for an inference experiment.
    *
    * @param rds A radial diffusion solver of type [[RadialDiffusion]]
    * @param dll Diffusion field.
    * @param lambda Loss rate.
    * @param q Particle injection rate.
    * @param initialPSD The PSD at t = 0
    * @param noise Measurement noise to be added to the generated
    *              PSD profile.
    * @param num_boundary_points Number of points from the initial PSD state
    *                            to be sampled.
    * @param num_bulk_points Number of points to be sampled from the interior
    *                        of the space-time domain.
    * @param num_colocation_points Number of "co-location" points, i.e. points
    *                              where the radial diffusion PDE is enforced (with
    *                              some slack).
    *
    * @return The generated PSD profile, noisy observations (boundary and bulk) and
    *         co-location points.
    *
    * */
  def generateData(
    rds: RadialDiffusion,
    dll: (Double, Double) => Double,
    lambda: (Double, Double) => Double,
    q: (Double, Double) => Double,
    initialPSD: (Double) => Double)(
    noise: RandomVariable[Double],
    num_boundary_points: Int,
    num_bulk_points: Int,
    num_colocation_points: Int) = {

    val groundTruth = rds.solve(q, dll, lambda)(initialPSD)
    val ground_truth_matrix = DenseMatrix.horzcat(groundTruth.map(_.asDenseMatrix.t):_*)


    val noise_mat = DenseMatrix.tabulate[Double](rds.nL+1, rds.nT+1)((_, _) => noise.draw)
    val data: DenseMatrix[Double] = ground_truth_matrix + noise_mat

    val (lShellVec, timeVec) = rds.stencil

    val coordinateIndices = combine(Seq(lShellVec.indices, timeVec.tail.indices)).map(s => (s.head, s.last))


    val boundary_data = lShellVec.indices
      .filter(_ => Rand.uniform.draw() <= num_boundary_points.toDouble/lShellVec.length)
      .map(lIndex => {
        ((lShellVec(lIndex),0d), data(lIndex, 0))
      }).toStream

    val bulk_data = if(num_bulk_points == 0) {
      Stream.empty[((Double, Double), Double)]
    } else {
      coordinateIndices
        .filter(_ => Rand.uniform.draw() < num_bulk_points.toDouble/coordinateIndices.length)
        .map((lt) => {
          val (l, t) = (lShellVec(lt._1), timeVec(lt._2+1))
          ((l,t), data(lt._1, lt._2+1))
        }).toStream
    }

    val rowSelectorRV = MultinomialRV(DenseVector.fill[Double](lShellVec.length)(1d/lShellVec.length.toDouble))
    val colSelectorRV = MultinomialRV(DenseVector.fill[Double](timeVec.length-1)(1d/timeVec.length.toDouble))

    val colocation_points: Stream[(Double, Double)] = {
      (0 until num_colocation_points).map(_ => {
        val rowS = rowSelectorRV.draw
        val colS = colSelectorRV.draw
        (lShellVec(rowS), timeVec(colS))
      }).toStream
    }

    (groundTruth, (boundary_data, bulk_data), colocation_points)
  }

  /**
    * Defines a sensible default hyper-prior over diffusion
    * parameters.
    * */
  def hyper_prior(hyp: List[String]) = {
    hyp.filter(_.contains("base::")).map(h => (h, new LogNormal(0d, 2d))).toMap ++
      hyp.filterNot(h => h.contains("base::") || h.contains("tau")).map(h => (h, new Gaussian(0d, 2.5d))).toMap ++
      Map(
        "tau_alpha" -> new Gaussian(0d, 1d),
        "tau_beta" -> new Gamma(2d, 2d),
        "tau_b" -> new Gaussian(0d, 2.0)).filterKeys(hyp.contains)
  }

  /**
    * Print a sampling report to the console.
    * @param samples Posterior samples.
    * @param quantities Key quantities (hyper-parameters) which are
    *                   sampled, with their string representations for
    *                   pretty print.
    * @param acceptanceRate The sample acceptance rate as empirically
    *                       computed by the sampling procedure.
    * */
  def samplingReport(
    samples: Stream[Map[String, Double]],
    quantities: Map[String, Char],
    gt: Map[String, Double],
    acceptanceRate: Double,
    inferred_quantity: String = "loss"): Unit = {

    val post_vecs = samples.map(c => DenseVector(c.values.toArray))

    val post_moments = getStats(post_vecs.toList)


    println("\n:::::: MCMC Sampling Report ::::::\n")

    println(
      "Quantity: "+diffusion_quantities(inferred_quantity)+
      "(l,t) = "+0x03B1.toChar+"l^("+0x03B2.toChar+")*10^(b*K(t))")

    println("Markov Chain Acceptance Rate = "+acceptanceRate)

    samples.head.keys.zipWithIndex.foreach(c => {
      val ((key, char), index) = ((c._1, quantities(c._1)), c._2)
      println("\n------------------------------")
      println("Parameter: "+char)
      println("Ground Truth:- "+gt(key))
      println("Posterior Moments: mean = "+post_moments._1(index)+" variance = "+post_moments._2(index))
    })
  }

  /**
    * Render plots of a PSD profile returned by [[generateData()]].
    *
    * @param lShellLimits L-shell limits
    * @param timeLimits Time limits.
    * @param initialPSD The initial PSD profile (t = 0)
    * @param solution Generated PSD profile as returned by [[RadialDiffusion]]
    * @param Kp Kp index as a function ([[DataPipe]]) of time.
    * */
  def visualisePSD(
    lShellLimits: (Double, Double),
    timeLimits: (Double, Double), nL: Int, nT: Int)(
    initialPSD: (Double) => Double,
    solution: Stream[DenseVector[Double]],
    Kp: DataPipe[Double, Double]): Unit = {

    val lMax = 10
    val tMax = 10

    val (lShellVec, timeVec) = RadialDiffusion.buildStencil(lShellLimits, nL, timeLimits, nT)

    line(timeVec.map(t => (t, Kp(t))).toSeq)
    xAxis("time")
    yAxis("Kp")
    title("Evolution of Kp")

    spline(lShellVec.map(l => (l, initialPSD(l))).toSeq)
    xAxis("L")
    yAxis("f(L, 0)")
    title("Phase Space Density Profile, t = 0")

    /*
     *
     * First set of plots
     *
     * */

    spline(timeVec.zip(solution.map(_(0))))
    hold()

    (0 until lMax).foreach(l => {
      spline(timeVec.zip(solution.map(_(l*20))))
    })

    unhold()

    legend(DenseVector.tabulate[Double](lMax+1)(i =>
      if(i < nL) lShellLimits._1+(deltaL*i*20)
      else lShellLimits._2).toArray.map(s => "L = "+"%3f".format(s)))
    title("Evolution of Phase Space Density f(L,t)")
    xAxis("time")
    yAxis("f(L,t)")

   /*
    *
    *  Second set of plots
    *
    * */
    spline(lShellVec.toArray.toSeq.zip(solution.head.toArray.toSeq))
    hold()

    (0 until tMax).foreach(t => {
      spline(lShellVec.toArray.toSeq.zip(solution(t*5).toArray.toSeq))
    })
    unhold()

    legend(DenseVector.tabulate[Double](tMax+1)(i =>
      if(i < nL) timeLimits._1+(deltaT*i*5)
      else timeLimits._2).toArray
      .toSeq.map(s => "t = "+"%3f".format(s)))

    title("Variation of Phase Space Density f(L,t)")
    xAxis("L")
    yAxis("f(L,t)")

  }

  /**
    * Render plots of a sampling experiment performed
    * for loss rate parameters.
    *
    * @param samples Posterior samples.
    * @param gt Ground truth values of the parameters.
    * @param hyper_prior Prior distributions over the hyper-parameters.
    *
    * */
  def visualiseResultsLoss(
    samples: Stream[Map[String, Double]],
    gt: Map[String, Double],
    hyper_prior: Map[String, ContinuousDistr[Double]]): Unit = {

    val (prior_alpha, prior_beta, prior_b) = (hyper_prior("tau_alpha"), hyper_prior("tau_beta"), hyper_prior("tau_b"))

    scatter(samples.map(c => (c("tau_alpha"), c("tau_b"))))
    hold()
    scatter(Seq((gt("tau_alpha"), gt("tau_b"))))
    legend(Seq("Posterior Samples", "Ground Truth"))
    title("Posterior Samples:- "+0x03B1.toChar+" vs b")
    xAxis(0x03C4.toChar+": "+0x03B1.toChar)
    yAxis(0x03C4.toChar+": b")
    unhold()

    scatter(samples.map(c => (c("tau_alpha"), c("tau_beta"))))
    hold()
    scatter(Seq((gt("tau_alpha"), gt("tau_beta"))))
    legend(Seq("Posterior Samples", "Ground Truth"))
    title("Posterior Samples "+0x03B1.toChar+" vs "+0x03B2.toChar)
    xAxis(0x03C4.toChar+": "+0x03B1.toChar)
    yAxis(0x03C4.toChar+": "+0x03B2.toChar)
    unhold()


    histogram((1 to samples.length).map(_ => prior_alpha.draw), 100)
    hold()
    histogram(samples.map(_("tau_alpha")), 100)
    legend(Seq("Prior Samples", "Posterior Samples"))
    title("Histogram: "+0x03B1.toChar)
    unhold()


    histogram((1 to samples.length).map(_ => prior_beta.draw), 100)
    hold()
    histogram(samples.map(_("tau_beta")), 100)
    legend(Seq("Prior Samples", "Posterior Samples"))
    title("Histogram: "+0x03B2.toChar)
    unhold()

    histogram((1 to samples.length).map(_ => prior_b.draw), 100)
    hold()
    histogram(samples.map(_("tau_b")), 100)
    legend(Seq("Prior Samples", "Posterior Samples"))
    title("Histogram: b")
    unhold()

  }

  /**
    * Render plots of a sampling experiment performed
    * for injection rate parameters.
    *
    * @param samples Posterior samples.
    * @param gt Ground truth values of the parameters.
    * @param hyper_prior Prior distributions over the hyper-parameters.
    *
    * */
  def visualiseResultsInjection(
    samples: Stream[Map[String, Double]],
    gt: Map[String, Double],
    hyper_prior: Map[String, ContinuousDistr[Double]]): Unit = {


    scatter(samples.map(c => (c("Q_gamma"), c("Q_b"))))
    hold()
    scatter(Seq((gt("Q_gamma"), gt("Q_b"))))
    legend(Seq("Posterior Samples", "Ground Truth"))
    title("Posterior Samples:- "+0x03B3.toChar+" vs b")
    xAxis("Q: "+0x03B3.toChar)
    yAxis("Q: b")
    unhold()


/*

    scatter(samples.map(c => (c("Q_alpha"), c("Q_beta"))))
    hold()
    scatter(Seq((gt("Q_alpha"), gt("Q_beta"))))
    legend(Seq("Posterior Samples", "Ground Truth"))
    title("Posterior Samples "+0x03B1.toChar+" vs "+0x03B2.toChar)
    xAxis(0x03C4.toChar+": "+0x03B1.toChar)
    yAxis(0x03C4.toChar+": "+0x03B2.toChar)
    unhold()
*/

    val prior_b = hyper_prior("Q_b")
    val prior_gamma = hyper_prior("Q_gamma")

    histogram((1 to samples.length).map(_ => prior_b.draw), 100)
    hold()
    histogram(samples.map(_("Q_b")), 100)
    legend(Seq("Prior Samples", "Posterior Samples"))
    title("Histogram: "+"b")
    unhold()

    histogram((1 to samples.length).map(_ => prior_gamma.draw), 100)
    hold()
    histogram(samples.map(_("Q_gamma")), 100)
    legend(Seq("Prior Samples", "Posterior Samples"))
    title("Histogram: "+0x03B3.toChar)
    unhold()


  }

  /**
    * Write the experiment configuration and results to
    * disk.
    *
    * @param solution PSD profile.
    * @param boundary_data Boundary observations.
    * @param bulk_data Bulk observations.
    * @param colocation_points Co-location points in the domain.
    * @param hyper_prior Prior defined over model hyper-parameters.
    * @param samples Posterior samples.
    * @param basisSize The number of space and time nodes in the PSD
    *                  basis expansion used by [[BasisFuncRadialDiffusionModel]].
    * @param basisType The type of basis expansion.
    * @param reg The regularisation parameters used by [[BasisFuncRadialDiffusionModel]]
    *
    * @return The path where results are written.
    * */
  def writeResults(
    solution: Stream[DenseVector[Double]],
    boundary_data: Stream[((Double, Double), Double)],
    bulk_data: Stream[((Double, Double), Double)],
    colocation_points: Stream[(Double, Double)],
    hyper_prior: Map[String, ContinuousDistr[Double]],
    samples: Stream[Map[String, Double]],
    basisSize: (Int, Int), basisType: String,
    reg: (Double, Double)): Path = {

    val dateTime = new DateTime()

    val dtString = dateTime.toString(DateTimeFormat.forPattern("yyyy_MM_dd_H_mm"))

    val resultsPath = pwd/".cache"/("radial-diffusion-exp_"+dtString)

    logger.info("Writing results of radial diffusion experiment in directory: "+resultsPath.toString())

    logger.info("Writing domain information in "+"diffusion_domain.csv")

    write(resultsPath/"diffusion_domain.csv", domainSpec.keys.mkString(",")+"\n"+domainSpec.values.mkString(","))

    val (lShellVec, timeVec) = RadialDiffusion.buildStencil(lShellLimits, nL, timeLimits, nT)

    val initial_condition = lShellVec.map(l => Seq(l, initialPSD(l)))
    val kp = timeVec.map(t => Seq(t, Kp(t)))

    logger.info("Writing initial PSD in "+"initial_psd.csv")
    write(resultsPath/"initial_psd.csv", initial_condition.map(_.mkString(",")).mkString("\n"))

    logger.info("Writing Kp profile in "+"kp_profile.csv")
    write(resultsPath/"kp_profile.csv", kp.map(_.mkString(",")).mkString("\n"))

    logger.info("Writing Diffusion parameters in "+"diffusion_params.csv")
    write(resultsPath/"diffusion_params.csv", gt.keys.mkString(",")+"\n"+gt.values.mkString(",")+"\n")

    logger.info("Writing discretised solution produced by solver in "+"diffusion_solution.csv")
    write(resultsPath/"diffusion_solution.csv", solution.map(_.toArray.mkString(",")).mkString("\n"))

    val (m, v) = (measurement_noise.mu, measurement_noise.sigma)
    write(
      resultsPath/"measurement_noise.csv",
      "mean,sigma\n"+m+","+v+"\n"
    )

    logger.info("Writing observed boundary data in "+"boundary_data.csv")
    write(
      resultsPath/"boundary_data.csv",
      boundary_data.map(t => List(t._1._1, t._1._2, t._2).mkString(",")).mkString("\n"))

    logger.info("Writing observed bulk data in "+"bulk_data.csv")
    write(
      resultsPath/"bulk_data.csv",
      bulk_data.map(t => List(t._1._1, t._1._2, t._2).mkString(",")).mkString("\n"))

    logger.info("Writing coordinates of colocation points in "+"colocation_points.csv")
    write(
      resultsPath/"colocation_points.csv",
      colocation_points.map(t => List(t._1, t._2).mkString(",")).mkString("\n"))


    val prior_samples = (1 to samples.length).map(_ => hyper_prior.mapValues(_.draw()))

    val prior_samples_file_content =
      prior_samples.head.keys.mkString(",") + "\n" + prior_samples.map(_.values.mkString(",")).mkString("\n")

    logger.info("Writing model info")

    val modelInfo = Map(
      "type" -> basisType, "nL" -> basisSize._1.toString, "nT" -> basisSize._2.toString,
      "regCol" -> reg._1.toString, "regData" -> reg._2.toString)

    write(resultsPath/"model_info.csv", modelInfo.keys.mkString(",")+"\n"+modelInfo.values.mkString(","))

    logger.info("Writing samples generated from prior distribution in "+"prior_samples.csv")
    write(resultsPath/"prior_samples.csv", prior_samples_file_content)

    val samples_file_content =
      samples.head.keys.mkString(",") + "\n" + samples.map(_.values.mkString(",")).mkString("\n")

    logger.info("Writing samples generated from posterior distribution in "+"posterior_samples.csv")
    write(resultsPath/"posterior_samples.csv", samples_file_content)

    logger.info("Done writing results for experiment")
    resultsPath
  }

  val readFile         = DataPipe((p: Path) => (read.lines! p).toStream)

  val processLines     = StreamDataPipe((s: String) => s.split(',').map(_.toDouble))

  val readObservations = readFile > processLines > StreamDataPipe((a: Array[Double]) => ((a.head, a(1)), a.last))

  val readColocation   = readFile > processLines > StreamDataPipe((a: Array[Double]) => (a.head, a(1)))

  def readAsMap(h: Seq[String]) =
    readFile > dropHead > processLines > StreamDataPipe((s: Array[Double]) => h.zip(s).toMap)

  def loadCachedResults(resultsPath: Path) = {

    logger.info("Reading results of radial diffusion experiment in directory: "+resultsPath.toString())

    val hyp               = (read.lines! resultsPath/"posterior_samples.csv").head.split(',').toSeq

    val readSamples       = readAsMap(hyp)

    val boundary_data     = readObservations(resultsPath/"boundary_data.csv")
    val bulk_data         = readObservations(resultsPath/"bulk_data.csv")
    val colocation_points = readColocation(resultsPath/"colocation_points.csv")

    val samples           = readSamples(resultsPath/"posterior_samples.csv")


    val model_params = (read.lines! resultsPath/"model_info.csv").head.split(',').toSeq
    val model_info   = (readFile > dropHead > StreamDataPipe((s: String) => s.split(',')))(resultsPath/"model_info.csv")

    val m_info       = model_params.zip(model_info.head).toMap

    regColocation    = m_info("regCol").toDouble
    regData          = m_info("regData").toDouble

    val basisInfo = (m_info("type"), (m_info("nL").toInt, m_info("nT").toInt))

    val solution  = (readFile > processLines > StreamDataPipe((v: Array[Double]) => DenseVector(v)))(
      resultsPath/"diffusion_solution.csv"
    )

    val domainInfo  = readAsMap(
      (read.lines! resultsPath/"diffusion_domain.csv").head.split(',').toSeq)(
      resultsPath/"diffusion_domain.csv").head

    val noiseInfo   = readAsMap(
      (read.lines! resultsPath/"measurement_noise.csv").head.split(',').toSeq)(
      resultsPath/"measurement_noise.csv").head

    val groundTruth = readAsMap(
      (read.lines! resultsPath/"diffusion_params.csv").head.split(',').toSeq)(
      resultsPath/"diffusion_params.csv").head

    dll_params    = (groundTruth("dll_alpha"), groundTruth("dll_beta"), groundTruth("dll_gamma"), groundTruth("dll_b"))

    q_params      = (groundTruth("Q_alpha"), groundTruth("Q_beta"), groundTruth("Q_gamma"), groundTruth("Q_b"))

    lambda_params = (groundTruth("tau_alpha"), groundTruth("tau_beta"), groundTruth("tau_gamma"), groundTruth("tau_b"))

    nL = domainInfo("nL").toInt

    nT = domainInfo("nT").toInt

    lShellLimits  = (domainInfo("lMin"), domainInfo("lMax"))
    timeLimits    = (domainInfo("tMin"), domainInfo("tMax"))

    measurement_noise = GaussianRV(noiseInfo("mean"), noiseInfo("sigma"))

    (solution, (boundary_data, bulk_data), colocation_points, samples, basisInfo)

  }

}

object RDSettings {

  /*Define the data generation primitives*/
  var (nL,nT) = (200, 50)

  var lShellLimits: (Double, Double) = (1.0, 7.0)
  var timeLimits: (Double, Double) = (0.0, 5.0)

  def domainSpec: Map[String, Double] = Map(
    "nL" -> nL.toDouble,
    "nT" -> nT.toDouble,
    "lMin" -> lShellLimits._1,
    "lMax" -> lShellLimits._2,
    "tMin" -> timeLimits._1,
    "tMax" -> timeLimits._2
  )

  def deltaL: Double = (lShellLimits._2 - lShellLimits._1)/nL

  def deltaT: Double = (timeLimits._2 - timeLimits._1)/nT

  var dll_params: (Double, Double, Double, Double) = (
    math.log(math.exp(0d)*math.pow(10d, -9.325)),
    10d, 0d, 0.506)

  var lambda_params: (Double, Double, Double, Double) = (
    math.log(math.pow(10d, -4)*math.pow(10d, 2.5d)/2.4),
    1d, 0d, 0.18)

  var q_params: (Double, Double, Double, Double) = (Double.NegativeInfinity, 0d, 0d, 0d)

  val quantities_loss = Map(
    "tau_alpha" -> 0x03B1.toChar,
    "tau_beta" -> 0x03B2.toChar,
    "tau_b" -> 'b')

  val quantities_injection = Map(
    "Q_alpha" -> 0x03B1.toChar,
    "Q_beta" -> 0x03B2.toChar,
    "Q_gamma" -> 0x03B3.toChar,
    "Q_b" -> 'b'
  )

  val diffusion_quantities = Map(
    "loss" -> 0x03BB.toChar,
    "injection" -> 'Q',
    "diffusion" -> 0x03BA.toChar
  )

  def gt = Map(
    "dll_alpha" -> dll_params._1,
    "dll_beta" -> dll_params._2,
    "dll_gamma" -> dll_params._3,
    "dll_b" -> dll_params._4,
    "tau_alpha" -> lambda_params._1,
    "tau_beta" -> lambda_params._2,
    "tau_gamma" -> lambda_params._3,
    "tau_b" -> lambda_params._4,
    "Q_alpha" -> q_params._1,
    "Q_beta" -> q_params._2,
    "Q_gamma" -> q_params._3,
    "Q_b" -> q_params._4)

  var Kp: DataPipe[Double, Double] = DataPipe((t: Double) => {
    if(t<= 0d) 2.5
    else if(t < 1.5) 2.5 + 4*t
    else if (t >= 1.5 && t< 3d) 8.5
    else if(t >= 3d && t<= 5d) 17.5 - 3*t
    else 2.5
  })

  def dll: (Double, Double) => Double = (l: Double, t: Double) =>
    (math.exp(dll_params._1)*math.pow(l, dll_params._2) + dll_params._3)*math.pow(10, dll_params._4*Kp(t))

  def Q: (Double, Double) => Double = (l: Double, t: Double) =>
    (math.exp(q_params._1)*math.pow(l, q_params._2) + q_params._3)*math.pow(10, q_params._4*Kp(t))

  def lambda: (Double, Double) => Double = (l: Double, t: Double) =>
    (math.exp(lambda_params._1)*math.pow(l, lambda_params._2) + lambda_params._3)*math.pow(10, lambda_params._4*Kp(t))

  protected def omega: Double = 2*math.Pi/(lShellLimits._2 - lShellLimits._1)

  var initialPSD: (Double) => Double = (l: Double) => Bessel.i1(omega*(l - lShellLimits._1))*1E2 + 100

  var measurement_noise = GaussianRV(0.0, 0.5)

  var num_boundary_data = 50
  var num_bulk_data     = 100
  var num_dummy_data    = 200

  var regData           = 0.01

  var regColocation     = 1E-8

  var lMax              = 10
  var tMax              = 10

}
