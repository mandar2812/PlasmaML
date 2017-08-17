{
  import scala.util.Random
  import breeze.linalg._
  import breeze.numerics.Bessel
  import breeze.stats.distributions._
  import spire.implicits._
  import com.quantifind.charts.Highcharts._

  import io.github.mandar2812.dynaml.utils._
  import io.github.mandar2812.dynaml.analysis._
  import io.github.mandar2812.dynaml.analysis.implicits._
  import io.github.mandar2812.dynaml.optimization.RegularizedLSSolver
  import io.github.mandar2812.dynaml.DynaMLPipe._
  import io.github.mandar2812.dynaml.kernels._
  import io.github.mandar2812.dynaml.pipes.DataPipe
  import io.github.mandar2812.dynaml.probability._
  import io.github.mandar2812.dynaml.models.gp._
  import io.github.mandar2812.dynaml.probability.mcmc._
  
  import io.github.mandar2812.PlasmaML.dynamics.diffusion._
  import io.github.mandar2812.PlasmaML.utils._

  val (nL,nT) = (200, 50)

  val lShellLimits = (1.0, 7.0)
  val timeLimits = (0.0, 5.0)

  val rds = new RadialDiffusion(lShellLimits, timeLimits, nL, nT)

  val (lShellVec, timeVec) = rds.stencil

  val Kp = DataPipe((t: Double) => {
    if(t<= 0d) 2.5
    else if(t < 1.5) 2.5 + 4*t
    else if (t >= 1.5 && t< 3d) 8.5
    else if(t >= 3d && t<= 5d) 17.5 - 3*t
    else 2.5
  })

  val rowSelectorRV = MultinomialRV(DenseVector.fill[Double](lShellVec.length)(1d/lShellVec.length.toDouble))
  val colSelectorRV = MultinomialRV(DenseVector.fill[Double](timeVec.length)(1d/timeVec.length.toDouble))

  val baseNoiseLevel = 1.2
  val mult = 0.8

  /*
   * Define parameters of radial diffusion system:
   *
   *  1) The diffusion field: dll
   *  2) The particle injection process: q
   *  3) The loss parameter: lambda
   *
   * Using the MagnetosphericProcessTrend class,
   * we define each unknown process using a canonical
   * parameterization of diffusion processes in the
   * magnetosphere.
   *
   * For each process we must specify 4 parameters
   * alpha, beta, a, b
   * */

  //Diffusion Field
  val dll_alpha = 1d
  val dll_beta = 10d
  val dll_gamma = 0d
  val dll_a = -9.325
  val dll_b = 0.506

  //Injection process
  val q_alpha = 0d
  val q_beta = 0d
  val q_a = 0.0d
  val q_b = 0.0d

  //Loss Process
  val lambda_alpha = 2.0//math.pow(10d, -4)/2.4
  val lambda_beta = 4d
  val lambda_a = -0.5
  val lambda_b = 0.18

  //Create ground truth diffusion parameter functions
  val dll = (l: Double, t: Double) => dll_alpha*math.pow(l, dll_beta)*math.pow(10, dll_a + dll_b*Kp(t))

  val Q = (_: Double, _: Double) => 0d

  val lambda = (l: Double, t: Double) => lambda_alpha*math.pow(l, lambda_beta)*math.pow(10, lambda_a + lambda_b*Kp(t))

  val omega = 2*math.Pi/(lShellLimits._2 - lShellLimits._1)

  val omega_t = math.Pi*2d/rds.deltaT

  val initialPSD = (l: Double) => Bessel.i1(omega*(l - lShellLimits._1))*1E1

  val initialPSDGT: DenseVector[Double] = DenseVector(lShellVec.map(l => initialPSD(l)).toArray)

  //Create ground truth PSD data and corrupt it with statistical noise.
  val groundTruth = rds.solve(Q, dll, lambda)(initialPSD)
  val ground_truth_matrix = DenseMatrix.horzcat(groundTruth.map(_.asDenseMatrix.t):_*)
  val measurement_noise = GaussianRV(0.0, 0.1)

  val noise_mat = DenseMatrix.tabulate[Double](nL+1, nT+1)((_, _) => measurement_noise.draw)
  val data: DenseMatrix[Double] = ground_truth_matrix + noise_mat
  val num_data = 10
  val gp_data: Seq[((Double, Double), Double)] = {
    (0 until num_data).map(_ => {
      val rowS = rowSelectorRV.draw
      val colS = colSelectorRV.draw
      val (l, t) = (lShellVec(rowS), timeVec(colS))
      ((l,t), data(rowS, colS))
    })
  }


}
{
  val burn = 50000
  //Create the GP PDE model
  val gpKernel = new SE1dExtRadDiffusionKernel(
    1.0, 0.1*rds.deltaL, 0.1*rds.deltaT, Kp)(
    (dll_alpha*math.pow(10d, dll_a), dll_beta, dll_gamma, dll_b),
      (lambda_alpha*math.pow(10d, 0.1), 0.2, 0d, 0d), "L2", "L1"
  )

  val noiseKernel = new MAKernel(0.01)

  noiseKernel.block_all_hyper_parameters

  val blocked_hyp = {
    gpKernel.hyper_parameters.filter(h => h.contains("dll_") || h.contains("_gamma") || h.contains("base::"))
  }

  gpKernel.block(blocked_hyp:_*)


  implicit val dataT = identityPipe[Seq[((Double, Double), Double)]]

  val psdMean = gp_data.map(_._2).sum/gp_data.length

  val p = (dll_beta - 2d)/2d
  val q = (dll_beta - 3d)/2d
  val nu = math.abs((dll_beta - 3d)/(dll_beta - 2d))
  val beta = 2d/((dll_beta - 2d)*math.sqrt(dll_alpha))

  val psd_trend = (l: Double, t: Double) => initialPSD(l)//*math.exp(-beta*t)


  //Define some basis function expansions
  val num_components = 4
  val fourier_series_map: DataPipe[Double, DenseVector[Double]] = FourierSeriesGenerator(omega, num_components)
  val chebyshev_series_map: DataPipe[Double, DenseVector[Double]] = ChebyshevSeriesGenerator(num_components, 2)
  val spline_series_map = CubicSplineGenerator(0 until num_components)

  val rbf_centers = gp_data.map(_._1._1)
  val rbf_scales = Seq.fill[Double](gp_data.length)(rds.deltaL)
  val rbf_basis = RBFGenerator.invMultiquadricBasis[Double](rbf_centers, rbf_scales)

  val basis = rbf_basis


  //Calculate Regularized Least Squares solution to basis function OLS problem
  //and use that as parameter mean.

  val designMatrix = DenseMatrix.vertcat[Double](
    gp_data.map(p => basis(p._1._1).toDenseMatrix):_*
  )

  val responseVector = DenseVector(gp_data.map(_._2).toArray)

  val s = new RegularizedLSSolver
  s.setRegParam(0.001)

  val b = s.optimize(
    num_data,
    (designMatrix.t*designMatrix, designMatrix.t*responseVector),
    DenseVector.zeros[Double](designMatrix.cols)
  )


  val basis_prior = MultGaussianRV(
    //DenseVector.tabulate[Double](num_components+1)(i => if(i == 0) psdMean else 1d/(gamma + math.pow(i*omega, 2d))),
    b, DenseMatrix.eye[Double](designMatrix.cols)
  )(VectorField(designMatrix.cols))


  val model = AbstractGPRegressionModel[Seq[((Double, Double), Double)], (Double, Double)](
    gpKernel, noiseKernel:*noiseKernel,
    DataPipe((x: (Double, Double)) => basis(x._1)),
    basis_prior)(gp_data, gp_data.length)


  //Create the MCMC sampler
  val hyp = gpKernel.effective_hyper_parameters ++ noiseKernel.effective_hyper_parameters

  val num_hyp = hyp.length

  val hyper_prior = {
    hyp.filter(_.contains("base::")).map(h => (h, new LogNormal(0d, 2d))).toMap ++
    hyp.filterNot(h => h.contains("base::") || h.contains("tau")).map(h => (h, new Gaussian(0d, 2.5d))).toMap ++
    Map(
      "tau_alpha" -> new LogNormal(0d, 3d),
      "tau_beta" -> new LogNormal(0d, 3d),
      "tau_b" -> new Gaussian(0d, 1.0))
  }

  val mcmc = new AdaptiveHyperParameterMCMC[model.type, ContinuousDistr[Double]](model, hyper_prior, burn)

  //Draw samples from the posterior
  val samples = mcmc.iid(4000).draw
}

