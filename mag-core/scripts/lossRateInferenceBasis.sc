{
  import breeze.stats.distributions._
  import io.github.mandar2812.dynaml.kernels._
  import io.github.mandar2812.dynaml.probability.mcmc._
  import ammonite.ops._
  import ammonite.ops.ImplicitWd._

  import io.github.mandar2812.PlasmaML.dynamics.diffusion._
  import io.github.mandar2812.PlasmaML.utils.DiracTuple2Kernel

  import io.github.mandar2812.PlasmaML.dynamics.diffusion.BasisFuncRadialDiffusionModel
  import io.github.mandar2812.PlasmaML.dynamics.diffusion.RDSettings._

  num_bulk_data = 50
  num_boundary_data = 20

  num_dummy_data = 50

  lambda_params = (
    math.log(math.pow(10d, -4)*math.pow(10d, 2.5d)/2.4),
    2.0, 0d, 0.18)


  nL = 300
  nT = 200

  q_params = (0d, 0.5d, 0.05, 0.45)

  val rds = RDExperiment.solver(lShellLimits, timeLimits, nL, nT)


  val basisSize = (79, 49)
  val hybrid_basis = new HybridMQPSDBasis(0.75d)(
    lShellLimits, basisSize._1, timeLimits, basisSize._2, (false, false)
  )


  val burn = 1500

  val seKernel = new GenExpSpaceTimeKernel[Double](
    10d, deltaL, deltaT)(
    sqNormDouble, l1NormDouble)

  val noiseKernel = new DiracTuple2Kernel(1.5)

  noiseKernel.block_all_hyper_parameters

  val (solution, (boundary_data, bulk_data), colocation_points) = RDExperiment.generateData(
    rds, dll, lambda, Q, initialPSD)(
    measurement_noise, num_boundary_data,
    num_bulk_data, num_dummy_data)

  val model = new BasisFuncRadialDiffusionModel(
    Kp, dll_params, (0d, 0.2, 0d, 0.0), q_params)(
    seKernel, noiseKernel,
    boundary_data ++ bulk_data, colocation_points,
    hybrid_basis
  )

  val blocked_hyp = {
    model.blocked_hyper_parameters ++
      model.hyper_parameters.filter(c =>
        c.contains("dll") ||
        c.contains("base::") ||
        c.contains("tau_gamma") ||
        c.contains("Q_")
      )
  }


  model.block(blocked_hyp:_*)

  val hyp = model.effective_hyper_parameters
  val hyper_prior = {
    hyp.filter(_.contains("base::")).map(h => (h, new LogNormal(0d, 2d))).toMap ++
      hyp.filterNot(h => h.contains("base::") || h.contains("tau")).map(h => (h, new Gaussian(0d, 2.5d))).toMap ++
      Map(
        "tau_alpha" -> new Gaussian(0d, 1d),
        "tau_beta" -> new Gamma(1d, 1d),
        "tau_b" -> new Gaussian(0d, 2.0)).filterKeys(hyp.contains)
  }

  model.regCol = regColocation
  model.regObs = 1E-3

  //Create the MCMC sampler
  val mcmc_sampler = new AdaptiveHyperParameterMCMC[
    model.type, ContinuousDistr[Double]](
    model, hyper_prior, burn)

  val num_post_samples = 1000

  //Draw samples from the posterior
  val samples = mcmc_sampler.iid(num_post_samples).draw

  val resPath = RDExperiment.writeResults(
    solution, boundary_data, bulk_data, colocation_points,
    hyper_prior, samples, basisSize, "HybridMQ",
    (model.regCol, model.regObs))

  val scriptPath = pwd / "mag-core" / 'scripts / "visualiseSamplingResults.R"

  %%('Rscript, scriptPath.toString, resPath.toString, "loss")


  RDExperiment.samplingReport(
    samples, hyp.map(c => (c, quantities_loss(c))).toMap,
    gt, mcmc_sampler.sampleAcceptenceRate)

  RDExperiment.visualisePSD(lShellLimits, timeLimits, nL, nT)(initialPSD, solution, Kp)

  RDExperiment.visualiseResultsLoss(samples, gt, hyper_prior)
}
