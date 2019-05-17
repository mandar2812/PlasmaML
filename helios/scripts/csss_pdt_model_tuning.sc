import _root_.org.joda.time._
import _root_.ammonite.ops._
import _root_.spire.implicits._
import _root_.io.github.mandar2812.dynaml.tensorflow._
import _root_.io.github.mandar2812.dynaml.probability._
import _root_.io.github.mandar2812.dynaml.pipes._
import _root_.io.github.mandar2812.dynaml.DynaMLPipe
import _root_.io.github.mandar2812.dynaml.analysis._
import _root_.io.github.mandar2812.dynaml.repl.Router.main
import _root_.io.github.mandar2812.PlasmaML.omni.OMNIData
import _root_.io.github.mandar2812.PlasmaML.helios.core.timelag
import _root_.io.github.mandar2812.PlasmaML.helios.fte
import _root_.io.github.mandar2812.dynaml.tensorflow.layers.{
  L1Regularization,
  L2Regularization
}
import _root_.io.github.mandar2812.PlasmaML.helios
import breeze.numerics.sigmoid
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.ops.NN.SameConvPadding
import org.platanios.tensorflow.api.learn.layers.{Activation, Layer}
import org.platanios.tensorflow.api.learn.Mode

def setup_exp_data(
  year_range: Range = 2011 to 2017,
  test_year: Int = 2015,
  sw_threshold: Double = 700d,
  quantity: Int = OMNIData.Quantities.V_SW,
  ts_transform_output: DataPipe[Seq[Double], Seq[Double]] =
    identityPipe[Seq[Double]],
  deltaT: (Int, Int) = (48, 72),
  use_persistence: Boolean = false,
  deltaTFTE: Int = 5,
  fteStep: Int = 1,
  latitude_limit: Double = 40d,
  fraction_pca: Double = 0.8,
  log_scale_fte: Boolean = false,
  log_scale_omni: Boolean = false,
  conv_flag: Boolean = false,
  fte_data_path: Path = home / 'Downloads / 'fte,
  summary_top_dir: Path = home / 'tmp,
  existing_exp: Option[Path] = None
): (fte.data.FteOmniConfig, Path) = {
  val mo_flag: Boolean       = true
  val prob_timelags: Boolean = true

  val urv = UniformRV(0d, 1d)

  val sum_dir_prefix = if (conv_flag) "fte_omni_conv" else "fte_omni"

  val dt = DateTime.now()

  val summary_dir_index = {
    if (mo_flag) sum_dir_prefix + "_mo_tl_" + dt.toString("YYYY-MM-dd-HH-mm")
    else sum_dir_prefix + "_tl_" + dt.toString("YYYY-MM-dd-HH-mm")
  }

  val tf_summary_dir_tmp =
    existing_exp.getOrElse(summary_top_dir / summary_dir_index)

  val adj_fraction_pca = 1d

  val experiment_config = fte.data.FteOmniConfig(
    fte.data.FTEConfig(
      (year_range.min, year_range.max),
      deltaTFTE,
      fteStep,
      latitude_limit,
      log_scale_fte
    ),
    fte.data.OMNIConfig(deltaT, log_scale_omni, quantity, use_persistence),
    multi_output = true,
    probabilistic_time_lags = true,
    timelag_prediction = "mode",
    fraction_variance = adj_fraction_pca
  )

  val existing_config =
    fte.data.read_exp_config(tf_summary_dir_tmp / "config.json")

  val use_cached_config: Boolean =
    fte.data._config_match(tf_summary_dir_tmp, experiment_config)

  val tf_summary_dir = if (use_cached_config) {
    println("Using provided experiment directory to continue experiment")
    tf_summary_dir_tmp
  } else {
    println(
      "Ignoring provided experiment directory and starting fresh experiment"
    )

    fte.data
      .write_exp_config(experiment_config, summary_top_dir / summary_dir_index)

    summary_top_dir / summary_dir_index
  }

  val use_cached_data = if (use_cached_config) {
    fte.data._dataset_serialized(tf_summary_dir)
  } else {
    false
  }

  val (test_start, test_end) = (
    new DateTime(test_year, 1, 1, 0, 0),
    new DateTime(test_year, 12, 31, 23, 59)
  )

  val tt_partition = DataPipe(
    (p: (DateTime, (DenseVector[Double], DenseVector[Double]))) =>
      if (p._1.isAfter(test_start) && p._1.isBefore(test_end))
        false
      else
        true
  )

  if (!use_cached_data) {

    val dataset =
      fte.data.generate_dataset(
        fte_data_path,
        experiment_config,
        ts_transform_output,
        tt_partition,
        conv_flag
      )

    println("Serializing data sets")
    fte.data.write_data_set(
      dt.toString("YYYY-MM-dd-HH-mm"),
      dataset,
      DataPipe[DenseVector[Double], Seq[Double]](_.toArray.toSeq),
      DataPipe[DenseVector[Double], Seq[Double]](_.toArray.toSeq),
      tf_summary_dir
    )
  }

  (experiment_config, tf_summary_dir)
}
@main
def apply(
  start_year: Int = 2011,
  end_year: Int = 2017,
  test_year: Int = 2015,
  sw_threshold: Double = 700d,
  network_size: Seq[Int] = Seq(100, 60),
  activation_func: Int => Layer[Output[Double], Output[Double]] = (i: Int) =>
    timelag.utils.getReLUAct3[Double](1, 1, i, 0f),
  history_fte: Int = 10,
  fte_step: Int = 2,
  crop_latitude: Double = 40d,
  fraction_pca: Double = 0.8,
  log_scale_fte: Boolean = false,
  log_scale_omni: Boolean = false,
  conv_flag: Boolean = false,
  quantity: Int = OMNIData.Quantities.V_SW,
  time_window: (Int, Int) = (48, 56),
  use_persistence: Boolean = false,
  ts_transform_output: DataPipe[Seq[Double], Seq[Double]] =
    identityPipe[Seq[Double]],
  max_iterations: Int = 100000,
  max_iterations_tuning: Int = 20000,
  pdt_iterations_tuning: Int = 4,
  pdt_iterations_test: Int = 14,
  num_samples: Int = 4,
  hyper_optimizer: String = "gs",
  batch_size: Int = 32,
  optimization_algo: tf.train.Optimizer = tf.train.Adam(0.01f),
  summary_dir: Path = home / 'tmp,
  hyp_opt_iterations: Option[Int] = Some(5),
  get_training_preds: Boolean = false,
  reg_type: String = "L2",
  existing_exp: Option[Path] = None,
  checkpointing_freq: Int = 1,
  data_scaling: String = "gauss"
): helios.Experiment[Double, fte.ModelRunTuning[DenseVector[Double]], fte.data.FteOmniConfig] = {

  val (net_layer_sizes, layer_shapes, layer_parameter_names, layer_datatypes) =
    dtfutils.get_ffstack_properties(
      -1,
      network_size.last,
      network_size.take(network_size.length - 1),
      "FLOAT64"
    )

  val sliding_window = ts_transform_output(
    Seq.fill(time_window._2)(0d)
  ).length

  val output_mapping = {

    val outputs_segment = if (data_scaling == "gauss") {
      tf.learn.Linear[Double]("Outputs", sliding_window)
    } else {
      tf.learn.Linear[Double]("Outputs", sliding_window) >>
        tf.learn.Sigmoid("ScaledOutputs")
    }

    val timelag_segment =
      tf.learn.Linear[Double]("TimeLags", sliding_window) >>
        tf.learn.Softmax[Double]("Probability/Softmax")

    dtflearn.bifurcation_layer("PDTNet", outputs_segment, timelag_segment)
  }

  val output_mapping2 = {

    val outputs_segment = if (data_scaling == "gauss") {
      tf.learn.Linear[Double]("Outputs", sliding_window)
    } else {
      tf.learn.Linear[Double]("Outputs", sliding_window) >>
        tf.learn.Sigmoid("ScaledOutputs")
    }

    val time_lag_segment = if(data_scaling == "gauss") {
      tf.learn.Sigmoid[Double](s"Act_Prob") >>
        tf.learn.Linear[Double]("TimeLags", sliding_window) >>
        tf.learn.Softmax[Double]("Probability/Softmax")
    } else {
      tf.learn.Linear[Double]("TimeLags", sliding_window) >>
        tf.learn.Softmax[Double]("Probability/Softmax")
    }

    val select_outputs = dtflearn.layer(
      "Cast/Outputs",
      MetaPipe[Mode, (Output[Double], Output[Double]), Output[Double]](
        _ => o => o._1
      )
    )

    val output_segment =
      dtflearn.bifurcation_layer(
        "Bifurcation",
        outputs_segment,
        dtflearn.identity[Output[Double]]("ProjectFeat")
      ) >>
        dtflearn.bifurcation_layer(
          "PDT",
          select_outputs,
          dtflearn
            .concat_tuple2[Double]("Concat_Out_Feat", 1) >> time_lag_segment
        )

    output_segment
  }

  val hyper_parameters = List(
    "sigma_sq",
    "alpha",
    "reg"
  )

  val persistent_hyper_parameters = List("reg")

  val hyper_prior = Map(
    "reg"      -> UniformRV(-5.5d, -4d),
    "alpha"    -> UniformRV(0.75d, 2d),
    "sigma_sq" -> UniformRV(1e-5, 5d)
  )

  val params_enc = Encoder(
    identityPipe[Map[String, Double]],
    identityPipe[Map[String, Double]]
  )

  val filter_depths = Seq(
    Seq(4, 4, 4, 4),
    Seq(2, 2, 2, 2),
    Seq(1, 1, 1, 1)
  )

  val activation = DataPipe[String, Layer[Output[Float], Output[Float]]](
    (s: String) => tf.learn.ReLU(s, 0.01f)
  )

  //Prediction architecture
  val architecture = if (conv_flag) {
    tf.learn.Cast[Double, Float]("Cast/Input") >>
      dtflearn.inception_unit[Float](
        channels = 1,
        filter_depths.head,
        activation,
        use_batch_norm = true
      )(layer_index = 1) >>
      tf.learn.MaxPool(s"MaxPool_1", Seq(1, 3, 3, 1), 2, 2, SameConvPadding) >>
      dtflearn.inception_unit[Float](
        filter_depths.head.sum,
        filter_depths(1),
        activation,
        use_batch_norm = true
      )(layer_index = 2) >>
      tf.learn.MaxPool(s"MaxPool_2", Seq(1, 3, 3, 1), 2, 2, SameConvPadding) >>
      dtflearn.inception_unit[Float](
        filter_depths(1).sum,
        filter_depths.last,
        activation,
        use_batch_norm = true
      )(layer_index = 3) >>
      tf.learn.MaxPool(s"MaxPool_3", Seq(1, 3, 3, 1), 2, 2, SameConvPadding) >>
      tf.learn.Flatten("FlattenFeatures") >>
      tf.learn.Cast[Float, Double]("Cast/Output") >>
      dtflearn.feedforward_stack[Double](activation_func)(net_layer_sizes.tail) >>
      output_mapping2
  } else {
    dtflearn.feedforward_stack[Double](activation_func)(net_layer_sizes.tail) >>
      activation_func(net_layer_sizes.tail.length) >>
      output_mapping2
  }

  val scope = dtfutils.get_scope(architecture) _

  val layer_scopes = layer_parameter_names.map(n => scope(n.split("/").head))

  val output_scope = scope("Outputs")

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

  val logit =
    Encoder((x: Double) => math.log(x / (1d - x)), (x: Double) => sigmoid(x))

  val hyp_mapping = Some(
    hyper_parameters
      .map(
        h => (h, hyp_scaling(h) > logit)
      )
      .toMap
  )

  val fitness_to_scalar =
    DataPipe[Seq[Tensor[Float]], Double](s => {
      val metrics = s.map(_.scalar.toDouble)
      metrics(1) / metrics.head
    })

  val loss_func_generator = (h: Map[String, Double]) => {

    val lossFunc = timelag.utils.get_pdt_loss[Double, Double, Double](
      sliding_window,
      h("sigma_sq"),
      h("alpha")
    )

    val reg =
      if (reg_type == "L2")
        L2Regularization[Double](
          layer_scopes /* :+ output_scope */,
          layer_parameter_names /* :+ "Outputs/Weights" */,
          layer_datatypes /* :+ "FLOAT64" */,
          layer_shapes /* :+ Shape(network_size.last, sliding_window) */,
          math.exp(h("reg")),
          "L2Reg"
        )
      else
        L1Regularization[Double](
          layer_scopes /* :+ output_scope */,
          layer_parameter_names /* :+ "Outputs/Weights" */,
          layer_datatypes /* :+ "FLOAT64" */,
          layer_shapes /* :+ Shape(network_size.last, sliding_window) */,
          math.exp(h("reg")),
          "L1Reg"
        )

    lossFunc >>
      reg >>
      tf.learn.ScalarSummary("Loss", "ModelLoss")
  }

  /* fte.exp_cdt_alt(
    architecture,
    hyper_parameters,
    persistent_hyper_parameters,
    params_enc,
    loss_func_generator,
    hyper_prior,
    hyp_mapping = hyp_mapping,
    year_range = start_year to end_year,
    test_year = test_year,
    sw_threshold = sw_threshold,
    quantity = quantity,
    optimizer = optimization_algo,
    miniBatch = batch_size,
    iterations = max_iterations,
    num_samples = num_samples,
    hyper_optimizer = hyper_optimizer,
    iterations_tuning = max_iterations_tuning,
    pdt_iterations_tuning = pdt_iterations_tuning,
    pdt_iterations_test = pdt_iterations_test,
    latitude_limit = crop_latitude,
    fraction_pca = fraction_pca,
    deltaTFTE = history_fte,
    fteStep = fte_step,
    conv_flag = conv_flag,
    log_scale_fte = log_scale_fte,
    log_scale_omni = log_scale_omni,
    deltaT = time_window,
    ts_transform_output = ts_transform_output,
    summary_top_dir = summary_dir,
    hyp_opt_iterations = hyp_opt_iterations,
    get_training_preds = get_training_preds,
    existing_exp = existing_exp,
    fitness_to_scalar = fitness_to_scalar,
    checkpointing_freq = checkpointing_freq
  ) */

  val (experiment_config, tf_summary_dir) = setup_exp_data(
    start_year to end_year,
    test_year = test_year,
    sw_threshold = sw_threshold,
    quantity = quantity,
    ts_transform_output = ts_transform_output,
    deltaT = time_window,
    use_persistence = use_persistence,
    deltaTFTE = history_fte,
    fteStep = fte_step,
    latitude_limit = crop_latitude,
    fraction_pca = fraction_pca,
    log_scale_fte = log_scale_fte,
    log_scale_omni = log_scale_omni,
    conv_flag = conv_flag,
    fte_data_path = home / 'Downloads / 'fte,
    summary_top_dir = summary_dir,
    existing_exp = existing_exp
  )

  val results = fte.run_exp(
    tf_summary_dir,
    architecture,
    hyper_parameters,
    persistent_hyper_parameters,
    params_enc,
    loss_func_generator,
    hyper_prior,
    hyp_mapping,
    max_iterations,
    max_iterations_tuning,
    pdt_iterations_tuning,
    pdt_iterations_test,
    num_samples,
    hyper_optimizer,
    batch_size,
    optimization_algo,
    hyp_opt_iterations,
    get_training_preds,
    existing_exp,
    fitness_to_scalar,
    checkpointing_freq,
    experiment_config.omni_config.log_flag,
    data_scaling = data_scaling
  )

  helios.Experiment(
    experiment_config,
    results
  )
}
