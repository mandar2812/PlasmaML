package io.github.mandar2812.PlasmaML.helios

import ammonite.ops._
import org.joda.time._
import breeze.stats._
import io.github.mandar2812.dynaml.DynaMLPipe._
import io.github.mandar2812.dynaml.pipes._
import io.github.mandar2812.dynaml.evaluation._
import io.github.mandar2812.dynaml.optimization._
import io.github.mandar2812.dynaml.models.{TFModel, TunableTFModel}
import io.github.mandar2812.dynaml.tensorflow.data._
import io.github.mandar2812.dynaml.tensorflow.utils._
import io.github.mandar2812.dynaml.tensorflow.{dtf, dtfdata, dtflearn, dtfutils}
import io.github.mandar2812.dynaml.tensorflow.implicits._
import io.github.mandar2812.dynaml.probability._
import _root_.io.github.mandar2812.PlasmaML.omni.{OMNIData, OMNILoader}
import _root_.io.github.mandar2812.dynaml.tensorflow.layers.{L2Regularization, L1Regularization}
import _root_.io.github.mandar2812.PlasmaML.helios
import _root_.io.github.mandar2812.PlasmaML.helios.core.timelag
import breeze.stats.distributions.ContinuousDistr
import org.platanios.tensorflow.api.learn.layers.{Activation, Layer}
import org.platanios.tensorflow.api.ops.NN.SameConvPadding
import org.platanios.tensorflow.api.learn.Mode
import org.platanios.tensorflow.api.ops.variables.RandomNormalInitializer
import org.platanios.tensorflow.api.types.DataType
import org.platanios.tensorflow.api._
import _root_.io.github.mandar2812.dynaml.models.TunableTFModel.HyperParams

