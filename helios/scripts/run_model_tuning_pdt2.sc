import _root_.io.github.mandar2812.dynaml.tensorflow._
import _root_.io.github.mandar2812.dynaml.pipes._
import _root_.io.github.mandar2812.dynaml.DynaMLPipe._
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
import org.platanios.tensorflow.api.learn.layers.Layer
import org.platanios.tensorflow.api.learn.Mode

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
  summaries_top_dir: Path = home / 'tmp,
  num_samples: Int = 20,
  hyper_optimizer: String = "gs",
  hyp_opt_iterations: Option[Int] = Some(5),
  epochFlag: Boolean = false,
  regularization_types: Seq[String] = Seq("L2"),
  checkpointing_freq: Int = 4
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
    "default"
  )

  val (net_layer_sizes, layer_shapes, layer_parameter_names, layer_datatypes) =
    dtfutils.get_ffstack_properties(
      d,
      sliding_window,
      num_neurons,
      "FLOAT64"
    )

  val (
    net_layer_sizes_2,
    layer_shapes_2,
    layer_parameter_names_2,
    layer_datatypes_2
  ) =
    dtfutils.get_ffstack_properties(
      d,
      sliding_window,
      num_neurons,
      "FLOAT64"
    )

  val softmax_layer =
    new Layer[Output[Double], Output[Double]]("Output/Softmax") {
      override val layerType: String = s"Softmax"

      override def forwardWithoutContext(
        input: Output[Double]
      )(
        implicit mode: Mode
      ): Output[Double] = input.softmax()
    }

  val outputs_segment =
    dtflearn.feedforward_stack[Double](activation_func)(net_layer_sizes.tail)

  val tl_start_index = net_layer_sizes.tail.length + 1

  val timelag_segment =
    dtflearn.feedforward_stack[Double](
      (i: Int) => timelag.utils.getReLUAct2[Double](tl_start_index, i)
    )(
      net_layer_sizes.tail,
      starting_index = tl_start_index
    ) >> softmax_layer

  //Prediction architecture
  val architecture =
    dtflearn.bifurcation_layer("PDTNetwork", outputs_segment, timelag_segment)

  val scope = dtfutils.get_scope(architecture) _

  val layer_scopes =
    layer_parameter_names.map(n => scope(n.split("/").head)) /* ++
      layer_parameter_names_2.map(n => scope(n.split("/").head)) */

  val hyper_parameters = List(
    "sigma_sq",
    "alpha",
    "reg"
  )

  val persistent_hyper_parameters = List("reg")

  val hyper_prior = Map(
    "sigma_sq" -> UniformRV(1d, 10d),
    "alpha"    -> UniformRV(0.75d, 2d),
    "reg"      -> UniformRV(-6d, -5d)
  )

  val params_enc = Encoder(
    identityPipe[Map[String, Double]],
    identityPipe[Map[String, Double]]
  )

  val logit =
    Encoder((x: Double) => math.log(x / (1d - x)), (x: Double) => sigmoid(x))

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

  val fitness_to_scalar =
    DataPipe[Seq[Tensor[Float]], Double](s => s.map(_.scalar.toDouble).sum)

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
       regularization_type <- regularization_types) yield {

    val loss_func_generator = (h: Map[String, Double]) => {

      val lossFunc = timelag.utils.get_pdt_loss[Double, Double, Double](
        sliding_window,
        h("sigma_sq"),
        h("alpha")
      )

      val reg_layer =
        if (regularization_type == "L1")
          L1Regularization[Double](
            layer_scopes,
            layer_parameter_names, //++ layer_parameter_names_2,
            layer_datatypes,       //++ layer_datatypes_2,
            layer_shapes,          //++ layer_shapes_2,
            math.exp(h("reg")),
            "L1Reg"
          )
        else
          L2Regularization[Double](
            layer_scopes,
            layer_parameter_names, //++ layer_parameter_names_2,
            layer_datatypes,       //++ layer_datatypes_2,
            layer_shapes,          //++ layer_shapes_2,
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
      params_enc,
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
      checkpointing_freq = checkpointing_freq
    )

    result.copy[Double, Double, timelag.TunedModelRun[Double, Double]](
      config = result.config.copy[Double](
        divergence = None,
        target_prob = None,
        reg_type = Some(regularization_type)
      )
    )
  }

}
