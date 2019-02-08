import _root_.io.github.mandar2812.dynaml.tensorflow._
import _root_.io.github.mandar2812.dynaml.pipes._
import _root_.io.github.mandar2812.dynaml.repl.Router.main
import _root_.io.github.mandar2812.dynaml.probability._
import org.platanios.tensorflow.api.ops.training.optimizers.Optimizer
import _root_.io.github.mandar2812.PlasmaML.helios
import _root_.io.github.mandar2812.dynaml.tensorflow.layers.{L2Regularization, L1Regularization}
import _root_.io.github.mandar2812.PlasmaML.helios.core.timelag
import _root_.ammonite.ops._
import breeze.numerics.sigmoid
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.learn.layers.Activation

@main
def apply(
  compute_output: DataPipe[Tensor, (Float, Float)],
  d: Int                                                = 10,
  confounding: Seq[Double]                              = Seq(0d, 0.25, 0.5, 0.75),
  size_training: Int                                    = 1000,
  size_test: Int                                        = 500,
  sliding_window: Int                                   = 15,
  noise: Double                                         = 0.5,
  noiserot: Double                                      = 0.1,
  alpha: Double                                         = 0.0,
  train_test_separate: Boolean                          = false,
  num_neurons: Seq[Int]                                 = Seq(40),
  activation_func: Int => Activation                    = timelag.utils.getReLUAct(1),
  iterations: Int                                       = 150000,
  iterations_tuning: Int                                = 20000,
  miniBatch: Int                                        = 32,
  optimizer: Optimizer                                  = tf.train.AdaDelta(0.01),
  sum_dir_prefix: String                                = "cdt",
  prior_type: helios.learn.cdt_loss.Divergence          = helios.learn.cdt_loss.KullbackLeibler,
  target_prob: helios.learn.cdt_loss.TargetDistribution = helios.learn.cdt_loss.Boltzmann,
  dist_type: String                                     = "default",
  timelag_pred_strategy: String                         = "mode",
  summaries_top_dir: Path                               = home/'tmp,
  num_samples: Int                                      = 20,
  hyper_optimizer: String                               = "gs",
  hyp_opt_iterations: Option[Int]                       = Some(5),
  epochFlag: Boolean                                    = false,
  regularization_type: String                           = "L2")
: Seq[timelag.ExperimentResult[timelag.TunedModelRun]] = {

  val mo_flag = true
  val prob_timelags = true

  val num_pred_dims = timelag.utils.get_num_output_dims(sliding_window, mo_flag, prob_timelags, dist_type)

  val (net_layer_sizes, layer_shapes, layer_parameter_names, layer_datatypes) =
    timelag.utils.get_ffnet_properties(d, num_pred_dims, num_neurons, "FLOAT32")

  val output_mapping = timelag.utils.get_output_mapping(
    sliding_window, mo_flag,
    prob_timelags, dist_type)

  //Prediction architecture
  val architecture = dtflearn.feedforward_stack(
    activation_func, FLOAT32)(
    net_layer_sizes.tail) >>
    output_mapping

  val hyper_parameters = List(
    "prior_wt",
    "error_wt",
    "temperature",
    "specificity",
    "reg"
  )

  val hyper_prior = Map(
    "prior_wt"    -> UniformRV(0.5, 1.5),
    "error_wt"    -> UniformRV(0.75, 1.5),
    "temperature" -> UniformRV(0.75, 2.0),
    "specificity" -> UniformRV(0.5, 2.5),
    "reg"         -> UniformRV(math.pow(10d, -5d), math.pow(10d, -3d))
  )

  val hyp_scaling = hyper_prior.map(p =>
    (
      p._1,
      Encoder((x: Double) => (x - p._2.min)/(p._2.max - p._2.min), (u: Double) => u*(p._2.max - p._2.min) + p._2.min)
    )
  )

  val logit = Encoder((x: Double) => math.log(x/(1d - x)), (x: Double) => sigmoid(x))

  val hyp_mapping = Some(
    hyper_parameters.map(
      h => (h, hyp_scaling(h) > logit)
    ).toMap
  )

  val loss_func_generator = (h: Map[String, Double]) => {

    val lossFunc = timelag.utils.get_loss(
      sliding_window, mo_flag,
      prob_timelags,
      prior_wt = h("prior_wt"),
      prior_divergence = prior_type,
      target_dist = target_prob,
      temp = h("temperature"),
      error_wt = h("error_wt"),
      c = h("specificity"))

    val reg_layer =
      if(regularization_type == "L1") L1Regularization(layer_parameter_names, layer_datatypes, layer_shapes, h("reg"))
      else L2Regularization(layer_parameter_names, layer_datatypes, layer_shapes, h("reg"))

    lossFunc >>
      reg_layer >>
      tf.learn.ScalarSummary("Loss", "ModelLoss")
  }

  val fitness_function = DataPipe2[(Tensor, Tensor), Tensor, Double]((preds, targets) => {

    preds._1
      .subtract(targets)
      .square
      .multiply(preds._2)
      .sum(axes = 1)
      .mean()
      .scalar
      .asInstanceOf[Double]
  })

  val dataset: timelag.utils.TLDATA =
    timelag.utils.generate_data(
      compute_output, sliding_window,
      d, size_training, noiserot,
      alpha, noise)

  val dataset_test: timelag.utils.TLDATA =
    timelag.utils.generate_data(
      compute_output, sliding_window,
      d, size_test, noiserot, alpha,
      noise)

  confounding.map(c =>
    timelag.run_exp_hyp(
      (dataset, dataset_test),
      architecture, hyper_parameters,
      loss_func_generator,
      fitness_function, hyper_prior,
      iterations, iterations_tuning, optimizer,
      miniBatch, sum_dir_prefix,
      mo_flag, prob_timelags,
      timelag_pred_strategy,
      summaries_top_dir, num_samples,
      hyper_optimizer,
      hyp_opt_iterations = hyp_opt_iterations,
      epochFlag = epochFlag,
      hyp_mapping = hyp_mapping,
      confounding_factor = c)
  )

}