{
  val post_vecs = samples.map(c => DenseVector(c("tau_alpha"), c("tau_beta"), c("tau_b")))
  val post_moments = getStats(post_vecs.toList)

  val quantities = Map("alpha" -> 0x03B1.toChar, "beta" -> 0x03B2.toChar, "b" -> 'b')
  val gt = Map("alpha" -> lambda_alpha*math.pow(10d, lambda_a), "beta" -> lambda_beta, "b" -> lambda_b)

  println("\n:::::: MCMC Sampling Report ::::::\n")

  println("Quantity: "+0x03C4.toChar+"(l,t) = "+0x03B1.toChar+"l^("+0x03B2.toChar+")*10^(b*K(t))")


  quantities.zipWithIndex.foreach(c => {
    val ((key, char), index) = c
    println("\n------------------------------")
    println("Parameter: "+char)
    println("Ground Truth:- "+gt(key))
    println("Posterior Moments: mean = "+post_moments._1(index)+" variance = "+post_moments._2(index))
  })

}


{

  scatter(samples.map(c => (c("tau_alpha"), c("tau_b"))))
  hold()
  scatter(Seq((lambda_alpha*math.pow(10d, lambda_a), lambda_b)))
  legend(Seq("Posterior Samples", "Ground Truth"))
  title("Posterior Samples:- "+0x03B1.toChar+" vs b")
  xAxis(0x03C4.toChar+": "+0x03B1.toChar)
  yAxis(0x03C4.toChar+": b")
  unhold()



  scatter(samples.map(c => (c("tau_alpha"), c("tau_beta"))))
  hold()
  scatter(Seq((lambda_alpha, lambda_beta)))
  legend(Seq("Posterior Samples", "Ground Truth"))
  title("Posterior Samples "+0x03B1.toChar+" vs "+0x03B2.toChar)
  xAxis(0x03C4.toChar+": "+0x03B1.toChar)
  yAxis(0x03C4.toChar+": "+0x03B2.toChar)
  unhold()
}
