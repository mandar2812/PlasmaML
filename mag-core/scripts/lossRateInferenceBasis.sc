{
  import breeze.stats.distributions._
  import spire.implicits._

  import io.github.mandar2812.dynaml.analysis.implicits._
  import io.github.mandar2812.dynaml.kernels._
  import io.github.mandar2812.dynaml.pipes._
  import io.github.mandar2812.dynaml.probability.mcmc._

  import io.github.mandar2812.PlasmaML.dynamics.diffusion._
  import io.github.mandar2812.PlasmaML.utils.DiracTuple2Kernel

  import io.github.mandar2812.PlasmaML.dynamics.diffusion.BasisFuncRadialDiffusionModel
  import io.github.mandar2812.PlasmaML.dynamics.diffusion.RDSettings._

  num_bulk_data = 50

  num_dummy_data = 200

  lambda_params = (
    math.log(math.pow(10d, -4)*math.pow(10d, 2.5d)/2.4),
    1.75d, 0d, 0.18)

  val rds = RDExperiment.solver(lShellLimits, timeLimits, nL, nT)


  val hybrid_basis = new HybridMQPSDBasis(1d)(
    lShellLimits, 50, timeLimits, 30, (false, false)
  )

  val burn = 1500

  val seKernel = new GenExpSpaceTimeKernel[Double](
    10d, deltaL, deltaT)(
    sqNormDouble, l1NormDouble)

  val noiseKernel = new DiracTuple2Kernel(1.5)

  noiseKernel.block_all_hyper_parameters

  val (solution, data, colocation_points) = RDExperiment.generateData(
    rds, dll, lambda, Q, initialPSD)(
    measurement_noise, num_boundary_data,
    num_bulk_data, num_dummy_data)

  val model = new BasisFuncRadialDiffusionModel(
    Kp, dll_params, (0d, 0.2, 0d, 0.0), q_params)(
    seKernel, noiseKernel,
    data, colocation_points,
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
  val hyper_prior = RDExperiment.hyper_prior(hyp)

  model.regCol = regColocation
  model.regObs = regData

  //Create the MCMC sampler
  val mcmc_sampler = new AdaptiveHyperParameterMCMC[
    model.type, ContinuousDistr[Double]](
    model, hyper_prior, burn)

  val num_post_samples = 2000

  //Draw samples from the posterior
  val samples = mcmc_sampler.iid(num_post_samples).draw

  RDExperiment.samplingReport(samples, quantities_loss, gt, mcmc_sampler.sampleAcceptenceRate)

  RDExperiment.visualisePSD(lShellLimits, timeLimits, nL, nT)(initialPSD, solution, Kp)

  RDExperiment.visualiseResultsLoss(samples, gt, hyper_prior)
}
