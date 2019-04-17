import _root_.io.github.mandar2812.dynaml.tensorflow._
import _root_.io.github.mandar2812.dynaml.pipes._
import _root_.io.github.mandar2812.dynaml.repl.Router.main
import _root_.io.github.mandar2812.dynaml.probability._
import org.platanios.tensorflow.api.ops.training.optimizers.Optimizer
import _root_.io.github.mandar2812.dynaml.tensorflow.layers.{
  L2Regularization,
  L1Regularization
}
import _root_.io.github.mandar2812.PlasmaML.helios
import _root_.io.github.mandar2812.PlasmaML.helios.core.timelag
import _root_.ammonite.ops._
import breeze.numerics.sigmoid
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.learn.layers.Activation

@main
def apply(
  compute_output: DataPipe[Tensor[Double], (Float, Float)],
  d: Int = 10,
  confounding: Seq[Double] = Seq(0d, 0.25, 0.5, 0.75),
  size_training: Int = 1000,
  size_test: Int = 500,
  sliding_window: Int = 15,
  noise: Double = 0.5,
  noiserot: Double = 0.1,
  alpha: Double = 0.0,
  train_test_separate: Boolean = false,
  num_neurons: Seq[Int] = Seq(40),
  activation_func: Int => Activation[Double] = (i: Int) =>
    timelag.utils.getReLUAct2[Double](1, i),
  iterations: Int = 150000,
  iterations_tuning: Int = 20000,
  pdt_iterations: Int = 2,
  miniBatch: Int = 32,
  optimizer: Optimizer = tf.train.AdaDelta(0.01f),
  sum_dir_prefix: String = "cdt",
  prior_types: Seq[helios.learn.cdt_loss.Divergence] =
    Seq(helios.learn.cdt_loss.KullbackLeibler),
  target_probs: Seq[helios.learn.cdt_loss.TargetDistribution] =
    Seq(helios.learn.cdt_loss.Boltzmann),
  dist_type: String = "default",
  timelag_pred_strategy: String = "mode",
  summaries_top_dir: Path = home / 'tmp,
  num_samples: Int = 20,
  hyper_optimizer: String = "gs",
  hyp_opt_iterations: Option[Int] = Some(5),
  epochFlag: Boolean = false,
  regularization_types: Seq[String] = Seq("L2"),
  checkpointing_freq: Int = 5
): Seq[timelag.ExperimentResult[Double, Double, timelag.TunedModelRun[
  Double,
  Double
]]] = {

  val mo_flag       = true
  val prob_timelags = true

  val num_pred_dims = timelag.utils.get_num_output_dims(
    sliding_window,
    mo_flag,
    prob_timelags,
    dist_type
  )

  val (net_layer_sizes, layer_shapes, layer_parameter_names, layer_datatypes) =
    timelag.utils.get_ffnet_properties(
      -1,
      num_pred_dims,
      num_neurons,
      "FLOAT64"
    )

  val output_mapping = timelag.utils.get_output_mapping[Double](
    sliding_window,
    mo_flag,
    prob_timelags,
    dist_type
  )

  //Prediction architecture
  val architecture =
    dtflearn.feedforward_stack[Double](activation_func)(net_layer_sizes.tail) >>
      output_mapping

  val scope = dtfutils.get_scope(architecture) _

  val layer_scopes = layer_parameter_names.map(n => scope(n.split("/").head))

  val persistent_hyper_parameters = List("reg", "temperature")

  val logit =
    Encoder((x: Double) => math.log(x / (1d - x)), (x: Double) => sigmoid(x))

  val fitness_to_scalar =
    DataPipe[Seq[Tensor[Float]], Double](s => {
      val metrics = s.map(_.scalar.toDouble)
      metrics(2) / (metrics.head * metrics.head) - 2 * math
        .pow(metrics(1) / metrics.head, 2)
    })

  val dataset: timelag.utils.TLDATA[Double] =
    timelag.utils.generate_data[Double](
      compute_output,
      sliding_window,
      d,
      size_training,
      noiserot,
      alpha,
      noise
    )

  val dataset_test: timelag.utils.TLDATA[Double] =
    timelag.utils.generate_data[Double](
      compute_output,
      sliding_window,
      d,
      size_test,
      noiserot,
      alpha,
      noise
    )

  for (c                   <- confounding;
       prior_type          <- prior_types;
       target_prob         <- target_probs;
       regularization_type <- regularization_types) yield {

    val hyper_parameters = target_prob match {

      case helios.learn.cdt_loss.Boltzmann =>
        List(
          "error_wt",
          "temperature",
          "specificity",
          "reg"
        )

      case _ =>
        List(
          "error_wt",
          "specificity",
          "reg"
        )
    }

    val hyper_prior = target_prob match {

      case helios.learn.cdt_loss.Boltzmann =>
        Map(
          "error_wt"    -> UniformRV(0d, 1.5),
          "temperature" -> UniformRV(1d, 2.0),
          "specificity" -> UniformRV(0.5, 2.5),
          "reg"         -> UniformRV(-5d, -3d)
        )

      case _ =>
        Map(
          "error_wt"    -> UniformRV(0d, 1.5),
          "specificity" -> UniformRV(0.5, 2.5),
          "reg"         -> UniformRV(-5d, -2.5d)
        )
    }

    val hyp_scaling = hyper_prior.map(
      p =>
        (
          p._1,
          Encoder(
            (x: Double) => (x - p._2.min) / (p._2.max - p._2.min),
            (u: Double) => u * (p._2.max - p._2.min) + p._2.min
          )
        )
    )

    val hyp_mapping = Some(
      hyper_parameters
        .map(
          h => (h, hyp_scaling(h) > logit)
        )
        .toMap
    )

    val loss_func_generator = (h: Map[String, Double]) => {

      val lossFunc = timelag.utils.get_loss[Double, Double, Double](
        sliding_window,
        mo_flag,
        prob_timelags,
        prior_wt = 1d,
        prior_divergence = prior_type,
        target_dist = target_prob,
        temp = h.getOrElse("temperature", 1.0),
        error_wt = h("error_wt"),
        c = h("specificity")
      )

      val reg_layer =
        if (regularization_type == "L1")
          L1Regularization[Double](
            layer_scopes,
            layer_parameter_names,
            layer_datatypes,
            layer_shapes,
            math.exp(h("reg")),
            "L1Reg"
          )
        else
          L2Regularization[Double](
            layer_scopes,
            layer_parameter_names,
            layer_datatypes,
            layer_shapes,
            math.exp(h("reg")),
            "L2Reg"
          )

      lossFunc >>
        reg_layer >>
        tf.learn.ScalarSummary("Loss", "ModelLoss")
    }

    val result = timelag.run_exp_alt(
      (dataset, dataset_test),
      architecture,
      hyper_parameters,
      persistent_hyper_parameters,
      helios.learn.cdt_loss.params_enc,
      loss_func_generator,
      hyper_prior,
      iterations,
      iterations_tuning,
      pdt_iterations,
      optimizer,
      miniBatch,
      sum_dir_prefix,
      summaries_top_dir,
      num_samples,
      hyper_optimizer,
      hyp_opt_iterations = hyp_opt_iterations,
      epochFlag = epochFlag,
      hyp_mapping = hyp_mapping,
      confounding_factor = c,
      fitness_to_scalar = fitness_to_scalar,
      //eval_metric_names = Seq("s0", "c1", "c2"),
      checkpointing_freq = checkpointing_freq
    )

    result.copy[Double, Double, timelag.TunedModelRun[Double, Double]](
      config = result.config.copy[Double](
        divergence = Some(prior_type),
        target_prob = Some(target_prob),
        reg_type = Some(regularization_type)
      )
    )
  }

}