package object fte {

  type ModelRunTuning = TunedModelRun[
    Tensor, Output, DataType.Aux[Double], DataType, Shape, (Output, Output), (Tensor, Tensor),
    Tensor, Output, DataType.Aux[Double], DataType, Shape, Output]

  //Customized layer based on Bala et. al
  val quadratic_fit = (name: String) => new Layer[Output, Output](name) {
    override val layerType = s"LocalQuadraticFit"

    override protected def _forward(input: Output)(implicit mode: Mode) = {

      val aa = tf.variable("aa", input.dataType, Shape(), RandomNormalInitializer())
      val bb = tf.variable("bb", input.dataType, Shape(), RandomNormalInitializer())
      val cc = tf.variable("cc", input.dataType, Shape(), RandomNormalInitializer())


      val a = input.pow(2.0).multiply(aa)
      val b = input.multiply(bb)

      a + b + cc
    }
  }


  //Set time zone to UTC
  DateTimeZone.setDefault(DateTimeZone.UTC)

  //Load the Carrington Rotation Table
  val carrington_rotation_table: Path = pwd/'data/"CR_Table.rdb"

  val process_carrington_file: DataPipe[Path, Stream[Array[String]]] =
    DataPipe((p: Path) => (read.lines! p).toStream) >
      dropHead >
      dropHead >
      trimLines >
      replaceWhiteSpaces >
      splitLine

  case class CarringtonRotation(start: DateTime, end: DateTime) {

    def contains(dt: DateTime): Boolean = dt.isAfter(start) && dt.isBefore(end)
  }

  val read_time_stamps = DataPipe((s: Array[String]) => {

    val datetime_pattern = "YYYY.MM.dd_HH:mm:ss"
    val dt = format.DateTimeFormat.forPattern(datetime_pattern)

    val limits = (DateTime.parse(s(1), dt), DateTime.parse(s(3), dt))

    (s.head.toInt, CarringtonRotation(limits._1, limits._2))
  })

  val carrington_rotations: ZipDataSet[Int, CarringtonRotation] =
    dtfdata.dataset(process_carrington_file(carrington_rotation_table)).to_zip(read_time_stamps)

  val fte_file = MetaPipe(
    (data_path: Path) =>
      (carrington_rotation: Int) =>
        data_path/s"HMIfootpoint_ch_csss${carrington_rotation}HR.dat"
  )


  case class FTEPattern(longitude: Double, latitude: Double, fte: Option[Double])

  val process_fte_file = {
    fte_file >> (
      DataPipe((p: Path) => (read.lines! p).toStream) >
        Seq.fill(4)(dropHead).reduceLeft(_ > _) >
        trimLines >
        replaceWhiteSpaces >
        splitLine >
        IterableDataPipe((s: Array[String]) => s.length == 5) >
        IterableDataPipe((s: Array[String]) => {
          val (lon, lat) = (s.head.toDouble, s(1).toDouble)
          val fte: Option[Double] = try {
            Some(s(2).toDouble)
          } catch {
            case _: Exception => None
          }

          FTEPattern(lon, lat, fte)

        })
      )

  }

  case class FTEConfig()


  /**
    * Load the Flux Tube Expansion (FTE) data.
    *
    * Assumes the files have the schema
    * "HMIfootpoint_ch_csss{carrington_rotation}HR.dat"
    *
    * @param data_path Path containing the FTE data files.
    * @param cr The Carrington rotation number
    *
    * @throws java.nio.file.NoSuchFileException if the files cannot be
    *                                           found in the specified location
    * */
  def get_fte_for_rotation(data_path: Path)(cr: Int): Iterable[(Int, Iterable[FTEPattern])] = try {
    Iterable((cr, process_fte_file(data_path)(cr)))
  } catch {
    case _: java.nio.file.NoSuchFileException => Iterable()
  }


  val process_rotation = DataPipe((rotation_data: (Int, (CarringtonRotation, Iterable[FTEPattern]))) => {

    val (_, (rotation, fte)) = rotation_data

    val duration = new Duration(rotation.start, rotation.end)

    val time_jump = duration.getMillis/360.0

    fte.map(p => (rotation.end.toInstant.minus((time_jump*p.longitude).toLong).toDateTime, p))

  })

  val image_dt_roundoff: DataPipe[DateTime, DateTime] = DataPipe((d: DateTime) =>
    new DateTime(
      d.getYear, d.getMonthOfYear,
      d.getDayOfMonth, d.getHourOfDay,
      0, 0)
  )

  implicit val dateOrdering: Ordering[DateTime] = new Ordering[DateTime] {
    override def compare(x: DateTime, y: DateTime): Int = if(x.isBefore(y)) -1 else 1
  }


  /**
    * Creates a DynaML data set consisting of time FTE values.
    * The FTE values are loaded in a [[Tensor]] object.
    * */
  def load_fte_data(
    data_path: Path,
    carrington_rotation_table: ZipDataSet[Int, CarringtonRotation],
    log_flag: Boolean,
    start: DateTime, end: DateTime)(
    deltaTFTE: Int,
    fte_step: Int,
    latitude_limit: Double,
    conv_flag: Boolean): ZipDataSet[DateTime, Tensor] = {

    val start_rotation = carrington_rotation_table.filter(_._2.contains(start)).data.head._1

    val end_rotation = carrington_rotation_table.filter(_._2.contains(end)).data.head._1


    val clamp_fte: FTEPattern => FTEPattern = (p: FTEPattern) => p.fte match {
      case Some(f) => if(math.abs(f) <= 1000d) p else p.copy(fte = Some(1000d*math.signum(f)))
      case None => p
    }

    val load_fte_for_rotation = DataPipe(get_fte_for_rotation(data_path) _)



    val fte = dtfdata.dataset(start_rotation to end_rotation)
      .flatMap(load_fte_for_rotation)
      .map(identityPipe[Int] * IterableDataPipe[FTEPattern, FTEPattern](clamp_fte))
      .to_zip(identityPipe)

    val fte_data = carrington_rotation_table.join(fte)

    val log_transformation =
      (x: Double) => if(log_flag) {
        if(math.abs(x) < 1d) 0d
        else math.log10(math.abs(x))
      } else x

    val crop_data_by_latitude = (pattern: (DateTime, Seq[FTEPattern])) =>
      (pattern._1, pattern._2.filter(ftep => math.abs(ftep.latitude) <= latitude_limit))

    val load_slice_to_tensor = DataPipe(
      (s: Seq[FTEPattern]) => Tensor(s.map(_.fte.get).map(log_transformation)).reshape(Shape(s.length))
    )

    val sort_by_date = (s: Iterable[(DateTime, Seq[FTEPattern])]) => s.toSeq.sortBy(_._1)

    val processed_fte_data = {
      fte_data.flatMap(process_rotation)
        .transform(
          (data: Iterable[(DateTime, FTEPattern)]) => data
            .groupBy(_._1)
            .map(p => (
              p._1,
              p._2.map(_._2).toSeq.sortBy(_.latitude))))
        .filter(_._2.length == 180)
        .map(crop_data_by_latitude)
        .map(image_dt_roundoff * identityPipe[Seq[FTEPattern]])
        .transform(sort_by_date)
        .map(identityPipe[DateTime] * load_slice_to_tensor)
        .to_zip(identityPipe)
    }

    println("Interpolating FTE values to fill hourly cadence requirement")
    val interpolated_fte = dtfdata.dataset(
      processed_fte_data.data.sliding(2)
        .filter(p => new Duration(p.head._1, p.last._1).getStandardHours > 1)
        .flatMap(i => {
          val duration = new Duration(i.head._1, i.last._1).getStandardHours
          val delta_fte = (i.last._2 - i.head._2)/duration.toDouble

          (1 until duration.toInt).map(l => (i.head._1.plusHours(l), i.head._2 + delta_fte*l))
        }).toIterable
    )

    val load_history = (history: Iterable[(DateTime, Tensor)]) => {

      val history_size = history.toSeq.length/fte_step

      val hs = history.map(_._2).toSeq.zipWithIndex.filter(_._2 % fte_step == 0).map(_._1)

      (
        history.last._1,
        if(conv_flag)
          tfi.stack(hs, axis = -1).reshape(history.head._2.shape ++ Shape(history_size, 1))
        else
          tfi.concatenate(hs, axis = -1)
      )
    }

    val generate_history = (s: Iterable[(DateTime, Tensor)]) =>
      if (deltaTFTE > 0) s.sliding((deltaTFTE + 1)*fte_step).map(load_history).toIterable
      else if(conv_flag) s.map(c => (c._1, c._2.reshape(Shape(c._2.shape(0), 1, 1))))
      else s


    processed_fte_data
      .concatenate(interpolated_fte)
      .transform((data: Iterable[(DateTime, Tensor)]) => data.toSeq.sortBy(_._1))
      .transform(generate_history)
      .to_zip(identityPipe[(DateTime, Tensor)])

  }

  /**
    * Load the OMNI solar wind time series as a [[Tensor]]
    *
    * @param start Starting time of the data.
    * @param end End time of the data.
    * @param deltaT The time window (t + l, t + l + h)
    * @param log_flag If set to true, log scale the velocity values.
    * @param quantity An integer column index corresponding to the OMNI
    *                 quantity to extract. Defaults to [[OMNIData.Quantities.V_SW]]
    *
    * @return A [[ZipDataSet]] with time indexed tensors containing
    *         sliding time histories of the solar wind.
    * */
  def load_solar_wind_data(
    start: DateTime, end: DateTime)(
    deltaT: (Int, Int),
    log_flag: Boolean,
    quantity: Int = OMNIData.Quantities.V_SW): ZipDataSet[DateTime, Tensor] = {

    val omni_processing =
      OMNILoader.omniVarToSlidingTS(deltaT._1, deltaT._2)(quantity) >
        IterableDataPipe(
          (p: (DateTime, Seq[Double])) => p._1.isAfter(start) && p._1.isBefore(end)) >
        IterableDataPipe(
          (p: (DateTime, Seq[Double])) => (p._1, if(log_flag) p._2.map(math.log) else p._2))

    val omni_data_path = pwd/'data

    dtfdata.dataset(start.getYear to end.getYear)
      .map(DataPipe((i: Int) => omni_data_path.toString()+"/"+OMNIData.getFilePattern(i)))
      .transform(omni_processing)
      .to_zip(identityPipe[DateTime] * DataPipe(
        (s: Seq[Double]) => if (s.length == 1) Tensor(s).reshape(Shape()) else Tensor(s).reshape(Shape(s.length))
      ))

  }

  type SC_DATA = (helios.data.TF_DATA, (GaussianScalerTF, GaussianScalerTF))

  val scale_dataset = DataPipe((dataset: TFDataSet[(Tensor, Tensor)]) => {

    val concat_features = tfi.stack(
      dataset.training_dataset.map(DataPipe((p: (Tensor, Tensor)) => p._1)).data.toSeq
    )

    val concat_targets = tfi.stack(
      dataset.training_dataset.map(DataPipe((p: (Tensor, Tensor)) => p._2)).data.toSeq
    )

    //val (min, max) = (concat_targets.min(axes = 0), concat_targets.max(axes = 0))

    val n = concat_features.shape(0)

    val mean_t = concat_targets.mean(axes = 0)
    val std_t  = concat_targets.subtract(mean_t).square.mean(axes = 0).multiply(n/(n-1)).sqrt
    val mean_f = concat_features.mean(axes = 0)
    val std_f  = concat_features.subtract(mean_f).square.mean(axes = 0).multiply(n/(n-1)).sqrt

    val targets_scaler = GaussianScalerTF(mean_t, std_t)

    val features_scaler = GaussianScalerTF(mean_f, std_f)

    (
      dataset.copy(
        training_dataset = dataset.training_dataset.map(features_scaler * targets_scaler),
        test_dataset     = dataset.test_dataset.map(features_scaler * identityPipe[Tensor])
      ),
      (features_scaler, targets_scaler)
    )

  })

  /**
    * A configuration object for running experiments
    * on the FTE data sets.
    *
    * Contains cached values of experiment parameters, and data sets.
    * */
  object FTExperiment {

    case class FTEConfig(
      data_limits: (Int, Int),
      deltaTFTE: Int,
      fteStep: Int,
      latitude_limit: Double,
      log_scale_fte: Boolean)

    case class OMNIConfig(deltaT: (Int, Int), log_flag: Boolean)

    case class Config(
      fte_config: FTEConfig,
      omni_config: OMNIConfig,
      multi_output: Boolean = true,
      probabilistic_time_lags: Boolean = true,
      timelag_prediction: String = "mode")
      extends helios.Config


    var config = Config(
      FTEConfig((0, 0), 0, 1, 90d, log_scale_fte = false),
      OMNIConfig((0, 0), log_flag = false)
    )

    var fte_data: ZipDataSet[DateTime, Tensor] = dtfdata.dataset(Iterable[(DateTime, Tensor)]()).to_zip(identityPipe)

    var omni_data: ZipDataSet[DateTime, Tensor] = dtfdata.dataset(Iterable[(DateTime, Tensor)]()).to_zip(identityPipe)

    def clear_cache(): Unit = {
      fte_data = dtfdata.dataset(Iterable[(DateTime, Tensor)]()).to_zip(identityPipe)
      omni_data = dtfdata.dataset(Iterable[(DateTime, Tensor)]()).to_zip(identityPipe)
      config = Config(
        FTEConfig((0, 0), 0, 1, 90d, log_scale_fte = false),
        OMNIConfig((0, 0), log_flag = false)
      )
    }

  }



  def exp_cdt(
    num_neurons: Seq[Int] = Seq(30, 30),
    activation_func: Int => Activation = timelag.utils.getReLUAct(1),
    optimizer: tf.train.Optimizer = tf.train.Adam(0.001),
    year_range: Range = 2011 to 2017,
    test_year: Int = 2015,
    sw_threshold: Double = 700d,
    deltaT: (Int, Int) = (48, 72),
    deltaTFTE: Int = 5,
    fteStep: Int = 1,
    latitude_limit: Double = 40d,
    reg: Double = 0.0001,
    p_wt: Double = 0.75,
    e_wt: Double = 1.0,
    specificity: Double = 1.5,
    temperature: Double = 1.0,
    divergence: helios.learn.cdt_loss.Divergence = helios.learn.cdt_loss.KullbackLeibler,
    mo_flag: Boolean = true,
    prob_timelags: Boolean = true,
    log_scale_fte: Boolean = false,
    log_scale_omni: Boolean = false,
    conv_flag: Boolean = false,
    iterations: Int = 10000,
    miniBatch: Int = 32,
    fte_data_path: Path = home/'Downloads/'fte,
    summary_top_dir: Path = home/'tmp) = {


    val sum_dir_prefix = "fte_omni"

    val dt = DateTime.now()

    val summary_dir_index = {
      if(mo_flag) sum_dir_prefix+"_timelag_inference_mo_"+dt.toString("YYYY-MM-dd-HH-mm")
      else sum_dir_prefix+"_timelag_inference_"+dt.toString("YYYY-MM-dd-HH-mm")
    }

    val tf_summary_dir     = summary_top_dir/summary_dir_index

    val (test_start, test_end) = (
      new DateTime(test_year, 1, 1, 0, 0),
      new DateTime(test_year, 12, 31, 23, 59)
    )

    val (start, end) = (
      new DateTime(year_range.min, 1, 1, 0, 0),
      new DateTime(year_range.max, 12, 31, 23, 59))


    if(FTExperiment.fte_data.size == 0 ||
      FTExperiment.config.fte_config != FTExperiment.FTEConfig(
        (year_range.min, year_range.max),
        deltaTFTE, fteStep,
        latitude_limit,
        log_scale_fte)
    ) {

      println("\nProcessing FTE Data")

      FTExperiment.fte_data = load_fte_data(
        fte_data_path, carrington_rotations,
        log_scale_fte, start, end)(deltaTFTE, fteStep, latitude_limit, conv_flag)

      FTExperiment.config = FTExperiment.config.copy(fte_config = FTExperiment.FTEConfig(
        (year_range.min, year_range.max),
        deltaTFTE, fteStep, latitude_limit,
        log_scale_fte))



    } else {
      println("\nUsing cached FTE data sets")
    }


    if(
      FTExperiment.omni_data.size == 0 ||
        FTExperiment.config.omni_config != FTExperiment.OMNIConfig(deltaT, log_scale_omni)) {

      println("Processing OMNI solar wind data")
      FTExperiment.omni_data = load_solar_wind_data(start, end)(deltaT, log_scale_omni)

      FTExperiment.config = FTExperiment.config.copy(omni_config = FTExperiment.OMNIConfig(deltaT, log_scale_omni))

    } else {
      println("\nUsing cached OMNI data set")
    }

    val tt_partition = DataPipe((p: (DateTime, (Tensor, Tensor))) =>
      if (p._1.isAfter(test_start) && p._1.isBefore(test_end))
        false
      else
        true
    )

    println("Constructing joined data set")
    val dataset = FTExperiment.fte_data.join(FTExperiment.omni_data).partition(tt_partition)

    val causal_window = dataset.training_dataset.data.head._2._2.shape(0)

    val input_dim = dataset.training_dataset.data.head._2._1.shape

    val input = tf.learn.Input(FLOAT64, Shape(-1) ++ input_dim)

    val trainInput = tf.learn.Input(FLOAT64, Shape(-1, causal_window))

    val trainingInputLayer = tf.learn.Cast("TrainInput", FLOAT64)

    val summariesDir = java.nio.file.Paths.get(tf_summary_dir.toString())


    val num_pred_dims = timelag.utils.get_num_output_dims(causal_window, mo_flag, prob_timelags, "default")

    val (net_layer_sizes, layer_shapes, layer_parameter_names, layer_datatypes) =
      timelag.utils.get_ffnet_properties(-1, num_pred_dims, num_neurons)

    val output_mapping = timelag.utils.get_output_mapping(causal_window, mo_flag, prob_timelags, "default")

    val filter_depths = Seq(
      Seq(4, 4, 4, 4),
      Seq(2, 2, 2, 2),
      Seq(1, 1, 1, 1)
    )

    val activation = DataPipe[String, Layer[Output, Output]]((s: String) => tf.learn.ReLU(s, 0.01f))

    //Prediction architecture
    val architecture = if (conv_flag) {
      tf.learn.Cast("Cast/Input", FLOAT32) >>
        dtflearn.inception_unit(
          channels = 1, filter_depths.head,
          activation, use_batch_norm = true)(layer_index = 1) >>
        tf.learn.MaxPool(s"MaxPool_1", Seq(1, 3, 3, 1), 2, 2, SameConvPadding) >>
        dtflearn.inception_unit(
          filter_depths.head.sum, filter_depths(1),
          activation, use_batch_norm = true)(layer_index = 2) >>
        tf.learn.MaxPool(s"MaxPool_2", Seq(1, 3, 3, 1), 2, 2, SameConvPadding) >>
        dtflearn.inception_unit(
          filter_depths(1).sum, filter_depths.last,
          activation, use_batch_norm = true)(layer_index = 3) >>
        tf.learn.MaxPool(s"MaxPool_3", Seq(1, 3, 3, 1), 2, 2, SameConvPadding) >>
        tf.learn.Flatten("FlattenFeatures") >>
        dtflearn.feedforward_stack(activation_func, FLOAT64)(net_layer_sizes.tail) >>
        output_mapping
    } else {
      dtflearn.feedforward_stack(activation_func,FLOAT64)(net_layer_sizes.tail) >>
        output_mapping
    }



    val lossFunc = timelag.utils.get_loss(
      causal_window, mo_flag, prob_timelags,
      prior_wt = p_wt, prior_divergence =  divergence,
      error_wt = e_wt, c = specificity, temp = temperature)

    val loss = lossFunc >>
      L2Regularization(layer_parameter_names, layer_datatypes, layer_shapes, reg) >>
      tf.learn.ScalarSummary("Loss", "ModelLoss")


    println("Scaling data attributes")
    val (scaled_data, scalers): SC_DATA = scale_dataset(
      dataset.copy(
        training_dataset = dataset.training_dataset.map((p: (DateTime, (Tensor, Tensor))) => p._2),
        test_dataset = dataset.test_dataset.map((p: (DateTime, (Tensor, Tensor))) => p._2)
      )
    )


    val train_data_tf = {
      scaled_data.training_dataset
        .build[
        (Tensor, Tensor),
        (Output, Output),
        (DataType, DataType),
        (DataType, DataType),
        (Shape, Shape)](
        Left(identityPipe[(Tensor, Tensor)]),
        (FLOAT64, FLOAT64),
        (input_dim, Shape(causal_window)))
        .repeat()
        .shuffle(10)
        .batch(miniBatch)
        .prefetch(10)
    }

    val (model, estimator) = dtflearn.build_tf_model(
      architecture, input, trainInput, trainingInputLayer,
      loss, optimizer, summariesDir,
      dtflearn.rel_loss_change_stop(0.005, iterations))(
      train_data_tf)


    val nTest = scaled_data.test_dataset.size

    val predictions: (Tensor, Tensor) = dtfutils.buffered_preds[
      Tensor, Output, DataType, Shape, (Output, Output),
      Tensor, Output, DataType, Shape, Output,
      Tensor, (Tensor, Tensor), (Tensor, Tensor)](
      estimator, tfi.stack(scaled_data.test_dataset.data.toSeq.map(_._1), axis = 0),
      500, nTest)

    val index_times = Tensor(
      (0 until causal_window).map(_.toDouble)
    ).reshape(
      Shape(causal_window)
    )

    val pred_time_lags_test: Tensor = if(prob_timelags) {
      val unsc_probs = predictions._2

      unsc_probs.topK(1)._2.reshape(Shape(nTest)).cast(FLOAT64)

    } else predictions._2

    val pred_targets: Tensor = if (mo_flag) {
      val all_preds =
        if (prob_timelags) scalers._2.i(predictions._1)
        else scalers._2.i(predictions._1)

      val repeated_times = tfi.stack(Seq.fill(causal_window)(pred_time_lags_test.floor), axis = -1)

      val conv_kernel = repeated_times.subtract(index_times).square.multiply(-1.0).exp.floor

      all_preds.multiply(conv_kernel).sum(axes = 1).divide(conv_kernel.sum(axes = 1))
    } else {
      scalers._2(0).i(predictions._1)
    }

    val test_labels = scaled_data.test_dataset.data.map(_._2).map(t => dtfutils.toDoubleSeq(t).toSeq).toSeq

    val actual_targets = test_labels.zipWithIndex.map(zi => {
      val (z, index) = zi
      val time_lag = pred_time_lags_test(index).scalar.asInstanceOf[Double].toInt

      z(time_lag)
    })

    val (final_predictions, final_targets) =
      if(log_scale_omni) (pred_targets.exp, actual_targets.map(math.exp))
      else (pred_targets, actual_targets)

    val reg_metrics = new RegressionMetricsTF(final_predictions, final_targets)

    val experiment_config = helios.ExperimentConfig(mo_flag, prob_timelags, "mode")

    val results = helios.SupervisedModelRun[Tensor, Tensor, (Tensor, Tensor), (Output, Output)](
      (scaled_data, scalers),
      model, estimator, None,
      Some(reg_metrics),
      tf_summary_dir, None,
      Some((final_predictions, pred_time_lags_test))
    )

    helios.write_processed_predictions(
      dtfutils.toDoubleSeq(final_predictions).toSeq,
      final_targets,
      dtfutils.toDoubleSeq(pred_time_lags_test).toSeq,
      tf_summary_dir/("scatter_test-"+DateTime.now().toString("YYYY-MM-dd-HH-mm")+".csv"))


    helios.ExperimentResult(
      experiment_config,
      dataset.training_dataset,
      dataset.test_dataset,
      results
    )

  }


  def exp_cdt_tuning(
    arch: Layer[Output, (Output, Output)],
    hyper_params: List[String],
    loss_func_generator: dtflearn.tunable_tf_model.HyperParams => Layer[((Output, Output), Output), Output],
    fitness_func: DataPipe2[(Tensor, Tensor), Tensor, Double],
    hyper_prior: Map[String, ContinuousRVWithDistr[Double, ContinuousDistr[Double]]],
    hyp_mapping: Option[Map[String, Encoder[Double, Double]]] = None,
    iterations: Int                                           = 150000,
    iterations_tuning: Int                                    = 20000,
    num_samples: Int                                          = 20,
    hyper_optimizer: String                                   = "gs",
    miniBatch: Int                                            = 32,
    optimizer: tf.train.Optimizer                             = tf.train.AdaDelta(0.001),
    year_range: Range                                         = 2011 to 2017,
    test_year: Int                                            = 2015,
    sw_threshold: Double                                      = 700d,
    quantity: Int                                             = OMNIData.Quantities.V_SW,
    deltaT: (Int, Int)                                        = (48, 72),
    deltaTFTE: Int                                            = 5,
    fteStep: Int                                              = 1,
    latitude_limit: Double                                    = 40d,
    divergence: helios.learn.cdt_loss.Divergence              = helios.learn.cdt_loss.KullbackLeibler,
    log_scale_fte: Boolean                                    = false,
    log_scale_omni: Boolean                                   = false,
    conv_flag: Boolean                                        = false,
    fte_data_path: Path                                       = home/'Downloads/'fte,
    summary_top_dir: Path                                     = home/'tmp,
    hyp_opt_iterations: Option[Int]                           = Some(5))
  : helios.Experiment[ModelRunTuning, FTExperiment.Config] = {


    val mo_flag: Boolean = true
    val prob_timelags: Boolean = true


    val sum_dir_prefix = if(conv_flag) "fte_omni_conv" else "fte_omni"

    val dt = DateTime.now()

    val summary_dir_index = {
      if(mo_flag) sum_dir_prefix+"_mo_tl_"+dt.toString("YYYY-MM-dd-HH-mm")
      else sum_dir_prefix+"_tl_"+dt.toString("YYYY-MM-dd-HH-mm")
    }

    val tf_summary_dir     = summary_top_dir/summary_dir_index

    val (test_start, test_end) = (
      new DateTime(test_year, 1, 1, 0, 0),
      new DateTime(test_year, 12, 31, 23, 59)
    )

    val (start, end) = (
      new DateTime(year_range.min, 1, 1, 0, 0),
      new DateTime(year_range.max, 12, 31, 23, 59))

    val tt_partition = DataPipe((p: (DateTime, (Tensor, Tensor))) =>
      if (p._1.isAfter(test_start) && p._1.isBefore(test_end))
        false
      else
        true
    )

    println("\nProcessing FTE Data")
    val fte_data = load_fte_data(
      fte_data_path, carrington_rotations,
      log_scale_fte, start, end)(deltaTFTE, fteStep, latitude_limit, conv_flag)

    println("Processing OMNI solar wind data")
    val omni_data = load_solar_wind_data(start, end)(deltaT, log_scale_omni, quantity)

    println("Constructing joined data set")
    val dataset = fte_data.join(omni_data).partition(tt_partition)

    val causal_window = dataset.training_dataset.data.head._2._2.shape(0)

    val input_shape = dataset.training_dataset.data.head._2._1.shape

    val data_size = dataset.training_dataset.size


    println("Scaling data attributes")
    val (scaled_data, scalers): SC_DATA = scale_dataset(
      dataset.copy(
        training_dataset = dataset.training_dataset.map((p: (DateTime, (Tensor, Tensor))) => p._2),
        test_dataset = dataset.test_dataset.map((p: (DateTime, (Tensor, Tensor))) => p._2)
      )
    )

    val train_config_tuning =
      dtflearn.tunable_tf_model.ModelFunction.hyper_params_to_dir >>
        DataPipe((p: Path) => dtflearn.model.trainConfig(
          p, optimizer,
        dtflearn.rel_loss_change_stop(0.005, iterations_tuning),
        Some(
          dtflearn.model._train_hooks(
            p,
            iterations_tuning/3,
            iterations_tuning/3,
            iterations_tuning)))
        )

    val train_config_test = DataPipe[dtflearn.tunable_tf_model.HyperParams, dtflearn.model.Config](_ =>
      dtflearn.model.trainConfig(
        summaryDir = tf_summary_dir,
        stopCriteria = dtflearn.rel_loss_change_stop(0.005, iterations),
        trainHooks = Some(
          dtflearn.model._train_hooks(
            tf_summary_dir,
            iterations/3,
            iterations/3,
            iterations/2)
        ))
    )


    val tf_data_ops = dtflearn.model.data_ops(10, miniBatch, 10, data_size/5)

    val stackOperation = DataPipe[Iterable[Tensor], Tensor](bat =>
      tfi.stack(bat.toSeq, axis = 0)
    )

    val tunableTFModel: TunableTFModel[
      Tensor, Output, DataType.Aux[Double], DataType, Shape, (Output, Output), (Tensor, Tensor),
      Tensor, Output, DataType.Aux[Double], DataType, Shape, Output] =
      dtflearn.tunable_tf_model(
        loss_func_generator, hyper_params,
        scaled_data.training_dataset,
        fitness_func,
        arch,
        (FLOAT64, input_shape),
        (FLOAT64, Shape(causal_window)),
        tf.learn.Cast("TrainInput", FLOAT64),
        train_config_tuning(tf_summary_dir),
        data_split_func = Some(DataPipe[(Tensor, Tensor), Boolean](_ => scala.util.Random.nextGaussian() <= 0.7)),
        data_processing = tf_data_ops,
        inMemory = false,
        concatOpI = Some(stackOperation),
        concatOpT = Some(stackOperation)
      )



    val gs = hyper_optimizer match {
      case "csa" =>
        new CoupledSimulatedAnnealing[tunableTFModel.type](
          tunableTFModel, hyp_mapping).setMaxIterations(
          hyp_opt_iterations.getOrElse(5)
        )

      case "gs"  => new GridSearch[tunableTFModel.type](tunableTFModel)


      case "cma" => new CMAES[tunableTFModel.type](
        tunableTFModel,
        hyper_params,
        learning_rate = 0.8,
        hyp_mapping
      ).setMaxIterations(hyp_opt_iterations.getOrElse(5))

      case _     => new GridSearch[tunableTFModel.type](tunableTFModel)
    }

    gs.setPrior(hyper_prior)

    gs.setNumSamples(num_samples)



    println("--------------------------------------------------------------------")
    println("Initiating model tuning")
    println("--------------------------------------------------------------------")

    val (_, config) = gs.optimize(hyper_prior.mapValues(_.draw))

    println("--------------------------------------------------------------------")
    println("\nModel tuning complete")
    println("Chosen configuration:")
    pprint.pprintln(config)
    println("--------------------------------------------------------------------")

    println("Training final model based on chosen configuration")

    write(
      tf_summary_dir/"state.csv",
      config.keys.mkString(start = "", sep = ",", end = "\n") +
        config.values.mkString(start = "", sep = ",", end = "")
    )

    val model_function = dtflearn.tunable_tf_model.ModelFunction.from_loss_generator[
      Tensor, Output, DataType.Aux[Double], DataType, Shape, (Output, Output), (Tensor, Tensor),
      Tensor, Output, DataType.Aux[Double], DataType, Shape, Output](
      loss_func_generator, arch, (FLOAT64, input_shape),
      (FLOAT64, Shape(causal_window)),
      tf.learn.Cast("TrainInput", FLOAT64),
      train_config_test, tf_data_ops,
      inMemory = false,
      concatOpI = Some(stackOperation),
      concatOpT = Some(stackOperation)
    )

    val best_model = model_function(config)

    best_model.train(scaled_data.training_dataset)

    val extract_features = (p: (Tensor, Tensor)) => p._1
    val model_predictions_test = best_model.infer_coll(scaled_data.test_dataset.map(extract_features))

    val predictions = model_predictions_test match {
      case Left(tensor) => tensor
      case Right(collection) => timelag.utils.collect_predictions(collection)
    }

    val nTest = scaled_data.test_dataset.size

    val index_times = Tensor(
      (0 until causal_window).map(_.toDouble)
    ).reshape(
      Shape(causal_window)
    )

    val pred_time_lags_test: Tensor = if(prob_timelags) {
      val unsc_probs = predictions._2

      unsc_probs.topK(1)._2.reshape(Shape(nTest)).cast(FLOAT64)

    } else predictions._2

    val pred_targets: Tensor = if (mo_flag) {
      val all_preds =
        if (prob_timelags) scalers._2.i(predictions._1)
        else scalers._2.i(predictions._1)

      val repeated_times = tfi.stack(Seq.fill(causal_window)(pred_time_lags_test.floor), axis = -1)

      val conv_kernel = repeated_times.subtract(index_times).square.multiply(-1.0).exp.floor

      all_preds.multiply(conv_kernel).sum(axes = 1).divide(conv_kernel.sum(axes = 1))
    } else {
      scalers._2(0).i(predictions._1)
    }

    val test_labels = scaled_data.test_dataset.data.map(_._2).map(t => dtfutils.toDoubleSeq(t).toSeq).toSeq

    val actual_targets = test_labels.zipWithIndex.map(zi => {
      val (z, index) = zi
      val time_lag = pred_time_lags_test(index).scalar.asInstanceOf[Double].toInt

      z(time_lag)
    })

    val (final_predictions, final_targets) =
      if(log_scale_omni) (pred_targets.exp, actual_targets.map(math.exp))
      else (pred_targets, actual_targets)

    val reg_metrics = new RegressionMetricsTF(final_predictions, final_targets)

    val experiment_config = FTExperiment.Config(
      FTExperiment.FTEConfig(
        (year_range.min, year_range.max),
        deltaTFTE, fteStep, latitude_limit,
        log_scale_fte),
      FTExperiment.OMNIConfig(
        deltaT,
        log_scale_omni)
    )

    val results = helios.TunedModelRun(
      (scaled_data, scalers),
      best_model, None,
      Some(reg_metrics),
      tf_summary_dir, None,
      Some((final_predictions, pred_time_lags_test))
    )

    helios.write_processed_predictions(
      dtfutils.toDoubleSeq(final_predictions).toSeq,
      final_targets,
      dtfutils.toDoubleSeq(pred_time_lags_test).toSeq,
      tf_summary_dir/("scatter_test-"+DateTime.now().toString("YYYY-MM-dd-HH-mm")+".csv"))


    helios.Experiment(
      experiment_config,
      results
    )

  }

  def exp_single_output(
    num_neurons: Seq[Int] = Seq(30, 30),
    activation_func: Int => Activation = timelag.utils.getReLUAct(1),
    optimizer: tf.train.Optimizer = tf.train.Adam(0.001),
    year_range: Range = 2011 to 2017,
    test_year: Int = 2015,
    sw_threshold: Double = 700d,
    deltaT: Int = 96,
    deltaTFTE: Int = 5,
    fteStep: Int = 1,
    latitude_limit: Double = 40d,
    reg: Double = 0.0001,
    log_scale_fte: Boolean = false,
    log_scale_omni: Boolean = false,
    conv_flag: Boolean = false,
    iterations: Int = 10000,
    miniBatch: Int = 32,
    fte_data_path: Path = home/'Downloads/'fte,
    summary_top_dir: Path = home/'tmp) = {


    val sum_dir_prefix = "fte_omni"

    val dt = DateTime.now()

    val summary_dir_index  = sum_dir_prefix+s"_so_${deltaT}_"+dt.toString("YYYY-MM-dd-HH-mm")

    val tf_summary_dir     = summary_top_dir/summary_dir_index

    val (test_start, test_end) = (
      new DateTime(test_year, 1, 1, 0, 0),
      new DateTime(test_year, 12, 31, 23, 59)
    )

    val (start, end) = (
      new DateTime(year_range.min, 1, 1, 0, 0),
      new DateTime(year_range.max, 12, 31, 23, 59))


    if(FTExperiment.fte_data.size == 0 ||
      FTExperiment.config.fte_config != FTExperiment.FTEConfig(
        (year_range.min, year_range.max), deltaT,
        fteStep, latitude_limit, log_scale_fte)
    ) {

      println("\nProcessing FTE Data")

      FTExperiment.fte_data = load_fte_data(
        fte_data_path, carrington_rotations,
        log_scale_fte, start, end)(
        deltaTFTE, fteStep,
        latitude_limit, conv_flag)

      FTExperiment.config = FTExperiment.config.copy(fte_config = FTExperiment.FTEConfig(
        (year_range.min, year_range.max),
        deltaTFTE, fteStep, latitude_limit,
        log_scale_fte))



    } else {
      println("\nUsing cached FTE data sets")
    }


    if(
      FTExperiment.omni_data.size == 0 ||
        FTExperiment.config.omni_config != FTExperiment.OMNIConfig((deltaT, 1), log_scale_omni)) {

      println("Processing OMNI solar wind data")
      FTExperiment.omni_data = load_solar_wind_data(start, end)((deltaT, 1), log_scale_omni)

      FTExperiment.config = FTExperiment.config.copy(
        omni_config = FTExperiment.OMNIConfig((deltaT, 1), log_scale_omni)
      )

    } else {
      println("\nUsing cached OMNI data set")
    }


    val tt_partition = DataPipe((p: (DateTime, (Tensor, Tensor))) =>
      if (p._1.isAfter(test_start) && p._1.isBefore(test_end))
        false
      else
        true
    )

    println("Constructing joined data set")
    val dataset = FTExperiment.fte_data.join(FTExperiment.omni_data).partition(tt_partition)



    val input_dim = dataset.training_dataset.data.head._2._1.shape

    val input = tf.learn.Input(FLOAT64, Shape(-1) ++ input_dim)

    val trainInput = tf.learn.Input(FLOAT64, Shape(-1))

    val trainingInputLayer = tf.learn.Cast("TrainInput", FLOAT64)

    val summariesDir = java.nio.file.Paths.get(tf_summary_dir.toString())

    val num_pred_dims = 1

    val (net_layer_sizes, layer_shapes, layer_parameter_names, layer_datatypes) =
      timelag.utils.get_ffnet_properties(-1, num_pred_dims, num_neurons)



    val filter_depths = Seq(
      Seq(4, 4, 4, 4),
      Seq(2, 2, 2, 2),
      Seq(1, 1, 1, 1)
    )

    val activation = DataPipe[String, Layer[Output, Output]]((s: String) => tf.learn.ReLU(s, 0.01f))

    //Prediction architecture
    val architecture = if (conv_flag) {
      tf.learn.Cast("Cast/Input", FLOAT32) >>
        dtflearn.inception_unit(
          1, filter_depths.head,
          activation, use_batch_norm = true)(1) >>
        tf.learn.MaxPool(s"MaxPool_1", Seq(1, 3, 3, 1), 2, 2, SameConvPadding) >>
        dtflearn.inception_unit(
          filter_depths.head.sum, filter_depths(1),
          activation, use_batch_norm = true)(2) >>
        tf.learn.MaxPool(s"MaxPool_2", Seq(1, 3, 3, 1), 2, 2, SameConvPadding) >>
        dtflearn.inception_unit(
          filter_depths(1).sum, filter_depths.last,
          activation, use_batch_norm = true)(3) >>
        tf.learn.MaxPool(s"MaxPool_3", Seq(1, 3, 3, 1), 2, 2, SameConvPadding) >>
        tf.learn.Flatten("FlattenFeatures") >>
        dtflearn.feedforward_stack(activation_func, FLOAT64)(net_layer_sizes.tail)
    } else {
      dtflearn.feedforward_stack(activation_func,FLOAT64)(net_layer_sizes.tail)
    }



    val loss = tf.learn.L2Loss("Loss/L2") >>
      tf.learn.ScalarSummary("Loss/Error", "Error") >>
      L2Regularization(layer_parameter_names, layer_datatypes, layer_shapes, reg) >>
      tf.learn.ScalarSummary("Loss/Net", "ModelLoss")


    println("Scaling data attributes")
    val (scaled_data, scalers): SC_DATA = scale_dataset(
      dataset.copy(
        training_dataset = dataset.training_dataset.map((p: (DateTime, (Tensor, Tensor))) => p._2),
        test_dataset = dataset.test_dataset.map((p: (DateTime, (Tensor, Tensor))) => p._2)
      )
    )


    val train_data_tf = {
      scaled_data.training_dataset
        .build[
        (Tensor, Tensor),
        (Output, Output),
        (DataType, DataType),
        (DataType, DataType),
        (Shape, Shape)](
        Left(identityPipe[(Tensor, Tensor)]),
        (FLOAT64, FLOAT64),
        (input_dim, Shape()))
        .repeat()
        .shuffle(10)
        .batch(miniBatch)
        .prefetch(10)
    }

    val (model, estimator) = dtflearn.build_tf_model(
      architecture, input, trainInput, trainingInputLayer,
      loss, optimizer, summariesDir,
      dtflearn.rel_loss_change_stop(0.005, iterations))(
      train_data_tf)


    val nTest = scaled_data.test_dataset.size

    val predictions: Tensor = dtfutils.buffered_preds[
      Tensor, Output, DataType, Shape, Output,
      Tensor, Output, DataType, Shape, Output,
      Tensor, Tensor, Tensor](
      estimator, tfi.stack(scaled_data.test_dataset.data.toSeq.map(_._1), axis = 0),
      500, nTest)


    val pred_targets = scalers._2.i(predictions).reshape(Shape(nTest))

    val stacked_targets = tfi.stack(scaled_data.test_dataset.data.toSeq.map(_._2), axis = 0)

    val reg_metrics = if(log_scale_omni) {
      new RegressionMetricsTF(pred_targets.exp, stacked_targets.exp)
    } else {
      new RegressionMetricsTF(pred_targets, stacked_targets)
    }


    val experiment_config = helios.ExperimentConfig(
      multi_output = false,
      probabilistic_time_lags = false,
      "single-output")

    val results = helios.SupervisedModelRun[Tensor, Tensor, Tensor, Output](
      (scaled_data, scalers),
      model, estimator, None,
      Some(reg_metrics),
      tf_summary_dir, None,
      Some(pred_targets)
    )

    helios.ExperimentResult(
      experiment_config,
      dataset.training_dataset,
      dataset.test_dataset,
      results
    )



  }

}
