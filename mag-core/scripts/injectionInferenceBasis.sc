{
  import breeze.stats.distributions._
  import io.github.mandar2812.dynaml.kernels._
  import io.github.mandar2812.dynaml.probability.mcmc._

  import io.github.mandar2812.PlasmaML.dynamics.diffusion._
  import io.github.mandar2812.PlasmaML.utils.DiracTuple2Kernel

  import io.github.mandar2812.PlasmaML.dynamics.diffusion.BasisFuncRadialDiffusionModel
  import io.github.mandar2812.PlasmaML.dynamics.diffusion.RDSettings._

  num_bulk_data = 50
  num_dummy_data = 50

  q_params = (0d, 0d, 1.5d, 0.35)

  val rds = RDExperiment.solver(lShellLimits, timeLimits, nL, nT)

  val hybrid_basis = new HybridMQPSDBasis(1d)(
    lShellLimits, 50, timeLimits, 30, (false, false)
  )

  val burn = 1500

  val (solution, data, colocation_points) = RDExperiment.generateData(
    rds, dll, lambda, Q, initialPSD)(
    measurement_noise, num_boundary_data,
    num_bulk_data, num_dummy_data)


  val gpKernel = new GenExpSpaceTimeKernel[Double](
    10d, deltaL, deltaT)(
    sqNormDouble, l1NormDouble)

  val noiseKernel = new DiracTuple2Kernel(0.5)

  noiseKernel.block_all_hyper_parameters

  val model = new BasisFuncRadialDiffusionModel(
    Kp, dll_params, lambda_params,
    (0d, 0d, 0.01, 0.01))(
    gpKernel, noiseKernel,
    data, colocation_points,
    hybrid_basis
  )

  val blocked_hyp = {
    model.blocked_hyper_parameters ++
      model.hyper_parameters.filter(c =>
        c.contains("dll") ||
          c.contains("base::") ||
          c.contains("tau_")
      )
  }

  model.block(blocked_hyp:_*)
  //Create the MCMC sampler
  val hyp = model.effective_hyper_parameters

  val hyper_prior = {
    hyp.filter(_.contains("base::")).map(h => (h, new LogNormal(0d, 2d))).toMap ++
      hyp.filterNot(h => h.contains("base::") || h.contains("tau")).map(h => (h, new Gaussian(0d, 2.5d))).toMap ++
      Map(
        "Q_alpha" -> new LogNormal(-1d, 1d),
        "Q_beta" -> new Gaussian(0d, 1d),
        "Q_gamma" -> new LogNormal(0d, 2d),
        "Q_b" -> new Gaussian(0d, 2d))
  }

  model.regCol = regColocation
  model.regObs = regData

  val mcmc_sampler = new AdaptiveHyperParameterMCMC[
    model.type, ContinuousDistr[Double]](
    model, hyper_prior, burn)

  val num_post_samples = 2000

  //Draw samples from the posterior
  val samples = mcmc_sampler.iid(num_post_samples).draw

  RDExperiment.samplingReport(samples, quantities_loss, gt, mcmc_sampler.sampleAcceptenceRate)

  RDExperiment.visualisePSD(lShellLimits, timeLimits, nL, nT)(initialPSD, solution, Kp)

  RDExperiment.visualiseResultsInjection(samples, gt, hyper_prior)
}
