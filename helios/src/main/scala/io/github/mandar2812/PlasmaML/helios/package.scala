package io.github.mandar2812.PlasmaML

import ammonite.ops._
import org.joda.time._
import com.sksamuel.scrimage.Image
import io.github.mandar2812.dynaml.pipes._
import io.github.mandar2812.dynaml.DynaMLPipe._
import io.github.mandar2812.dynaml.models.{TFModel, TunableTFModel}
import io.github.mandar2812.dynaml.evaluation.{ClassificationMetricsTF, RegressionMetricsTF}
import io.github.mandar2812.dynaml.tensorflow._
import io.github.mandar2812.dynaml.tensorflow.data.{DataSet, TFDataSet}
import io.github.mandar2812.dynaml.tensorflow.implicits._
import io.github.mandar2812.PlasmaML.helios.core._
import io.github.mandar2812.PlasmaML.helios.data._
import io.github.mandar2812.PlasmaML.dynamics.mhd._
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.learn.{Mode, StopCriteria, SupervisedTrainableModel}
import org.platanios.tensorflow.api.learn.layers.{Compose, Layer, Loss}
import org.platanios.tensorflow.api.ops.training.optimizers.Optimizer
import org.platanios.tensorflow.api.types.DataType
import spire.math.UByte
import _root_.io.github.mandar2812.dynaml.graphics.charts.Highcharts._
import breeze.stats.distributions.ContinuousDistr
import _root_.io.github.mandar2812.dynaml.probability.ContinuousRVWithDistr
import org.platanios.tensorflow.api.learn.estimators.Estimator
import _root_.io.github.mandar2812.dynaml.optimization.{CMAES, CoupledSimulatedAnnealing, GridSearch}
import _root_.io.github.mandar2812.dynaml.tensorflow.utils.MinMaxScalerTF

/**
  * <h3>Helios</h3>
  *
  * The top level package for the helios module.
  *
  * Contains methods for carrying out ML experiments
  * on data sets associated with helios.
  *
  * @author mandar2812
  * */
package object helios {

  /**
    * Calculate RMSE of a tensorflow based estimator.
    * */
  def calculate_rmse(
    n: Int, n_part: Int)(
    labels_mean: Tensor, labels_stddev: Tensor)(
    images: Tensor, labels: Tensor)(infer: Tensor => Tensor): Float = {

    def accuracy(im: Tensor, lab: Tensor): Float = {
      infer(im)
        .multiply(labels_stddev)
        .add(labels_mean)
        .subtract(lab).cast(FLOAT64)
        .square.mean().scalar
        .asInstanceOf[Float]
    }

    val num_elem: Int = n/n_part

    math.sqrt((0 until n_part).map(i => {

      val (lower_index, upper_index) = (i*num_elem, if(i == n_part-1) n else (i+1)*num_elem)

      accuracy(images(lower_index::upper_index, ::, ::, ::), labels(lower_index::upper_index))
    }).sum/num_elem).toFloat
  }

  object learn {

    val upwind_1d: UpwindTF.type                              = UpwindTF

    /*
    * NN Architectures
    *
    * */
    val cnn_goes_v1: Layer[Output, Output]                    = Arch.cnn_goes_v1
    val cnn_goes_v1_1: Layer[Output, Output]                  = Arch.cnn_goes_v1_1
    val cnn_goes_v1_2: Layer[Output, Output]                  = Arch.cnn_goes_v1_2
    val cnn_sw_v1: Layer[Output, Output]                      = Arch.cnn_sw_v1
    val cnn_sw_dynamic_timescales_v1: Layer[Output, Output]   = Arch.cnn_sw_dynamic_timescales_v1
    val cnn_xray_class_v1: Layer[Output, Output]              = Arch.cnn_xray_class_v1

    def cnn_sw_v2(sliding_window: Int): Compose[Output, Output, Output] =
      Arch.cnn_sw_v2(sliding_window, mo_flag = true, prob_timelags = true)

    /*
    * Loss Functions
    * */
    val weightedL2FluxLoss: WeightedL2FluxLoss.type            = WeightedL2FluxLoss
    val rBFWeightedSWLoss: RBFWeightedSWLoss.type              = RBFWeightedSWLoss
    val dynamicRBFSWLoss: DynamicRBFSWLoss.type                = DynamicRBFSWLoss
    val cdt_loss: CausalDynamicTimeLag.type                    = CausalDynamicTimeLag
    val cdt_i: CausalDynamicTimeLagI.type                      = CausalDynamicTimeLagI
    val cdt_ii: CausalDynamicTimeLagII.type                    = CausalDynamicTimeLagII
    val cdt_loss_so: CausalDynamicTimeLagSO.type               = CausalDynamicTimeLagSO
    val cdt_poisson_loss: WeightedTimeSeriesLossPoisson.type   = WeightedTimeSeriesLossPoisson
    val cdt_gaussian_loss: WeightedTimeSeriesLossGaussian.type = WeightedTimeSeriesLossGaussian
    val cdt_beta_loss: WeightedTimeSeriesLossGaussian.type     = WeightedTimeSeriesLossGaussian
  }

  /**
    * A model run contains a tensorflow model/estimator as
    * well as its training/test data set and meta data regarding
    * the training/evaluation process.
    *
    * */
  sealed trait ModelRun {

    type DATA_PATTERN
    type SCALERS
    type MODEL
    type ESTIMATOR

    val summary_dir: Path

    val data_and_scales: (TFDataSet[DATA_PATTERN], SCALERS)

    val metrics_train: Option[RegressionMetricsTF]

    val metrics_test: Option[RegressionMetricsTF]

    val model: MODEL

    val estimator: ESTIMATOR

  }

  case class SupervisedModelRun[X, T, ModelOutput, ModelOutputSym](
    data_and_scales: (TFDataSet[(X, T)], (ReversibleScaler[X], ReversibleScaler[T])),
    model: SupervisedTrainableModel[
      Tensor, Output, DataType, Shape, ModelOutputSym,
      Tensor, Output, DataType, Shape, Output],
    estimator: Estimator[
      Tensor, Output, DataType, Shape, ModelOutputSym,
      (Tensor, Tensor), (Output, Output), (DataType, DataType), (Shape, Shape),
      (ModelOutputSym, Output)],
    metrics_train: Option[RegressionMetricsTF],
    metrics_test: Option[RegressionMetricsTF],
    summary_dir: Path,
    training_preds: Option[ModelOutput],
    test_preds: Option[ModelOutput]) extends ModelRun {

    override type DATA_PATTERN = (X, T)

    override type SCALERS = (ReversibleScaler[X], ReversibleScaler[T])

    override type MODEL = SupervisedTrainableModel[
      Tensor, Output, DataType, Shape, ModelOutputSym,
      Tensor, Output, DataType, Shape, Output]

    override type ESTIMATOR = Estimator[
      Tensor, Output, DataType, Shape, ModelOutputSym,
      (Tensor, Tensor), (Output, Output), (DataType, DataType), (Shape, Shape),
      (ModelOutputSym, Output)]
  }

  case class TunedModelRun[
  IT, IO, IDA, ID, IS, I, ITT,
  TT, TO, TDA, TD, TS, T](
    data_and_scales: (TFDataSet[(IT, TT)], (ReversibleScaler[IT], ReversibleScaler[TT])),
    model: TFModel[IT, IO, IDA, ID, IS, I, ITT, TT, TO, TDA, TD, TS, T],
    metrics_train: Option[RegressionMetricsTF],
    metrics_test: Option[RegressionMetricsTF],
    summary_dir: Path,
    training_preds: Option[ITT],
    test_preds: Option[ITT],
    training_outputs: Option[ITT] = None,
    test_outputs: Option[ITT]     = None) extends
    ModelRun {

    override type DATA_PATTERN = (IT, TT)

    override type SCALERS = (ReversibleScaler[IT], ReversibleScaler[TT])

    override type MODEL = TFModel[IT, IO, IDA, ID, IS, I, ITT, TT, TO, TDA, TD, TS, T]

    override type ESTIMATOR = dtflearn.SupEstimatorTF[IT, IO, ID, IS, I, TT, TO, TD, TS, T]

    override val estimator: ESTIMATOR = model.estimator
  }

  sealed trait Config

  case class ExperimentConfig(
    multi_output: Boolean,
    probabilistic_time_lags: Boolean,
    timelag_prediction: String) extends Config

  case class ImageExpConfig(
    image_sources: Seq[SolarImagesSource],
    image_preprocess: Seq[DataPipe[Image, Image]],
    image_history: Int,
    image_history_downsampling: Int,
    multi_output: Boolean,
    probabilistic_time_lags: Boolean,
    timelag_prediction: String,
    input_shape: Shape,
    targets_shape: Shape,
    divergence: Option[helios.learn.cdt_loss.Divergence] = None,
    target_prob: Option[helios.learn.cdt_loss.TargetDistribution] = None,
    reg_type: Option[String] = Some("L2")
  ) extends Config

  case class ExperimentResult[DATA, X, Y, ModelOutput, ModelOutputSym](
    config: ExperimentConfig,
    train_data: DATA,
    test_data: DATA,
    results: SupervisedModelRun[X, Y, ModelOutput, ModelOutputSym])

  case class Experiment[Run <: ModelRun, Conf <: Config](
    config: Conf,
    results: Run
  )


  type ModelRunTuning = TunedModelRun[
    Tensor, Output, DataType.Aux[UByte], DataType, Shape, (Output, Output), (Tensor, Tensor),
    Tensor, Output, DataType.Aux[Float], DataType, Shape, Output]


  def process_predictions(
    predictions: (Tensor, Tensor),
    time_window: Int,
    multi_output: Boolean = true,
    probabilistic_time_lags: Boolean = true,
    timelag_pred_strategy: String = "mode",
    scale_outputs: Option[MinMaxScalerTF] = None): (Tensor, Tensor) = {

    val index_times = Tensor(
      (0 until time_window).map(_.toDouble)
    ).reshape(
      Shape(time_window)
    )

    val pred_time_lags = if(probabilistic_time_lags) {
      val unsc_probs = predictions._2

      if (timelag_pred_strategy == "mode") unsc_probs.topK(1)._2.reshape(Shape(predictions._1.shape(0))).cast(FLOAT64)
      else unsc_probs.multiply(index_times).sum(axes = 1)

    } else predictions._2

    val pred_targets: Tensor = if (multi_output) {

      val all_preds =
        if (scale_outputs.isDefined) scale_outputs.get.i(predictions._1)
        else predictions._1

      val repeated_times = tfi.stack(Seq.fill(time_window)(pred_time_lags.floor), axis = -1)

      val conv_kernel = repeated_times.subtract(index_times).square.multiply(-1.0).exp.floor

      all_preds.multiply(conv_kernel).sum(axes = 1).divide(conv_kernel.sum(axes = 1))

    } else {

      if (scale_outputs.isDefined) {
        val scaler = scale_outputs.get
        scaler(0).i(predictions._1)
      } else predictions._1

    }

    (pred_targets, pred_time_lags)

  }

  /**
    * Train a Neural architecture on a
    * processed data set.
    *
    * @param collated_data Data set of temporally joined
    *                      image paths and GOES X-Ray fluxes.
    *                      This is generally the output after
    *                      executing [[collate_goes_data_range()]]
    *                      with the relevant parameters.
    * @param tt_partition A function which splits the data set
    *                     into train and test sections, based on
    *                     any Boolean function. If the function
    *                     returns true then the instance falls into
    *                     the training set else the test set
    * @param resample If set to true, the training data is resampled
    *                 to balance the occurrence of high flux and low
    *                 flux events.
    * @param longWavelength If set to true, predict long wavelength
    *                       GOES X-Ray flux, else short wavelength,
    *                       defaults to short wavelength.
    * @param tempdir A working directory where the results will be
    *                archived, defaults to user_home_dir/tmp. The model
    *                checkpoints and other results will be stored inside
    *                another directory created in tempdir.
    * @param results_id The suffix added the results/checkpoints directory name.
    * @param max_iterations The maximum number of iterations that the
    *                       network must be trained for.
    * @param arch The neural architecture to train, defaults to [[Arch.cnn_goes_v1]]
    * @param lossFunc The loss function which will be used to guide the training
    *                 of the architecture, defaults to [[tf.learn.L2Loss]]
    *
    * */
  def run_experiment_goes(
    collated_data: Stream[(DateTime, (Path, (Double, Double)))],
    tt_partition: ((DateTime, (Path, (Double, Double)))) => Boolean,
    resample: Boolean = false, longWavelength: Boolean = false)(
    results_id: String, max_iterations: Int,
    tempdir: Path = home/"tmp",
    arch: Layer[Output, Output] = learn.cnn_goes_v1,
    lossFunc: Loss[(Output, Output)] = tf.learn.L2Loss("Loss/L2")) = {

    val resDirName = if(longWavelength) "helios_goes_long_"+results_id else "helios_goes_"+results_id

    val tf_summary_dir = tempdir/resDirName


    val checkpoints =
      if (exists(tf_summary_dir)) ls! tf_summary_dir |? (_.isFile) |? (_.segments.last.contains("model.ckpt-"))
      else Seq()

    val checkpoint_max =
      if(checkpoints.isEmpty) 0
      else (checkpoints | (_.segments.last.split("-").last.split('.').head.toInt)).max

    val iterations = if(max_iterations > checkpoint_max) max_iterations - checkpoint_max else 0


    /*
    * After data has been joined/collated,
    * start loading it into tensors
    *
    * */

    val dataSet = helios.data.prepare_helios_goes_data_set(
      collated_data,
      tt_partition,
      scaleDownFactor = 2,
      resample)

    val trainImages = tf.data.TensorSlicesDataset(dataSet.trainData)

    val targetIndex = if(longWavelength) 1 else 0

    val train_labels = dataSet.trainLabels(::, targetIndex)

    val labels_mean = train_labels.mean()

    val labels_stddev = train_labels.subtract(labels_mean).square.mean().sqrt

    val norm_train_labels = train_labels.subtract(labels_mean).divide(labels_stddev)

    val trainLabels = tf.data.TensorSlicesDataset(norm_train_labels)

    //val trainWeights = tf.data.TensorSlicesDataset(norm_train_labels.exp)

    val trainData =
      trainImages.zip(trainLabels)
        .repeat()
        .shuffle(10000)
        .batch(64)
        .prefetch(10)

    /*
    * Start building tensorflow network/graph
    * */
    println("Building the regression model.")
    val input = tf.learn.Input(
      UINT8,
      Shape(
        -1,
        dataSet.trainData.shape(1),
        dataSet.trainData.shape(2),
        dataSet.trainData.shape(3))
    )

    val trainInput = tf.learn.Input(FLOAT64, Shape(-1))

    val trainingInputLayer = tf.learn.Cast("TrainInput", INT64)

    val loss = lossFunc >>
      tf.learn.Mean("Loss/Mean") >>
      tf.learn.ScalarSummary("Loss", "ModelLoss")

    val optimizer = tf.train.AdaGrad(0.002)

    val summariesDir = java.nio.file.Paths.get(tf_summary_dir.toString())

    //Now create the model
    val (model, estimator) = tf.createWith(graph = Graph()) {
      val model = tf.learn.Model.supervised(
        input, arch, trainInput, trainingInputLayer,
        loss, optimizer)

      println("Training the linear regression model.")

      val estimator = tf.learn.FileBasedEstimator(
        model,
        tf.learn.Configuration(Some(summariesDir)),
        tf.learn.StopCriteria(maxSteps = Some(iterations)),
        Set(
          tf.learn.StepRateLogger(log = false, summaryDir = summariesDir, trigger = tf.learn.StepHookTrigger(5000)),
          tf.learn.SummarySaver(summariesDir, tf.learn.StepHookTrigger(5000)),
          tf.learn.CheckpointSaver(summariesDir, tf.learn.StepHookTrigger(5000))),
        tensorBoardConfig = tf.learn.TensorBoardConfig(summariesDir, reloadInterval = 5000))

      estimator.train(() => trainData, tf.learn.StopCriteria(maxSteps = Some(iterations)))

      (model, estimator)
    }


    //Create  MetricsTF instance
    //First calculate and re-normalize the test predictions

    val predictions = estimator.infer(() => dataSet.testData)
      .multiply(labels_stddev)
      .add(labels_mean)
      .reshape(Shape(dataSet.nTest))

    val targets = dataSet.testLabels(::, targetIndex)
    val metrics = new RegressionMetricsTF(predictions, targets)

    val (predictions_seq, targets_seq) = (
      predictions.entriesIterator.map(_.asInstanceOf[Float]).map(GOESData.getFlareClass).toSeq,
      targets.entriesIterator.map(_.asInstanceOf[Float]).map(GOESData.getFlareClass).toSeq)

    val preds_one_hot = dtf.tensor_i32(dataSet.nTest)(predictions_seq:_*).oneHot(depth = 4)
    val targets_one_hot = dtf.tensor_i32(dataSet.nTest)(targets_seq:_*).oneHot(depth = 4)

    val metrics_class = new ClassificationMetricsTF(4, preds_one_hot, targets_one_hot)

    (model, estimator, metrics, metrics_class, tf_summary_dir, labels_mean, labels_stddev, collated_data)
  }

  def write_processed_predictions(
    preds: Seq[Double],
    targets: Seq[Double],
    timelags: Seq[Double],
    file: Path): Unit = {

    //write predictions and ground truth to a csv file

    val resScatter = preds.zip(targets).zip(timelags).map(p => Seq(p._1._1, p._1._2, p._2))

    streamToFile(file.toString())(resScatter.map(_.mkString(",")).toStream)
  }

  def visualise_cdt_results(results_path: Path): Unit = {

    val split_line = DataPipe[String, Array[Double]](_.split(',').map(_.toDouble))

    val process_pipe = DataPipe[Path, String](_.toString()) >
      fileToStream >
      StreamDataPipe(split_line)

    val scatter_data = process_pipe(results_path)

    regression(scatter_data.map(p => (p.head, p(1))))
    hold()
    title("Scatter Plot: Model Predictions")
    xAxis("Prediction")
    yAxis("Actual Target")
    unhold()

    scatter(scatter_data.map(p => (p.head, p.last)))
    hold()
    title("Scatter Plot: Output Timelag Relationship")
    xAxis("Prediction")
    yAxis("Predicted Time Lag")
    unhold()

  }

  def run_unsupervised_experiment(
    collated_data: HELIOS_IMAGE_DATA,
    tt_partition: IMAGE_PATTERN => Boolean,
    resample: Boolean = false,
    preprocess_image: DataPipe[Image, Image] = identityPipe[Image],
    image_to_bytearr: DataPipe[Image, Array[Byte]] = DataPipe((i: Image) => i.argb.flatten.map(_.toByte)),
    processed_image_size: (Int, Int) = (-1, -1),
    num_channels_image: Int = 4,
    image_history: Int = 0,
    image_history_downsampling: Int = 1)(
    results_id: String,
    stop_criteria: StopCriteria,
    tempdir: Path = home/"tmp",
    arch: Layer[Output, (Output, Output)],
    lossFunc: Layer[(Output, (Output, Output)), Output],
    optimizer: Optimizer = tf.train.AdaDelta(0.001),
    miniBatchSize: Int = 16,
    reuseExistingModel: Boolean = false) = {

    //The directories to write model parameters and summaries.
    val resDirName = if(reuseExistingModel) results_id else "helios_"+results_id

    val tf_summary_dir = tempdir/resDirName

    val load_image_into_tensor = read_image >
      preprocess_image >
      image_to_bytearr >
      DataPipe(
        dtf.tensor_from_buffer(
          dtype = "UINT8", processed_image_size._1,
          processed_image_size._2,
          num_channels_image) _
      )


    val dataSet: TF_IMAGE_DATA = helios.data.prepare_helios_data_set(
      collated_data,
      load_image_into_tensor,
      tt_partition,
      image_history,
      image_history_downsampling)

    val data_shape = Shape(
      processed_image_size._1,
      processed_image_size._2,
      num_channels_image*(image_history_downsampling + 1)
    )

    val train_data = dataSet.training_dataset.build[Tensor, Output, DataType.Aux[UByte], DataType, Shape](
      Left(identityPipe[Tensor]),
      dataType = UINT8, data_shape)
      .repeat()
      .shuffle(1000)
      .batch(miniBatchSize)
      .prefetch(10)


    /*
    * Start building tensorflow network/graph
    * */
    println("Building the unsupervised model.")
    val input = tf.learn.Input(
      UINT8, Shape(-1) ++ data_shape
    )


    val split_stacked_images = new Layer[Output, Seq[Output]]("UnstackImages") {
      override val layerType: String = "UnstackImage"

      override protected def _forward(input: Output)(implicit mode: Mode): Seq[Output] = {

        tf.splitEvenly(input, image_history_downsampling + 1, -1)
      }
    }

    val write_images = (image_name: String) =>
      dtflearn.seq_layer(
        "WriteImagesTS",
        Seq.tabulate(image_history_downsampling + 1)(
          i => tf.learn.ImageSummary(s"ImageSummary_$i", s"${image_name}_$i", maxOutputs = miniBatchSize/2))
      ) >> dtflearn.concat_outputs("Concat_Images")



    val loss = dtflearn.tuple2_layer(
      "Tup2",
      split_stacked_images >> write_images("Actual_Image"),
      dtflearn.tuple2_layer(
        "Tup_1",
        dtflearn.identity[Output]("Id"),
        split_stacked_images >> write_images("Reconstruction"))) >>
      lossFunc >>
      tf.learn.ScalarSummary("Loss", "ReconstructionLoss")

    //Now train the model
    val (model, estimator) = dtflearn.build_unsup_tf_model(
      arch, input, loss,
      optimizer, java.nio.file.Paths.get(tf_summary_dir.toString()),
      stop_criteria, stepRateFreq = 5000, summarySaveFreq = 5000, checkPointFreq = 5000)(
      train_data, inMemory = false)


    (model, estimator, collated_data, dataSet, tf_summary_dir)

  }

  /**
    * Train and test a CNN based solar wind prediction architecture.
    *
    * @param collated_data Data set of temporally joined
    *                      image paths and GOES X-Ray fluxes.
    *                      This is generally the output after
    *                      executing [[collate_goes_data_range()]]
    *                      with the relevant parameters.
    * @param tt_partition A function which splits the data set
    *                     into train and test sections, based on
    *                     any Boolean function. If the function
    *                     returns true then the instance falls into
    *                     the training set else the test set
    * @param resample If set to true, the training data is resampled
    *                 to balance the occurrence of high flux and low
    *                 flux events.
    *
    * @param tempdir A working directory where the results will be
    *                archived, defaults to user_home_dir/tmp. The model
    *                checkpoints and other results will be stored inside
    *                another directory created in tempdir.
    * @param results_id The suffix added the results/checkpoints directory name.
    * @param stop_criteria When to stop training, an instance of [[StopCriteria]]
    * @param arch The neural architecture to train, for example see [[learn.cnn_sw_v1]]
    *
    * */
  def run_cdt_experiment_omni(
    collated_data: HELIOS_OMNI_DATA,
    tt_partition: PATTERN => Boolean,
    resample: Boolean = false,
    preprocess_image: DataPipe[Image, Image] = identityPipe[Image],
    image_to_bytearr: DataPipe[Image, Array[Byte]] = DataPipe((i: Image) => i.argb.flatten.map(_.toByte)),
    processed_image_size: (Int, Int) = (-1, -1),
    num_channels_image: Int = 4,
    image_history: Int = 0,
    image_history_downsampling: Int = 1)(
    results_id: String,
    stop_criteria: StopCriteria,
    tempdir: Path = home/"tmp",
    arch: Layer[Output, (Output, Output)],
    lossFunc: Layer[((Output, Output), Output), Output],
    mo_flag: Boolean = true,
    prob_timelags: Boolean = true,
    optimizer: Optimizer = tf.train.AdaDelta(0.001),
    miniBatchSize: Int = 16,
    reuseExistingModel: Boolean = false): ExperimentResult[DataSet[PATTERN], Tensor, Tensor, (Tensor, Tensor), (Output, Output)] = {

    //The directories to write model parameters and summaries.
    val resDirName = if(reuseExistingModel) results_id else "helios_omni_"+results_id

    val tf_summary_dir = tempdir/resDirName

    val num_outputs = collated_data.data.head._2._2.length

    /*
    * Create the data processing pipe for processing each image.
    *
    * 1. Load the image into a scrimage object
    * 2. Apply pre-processing filters on the image
    * 3. Convert the processed image into a byte array
    * 4. Load the byte array into a tensor
    * */


    val image_into_tensor = read_image >
      preprocess_image >
      image_to_bytearr >
      DataPipe((arr: Array[Byte]) => dtf.tensor_from_buffer(
        dtype = "UINT8", processed_image_size._1,
        processed_image_size._2,
        num_channels_image)(arr))


    val load_image: DataPipe[Seq[Path], Option[Tensor]] = DataPipe((images: Seq[Path]) => {

      val first_non_corrupted_file: (Int, Path) =
        images
          .map(p => (available_bytes(p), p))
          .sortBy(_._1)
          .reverse
          .head

      if (first_non_corrupted_file._1 > 0) Some(image_into_tensor(first_non_corrupted_file._2)) else None

    })

    val load_targets_into_tensor = DataPipe((arr: Seq[Double]) => dtf.tensor_f32(num_outputs)(arr:_*))

    val dataSet: TF_DATA = helios.data.prepare_helios_data_set(
      collated_data,
      load_image,
      load_targets_into_tensor,
      tt_partition,
      resample,
      image_history,
      image_history_downsampling)

    val (norm_tf_data, scalers): SC_TF_DATA = scale_helios_dataset(dataSet)

    val causal_horizon = collated_data.data.head._2._2.length

    val data_shapes = (
      Shape(processed_image_size._1, processed_image_size._2, num_channels_image*(image_history_downsampling + 1)),
      Shape(causal_horizon)
    )

    val trainData = norm_tf_data.training_dataset.build[
        (Tensor, Tensor), (Output, Output),
        (DataType.Aux[UByte], DataType.Aux[Float]), (DataType, DataType),
        (Shape, Shape)](
      Left(identityPipe[(Tensor, Tensor)]),
      dataType = (UINT8, FLOAT32), data_shapes)
      .repeat()
      .shuffle(1000)
      .batch(miniBatchSize)
      .prefetch(10)

    /*
    * Start building tensorflow network/graph
    * */
    println("Building the regression model.")
    val input = tf.learn.Input(
      UINT8, Shape(-1) ++ data_shapes._1
    )

    val trainInput = tf.learn.Input(FLOAT32, Shape(-1) ++ data_shapes._2)

    val trainingInputLayer = tf.learn.Cast("TrainInput", FLOAT64)

    val loss = lossFunc >>
      tf.learn.ScalarSummary("Loss", "ModelLoss")

    //Now create the model
    val (model, estimator) = dtflearn.build_tf_model(
      arch, input, trainInput, trainingInputLayer,
      loss, optimizer, java.nio.file.Paths.get(tf_summary_dir.toString()),
      stop_criteria)(
      trainData)

    val nTest = norm_tf_data.test_dataset.size

    val predictions: (Tensor, Tensor) = dtfutils.buffered_preds[
      Tensor, Output, DataType, Shape, (Output, Output),
      Tensor, Output, DataType, Shape, Output,
      Tensor, (Tensor, Tensor), (Tensor, Tensor)](
      estimator, tfi.stack(norm_tf_data.test_dataset.data.toSeq.map(_._1), axis = 0),
      _buffer_size, nTest)

    val index_times = Tensor(
      (0 until causal_horizon).map(_.toDouble)
    ).reshape(
      Shape(causal_horizon)
    )

    val pred_time_lags_test: Tensor = if(prob_timelags) {
      val unsc_probs = predictions._2

      unsc_probs.topK(1)._2.reshape(Shape(nTest)).cast(FLOAT64)

    } else predictions._2

    val pred_targets: Tensor = if (mo_flag) {
      val all_preds =
        if (prob_timelags) scalers._2.i(predictions._1)
        else scalers._2.i(predictions._1)

      val repeated_times = tfi.stack(Seq.fill(causal_horizon)(pred_time_lags_test.floor), axis = -1)

      val conv_kernel = repeated_times.subtract(index_times).square.multiply(-1.0).exp.floor

      all_preds.multiply(conv_kernel).sum(axes = 1).divide(conv_kernel.sum(axes = 1))
    } else {
      scalers._2(0).i(predictions._1)
    }

    val test_labels = dataSet.test_dataset.data.map(_._2).map(t => dtfutils.toDoubleSeq(t).toSeq).toSeq

    val actual_targets = test_labels.zipWithIndex.map(zi => {
      val (z, index) = zi
      val time_lag = pred_time_lags_test(index).scalar.asInstanceOf[Double].toInt

      z(time_lag)
    })

    val reg_metrics = new RegressionMetricsTF(pred_targets, actual_targets)

    //write predictions and ground truth to a csv file

    write_processed_predictions(
      dtfutils.toDoubleSeq(pred_targets).toSeq,
      actual_targets,
      dtfutils.toDoubleSeq(pred_time_lags_test).toSeq,
      tf_summary_dir/("scatter_test-"+DateTime.now().toString("YYYY-MM-dd-HH-mm")+".csv"))

    val experiment_config = ExperimentConfig(mo_flag, prob_timelags, "mode")

    val results = SupervisedModelRun(
      (norm_tf_data, scalers),
      model, estimator, None,
      Some(reg_metrics),
      tf_summary_dir, None,
      Some((pred_targets, pred_time_lags_test))
    )

    val partitioned_data = collated_data.partition(DataPipe(tt_partition))

    ExperimentResult(
      experiment_config,
      partitioned_data.training_dataset,
      partitioned_data.test_dataset,
      results
    )
  }

  /**
    * Run a CDT experiment, predicting solar wind
    * arrival time and speed using SOHO/SDO images.
    *
    * @param dataset A collection of [[PATTERN]] instances i.e.
    *                date time stamps, image paths and
    *                upstream solar wind values.
    *                See [[HELIOS_OMNI_DATA]].
    *
    * @param tt_partition A function which takes each [[PATTERN]] and
    *                     returns `true` if it should be kept in the training
    *                     set and `false` otherwise.
    *
    * @param architecture Neural network architecture. Takes a tensor representation
    *                     of an image (or time series of images) and predicts.
    *                     <ol>
    *                       <li>Running history of solar wind speed</li>
    *                       <li>Probability distribution for causal time lag link</li>
    *                     <ol>
    *
    * @param hyper_params A list of hyper-parameters.
    *
    * @param loss_func_generator A function which takes some value of hyper-parameters,
    *                            and generates the CDT loss function. See [[CausalDynamicTimeLag]]
    *                            for more information on its hyper-parameters.
    *
    * @param fitness_func A data pipe which takes the model predictions, and the ground truth solar
    *                     wind time series and computes a fitness score which is used to compare
    *                     hyper-parameter assignments.
    *
    * @param hyper_prior A prior distribution over the hyper-parameter space,
    *                    the log likelihood computed from this is added to the
    *                    fitness to yield the total score for a hyper-parameter
    *                    configuration.
    *
    * @param results_id A string identifier for the results directory, this folder
    *                   will contain each trained model including the resulting
    *                   model after the tuning procedure.
    *
    * @param resample Set to true if the training data should be resampled to
    *                 mitigate imbalance of low versus high solar wind episodes.
    *
    *
    * @param preprocess_image A data pipe which applies some user specified pre-processing
    *                         operations on each image.
    *
    * @param image_to_bytearr A data pipe which converts the image to an array of bytes.
    *
    * @param processed_image_size Size in pixels of the processed image used to construct
    *                             tensor patterns.
    *
    * @param num_channels_image Number of colour channels in each processed image pattern.
    *
    * @param image_history If set to a positive value, a stacked time history of images
    *                      is constructed for each data pattern.
    *
    * @param image_history_downsampling Sets the down-sampling frequency of the image histories.
    *
    * @param iterations Maximum number of iterations to train the selected model during final training.
    *
    * @param iterations_tuning Maximum number of iterations to train model instances
    *                          during the tuning process.
    *
    * @param summaries_top_dir The top level directiry under which the results directory of the experiment
    *                          is to be created.
    *
    * @param optimizer Optimization algorithm to be used in tuning and training.
    *
    * @param miniBatchSize The mini batch size.
    *
    * @param num_hyp_samples The number of hyper-parameter samples (population size) to use
    *                        in Grid Search `gs` and Coupled Simulated Annealing `csa`
    *                        subroutines.
    *
    * @param hyper_optimizer Hyper-parameter search technique.
    *                        <ol>
    *                          <li>gs: Grid Search (in this case random search). The default value</li>
    *                          <li>csa: Coupled Simulated Annealing</li>
    *                          <li>cma: Covariance Matrix Adaptation - Evolutionary Search</li>
    *                        </ol>
    *
    * @param hyp_opt_iterations In case of `csa` and `cma` methods, the maximum number of iterations
    *                           to execute.
    *
    * @param hyp_mapping A one-to-one functional mapping for each hyper-parameter from its original
    *                    domain to the Real number line. Optional and defaults to [[None]].
    *
    * @return An [[Experiment]] instance which contains a [[ModelRunTuning]] instance and some
    *         configuration information [[ImageExpConfig]].
    * */
  def run_cdt_experiment_omni_hyp(
    dataset: HELIOS_OMNI_DATA,
    tt_partition: PATTERN => Boolean,
    architecture: Layer[Output, (Output, Output)],
    hyper_params: List[String],
    loss_func_generator: dtflearn.tunable_tf_model.HyperParams => Layer[((Output, Output), Output), Output],
    fitness_func: DataPipe2[(Tensor, Tensor), Tensor, Double],
    hyper_prior: Map[String, ContinuousRVWithDistr[Double, ContinuousDistr[Double]]],
    results_id: String,
    resample: Boolean                                         = false,
    preprocess_image: DataPipe[Image, Image]                  = identityPipe[Image],
    image_to_bytearr: DataPipe[Image, Array[Byte]]            = DataPipe((i: Image) => i.argb.flatten.map(_.toByte)),
    processed_image_size: (Int, Int)                          = (-1, -1),
    num_channels_image: Int                                   = 4,
    image_history: Int                                        = 0,
    image_history_downsampling: Int                           = 1,
    iterations: Int                                           = 150000,
    iterations_tuning: Int                                    = 20000,
    summaries_top_dir: Path                                   = home/"tmp",
    optimizer: Optimizer                                      = tf.train.AdaDelta(0.001),
    miniBatchSize: Int                                        = 16,
    num_hyp_samples: Int                                      = 20,
    hyper_optimizer: String                                   = "gs",
    hyp_opt_iterations: Option[Int]                           = Some(5),
    hyp_mapping: Option[Map[String, Encoder[Double, Double]]] = None): Experiment[ModelRunTuning, ImageExpConfig] = {

    //The directories to write model parameters and summaries.
    val resDirName = "helios_omni_"+results_id

    val tf_summary_dir = summaries_top_dir/resDirName

    val num_outputs = dataset.data.head._2._2.length

    /*
    * Create the data processing pipe for processing each image.
    *
    * 1. Load the image into a scrimage object
    * 2. Apply pre-processing filters on the image
    * 3. Convert the processed image into a byte array
    * 4. Load the byte array into a tensor
    * */


    val image_into_tensor = read_image >
      preprocess_image >
      image_to_bytearr >
      DataPipe((arr: Array[Byte]) => dtf.tensor_from_buffer(
        dtype = "UINT8", processed_image_size._1,
        processed_image_size._2,
        num_channels_image)(arr))


    val load_image: DataPipe[Seq[Path], Option[Tensor]] = DataPipe((images: Seq[Path]) => {

      val first_non_corrupted_file: (Int, Path) =
        images
          .map(p => (available_bytes(p), p))
          .sortBy(_._1)
          .reverse
          .head

      if (first_non_corrupted_file._1 > 0) Some(image_into_tensor(first_non_corrupted_file._2)) else None

    })

    val load_targets_into_tensor = DataPipe((arr: Seq[Double]) => dtf.tensor_f32(num_outputs)(arr:_*))

    val dataSet: TF_DATA = helios.data.prepare_helios_data_set(
      dataset,
      load_image,
      load_targets_into_tensor,
      tt_partition,
      resample,
      image_history,
      image_history_downsampling)

    val (norm_tf_data, scalers): SC_TF_DATA = scale_helios_dataset(dataSet)

    val causal_horizon = dataset.data.head._2._2.length

    val data_shapes = (
      Shape(processed_image_size._1, processed_image_size._2, num_channels_image*(image_history_downsampling + 1)),
      Shape(causal_horizon)
    )

    val data_size = dataSet.training_dataset.size


    val stop_condition_tuning = timelag.utils.get_stop_condition(
      iterations_tuning, 0.05, epochF = false,
      data_size, miniBatchSize)

    val stop_condition_test   = timelag.utils.get_stop_condition(
      iterations, 0.01, epochF = false,
      data_size, miniBatchSize)

    val train_config_tuning =
      dtflearn.tunable_tf_model.ModelFunction.hyper_params_to_dir >>
        DataPipe((p: Path) => dtflearn.model.trainConfig(
          p, optimizer,
          stop_condition_tuning,
          Some(timelag.utils.get_train_hooks(p, iterations_tuning, epochFlag = false, data_size, miniBatchSize))
        ))

    val train_config_test = DataPipe[dtflearn.tunable_tf_model.HyperParams, dtflearn.model.Config](_ =>
      dtflearn.model.trainConfig(
        summaryDir = tf_summary_dir,
        stopCriteria = stop_condition_test,
        trainHooks = Some(
          timelag.utils.get_train_hooks(tf_summary_dir, iterations, epochFlag = false, data_size, miniBatchSize)
        )
      )
    )

    val tf_data_ops = dtflearn.model.data_ops(10, miniBatchSize, 10, data_size/5)

    val stackOperation = DataPipe[Iterable[Tensor], Tensor](bat =>
      tfi.stack(bat.toSeq, axis = 0)
    )

    val tunableTFModel: TunableTFModel[
      Tensor, Output, DataType.Aux[UByte], DataType, Shape, (Output, Output), (Tensor, Tensor),
      Tensor, Output, DataType.Aux[Float], DataType, Shape, Output] =
      dtflearn.tunable_tf_model(
        loss_func_generator, hyper_params,
        norm_tf_data.training_dataset,
        fitness_func,
        architecture,
        (UINT8, data_shapes._1),
        (FLOAT32, data_shapes._2),
        tf.learn.Cast("TrainInput", FLOAT64),
        train_config_tuning(tf_summary_dir),
        data_split_func = Some(
          DataPipe[(Tensor, Tensor), Boolean](_ => scala.util.Random.nextDouble() <= 0.7)
        ),
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

    gs.setNumSamples(num_hyp_samples)

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
      Tensor, Output, DataType.Aux[UByte], DataType, Shape, (Output, Output), (Tensor, Tensor),
      Tensor, Output, DataType.Aux[Float], DataType, Shape, Output](
      loss_func_generator, architecture,
      (UINT8, data_shapes._1),
      (FLOAT32, data_shapes._2),
      tf.learn.Cast("TrainInput", FLOAT64),
      train_config_test,
      tf_data_ops, inMemory = false,
      concatOpI = Some(stackOperation),
      concatOpT = Some(stackOperation)
    )

    val best_model = model_function(config)(norm_tf_data.training_dataset)

    best_model.train()

    val extract_features = (p: (Tensor, Tensor)) => p._1

    val model_predictions_test = best_model.infer_coll(norm_tf_data.test_dataset.map(extract_features))
    val model_predictions_train = best_model.infer_coll(norm_tf_data.training_dataset.map(extract_features))

    val test_predictions = model_predictions_test match {
      case Left(tensor) => tensor
      case Right(collection) => timelag.utils.collect_predictions(collection)
    }

    val train_predictions = model_predictions_train match {
      case Left(tensor) => tensor
      case Right(collection) => timelag.utils.collect_predictions(collection)
    }

    val (pred_outputs_train, pred_time_lags_train) = process_predictions(
      train_predictions,
      causal_horizon,
      multi_output = true,
      probabilistic_time_lags = true,
      timelag_pred_strategy = "mode",
      Some(scalers._2))

    val (pred_outputs_test, pred_time_lags_test) = process_predictions(
      test_predictions,
      causal_horizon,
      multi_output = true,
      probabilistic_time_lags = true,
      timelag_pred_strategy = "mode",
      Some(scalers._2))


    //The test labels dont require rescaling
    val test_labels = norm_tf_data.test_dataset
      .map((p: (Tensor, Tensor)) => p._2)
      .map((t: Tensor) => dtfutils.toDoubleSeq(t).toSeq)
      .data
      .toSeq

    //The training labels were scaled during processing,
    //hence must be rescaled back to original domains.
    val train_labels = norm_tf_data.training_dataset
      .map((p: (Tensor, Tensor)) => scalers._2.i(p._2))
      .map((t: Tensor) => dtfutils.toDoubleSeq(t).toSeq)
      .data
      .toSeq

    val actual_targets_test = test_labels.zipWithIndex.map(zi => {
      val (z, index) = zi
      val time_lag = pred_time_lags_test(index).scalar.asInstanceOf[Double].toInt

      z(time_lag)
    })

    val actual_targets_train = train_labels.zipWithIndex.map(zi => {
      val (z, index) = zi
      val time_lag = pred_time_lags_train(index).scalar.asInstanceOf[Double].toInt

      z(time_lag)
    })

    val reg_metrics_test  = new RegressionMetricsTF(pred_outputs_test, actual_targets_test)
    val reg_metrics_train = new RegressionMetricsTF(pred_outputs_train, actual_targets_train)


    val results: helios.ModelRunTuning = helios.TunedModelRun(
      (norm_tf_data, scalers),
      best_model,
      Some(reg_metrics_train),
      Some(reg_metrics_test),
      tf_summary_dir,
      Some((pred_outputs_train, pred_time_lags_train)),
      Some((pred_outputs_test, pred_time_lags_test))
    )


    val exp_config = ImageExpConfig(
      Seq.empty[SolarImagesSource],
      Seq(preprocess_image),
      image_history, image_history_downsampling,
      multi_output            = true,
      probabilistic_time_lags = true,
      timelag_prediction      = "mode",
      data_shapes._1,
      data_shapes._2
    )

    val time_stamp = DateTime.now().toString("YYYY-MM-dd-HH-mm")

    val partitioned_data_collection = dataset.partition(DataPipe(tt_partition))

    //Write the train and test collections
    write_helios_data_set(
      partitioned_data_collection.training_dataset,
      tf_summary_dir,
      s"training_data_$time_stamp.json")

    write_helios_data_set(
      partitioned_data_collection.test_dataset,
      tf_summary_dir,
      s"test_data_$time_stamp,json")


    //Write model outputs for test data
    timelag.utils.write_model_outputs(test_predictions, tf_summary_dir, s"test_$time_stamp")
    //Write model outputs for training data
    timelag.utils.write_model_outputs(train_predictions, tf_summary_dir, s"train_$time_stamp")

    //Write the predictions for test data
    write_processed_predictions(
      dtfutils.toDoubleSeq(pred_outputs_test).toSeq,
      actual_targets_test,
      dtfutils.toDoubleSeq(pred_time_lags_test).toSeq,
      tf_summary_dir/s"scatter_test-$time_stamp.csv")

    //Write the predictions for training data
    write_processed_predictions(
      dtfutils.toDoubleSeq(pred_outputs_train).toSeq,
      actual_targets_train,
      dtfutils.toDoubleSeq(pred_time_lags_train).toSeq,
      tf_summary_dir/s"scatter_train-$time_stamp.csv")

    Experiment(exp_config, results)
  }


  /**
    * Train and test a CNN based solar wind prediction architecture.
    *
    * @param collated_data Data set of temporally joined
    *                      image paths and GOES X-Ray fluxes.
    *                      This is generally the output after
    *                      executing [[collate_goes_data_range()]]
    *                      with the relevant parameters.
    * @param tt_partition A function which splits the data set
    *                     into train and test sections, based on
    *                     any Boolean function. If the function
    *                     returns true then the instance falls into
    *                     the training set else the test set
    * @param resample If set to true, the training data is resampled
    *                 to balance the occurrence of high flux and low
    *                 flux events.
    *
    * @param tempdir A working directory where the results will be
    *                archived, defaults to user_home_dir/tmp. The model
    *                checkpoints and other results will be stored inside
    *                another directory created in tempdir.
    * @param results_id The suffix added the results/checkpoints directory name.
    * @param stop_criteria When to stop training.
    * @param arch The neural architecture to train.
    *
    * */
  def run_cdt_experiment_omni_ext(
    collated_data: HELIOS_OMNI_DATA_EXT,
    tt_partition: PATTERN_EXT => Boolean,
    resample: Boolean = false,
    preprocess_image: DataPipe[Image, Image] = identityPipe[Image],
    image_to_bytearr: DataPipe[Image, Array[Byte]] = DataPipe((i: Image) => i.argb.flatten.map(_.toByte)),
    processed_image_size: (Int, Int) = (-1, -1),
    num_channels_image: Int = 4,
    image_history: Int = 0,
    image_history_downsampling: Int = 1)(
    results_id: String,
    stop_criteria: StopCriteria,
    tempdir: Path = home/"tmp",
    arch: Layer[(Output, Output), (Output, Output)],
    lossFunc: Layer[((Output, Output), Output), Output],
    mo_flag: Boolean = true,
    prob_timelags: Boolean = true,
    optimizer: Optimizer = tf.train.AdaDelta(0.001),
    miniBatchSize: Int = 16,
    reuseExistingModel: Boolean = false) = {

    //The directories to write model parameters and summaries.
    val resDirName = if(reuseExistingModel) results_id else "helios_omni_hist_"+results_id

    val tf_summary_dir = tempdir/resDirName

    val num_outputs = collated_data.data.head._2._2._2.length

    val causal_horizon = num_outputs

    val size_history = collated_data.data.head._2._2._1.length

    /*
    * After data has been joined/collated,
    * start loading it into tensors
    *
    * */

    val load_image_into_tensor = read_image >
      preprocess_image >
      image_to_bytearr >
      /*data.image_to_tensor(
        processed_image_size._1,
        num_channels_image)*/
      DataPipe((arr: Array[Byte]) => dtf.tensor_from_buffer(
        dtype = "UINT8", processed_image_size._1,
        processed_image_size._2,
        num_channels_image)(arr))

    val load_targets_into_tensor = DataPipe((arr: Seq[Double]) => dtf.tensor_f32(num_outputs)(arr:_*))

    val load_targets_hist_into_tensor = DataPipe((arr: Seq[Double]) => dtf.tensor_f32(size_history)(arr:_*))

    val dataSet: TF_DATA_EXT = helios.data.prepare_helios_ts_data_set(
      collated_data,
      load_image_into_tensor,
      load_targets_into_tensor,
      load_targets_hist_into_tensor,
      tt_partition,
      resample,
      image_history,
      image_history_downsampling)


    val (norm_tf_data, scalers): SC_TF_DATA_EXT = scale_helios_dataset_ext(dataSet)

    val data_shapes = (
      (
        Shape(processed_image_size._1, processed_image_size._2, num_channels_image*(image_history_downsampling + 1)),
        Shape(size_history)
      ),
      Shape(causal_horizon)
    )

    val trainData =
      norm_tf_data.training_dataset.build[
        ((Tensor, Tensor), Tensor), ((Output, Output), Output),
        ((DataType.Aux[UByte], DataType.Aux[Float]), DataType.Aux[Float]),
        ((DataType, DataType), DataType),
        ((Shape, Shape), Shape)](
        Left(identityPipe[((Tensor, Tensor), Tensor)]),
        ((UINT8, FLOAT32), FLOAT32),
        data_shapes)
        .repeat()
        .shuffle(1000)
        .batch(miniBatchSize)
        .prefetch(10)

    /*
    * Start building tensorflow network/graph
    * */
    println("Building the regression model.")

    val input = tf.learn.Input[
      (Tensor, Tensor), (Output, Output),
      (DataType.Aux[UByte], DataType.Aux[Float]),
      (DataType, DataType), (Shape, Shape)](
      (UINT8, FLOAT32),
      (
        Shape(-1) ++ data_shapes._1._1,
        Shape(-1) ++ data_shapes._1._2
      )
    )


    val trainInput = tf.learn.Input[
      Tensor, Output, DataType.Aux[Float],
      DataType, Shape](FLOAT32, Shape(-1, causal_horizon))

    val trainingInputLayer = tf.learn.Cast("TrainInput", FLOAT64)

    val loss = lossFunc >> tf.learn.ScalarSummary("Loss", "ModelLoss")

    val summariesDir = java.nio.file.Paths.get(tf_summary_dir.toString())

    //Now create the model
    val (model, estimator) = dtflearn.build_tf_model(
      arch, input, trainInput, trainingInputLayer,
      loss, optimizer, summariesDir,
      stop_criteria)(trainData)


    val nTest = norm_tf_data.test_dataset.size

    val predictions: (Tensor, Tensor) = dtfutils.buffered_preds[
      (Tensor, Tensor), (Output, Output), (DataType, DataType), (Shape, Shape), (Output, Output),
      Tensor, Output, DataType, Shape, Output,
      (Tensor, Tensor), (Tensor, Tensor), (Tensor, Tensor)](
      estimator,
      (
        tfi.stack(norm_tf_data.test_dataset.data.toSeq.map(_._1._1), axis = 0),
        tfi.stack(norm_tf_data.test_dataset.data.toSeq.map(_._1._2), axis = 0)
      ),
      _buffer_size, nTest)

    val index_times = Tensor(
      (0 until causal_horizon).map(_.toDouble)
    ).reshape(
      Shape(causal_horizon)
    )


    val pred_time_lags_test: Tensor = if(prob_timelags) {
      val unsc_probs = predictions._2

      unsc_probs.topK(1)._2.reshape(Shape(nTest)).cast(FLOAT64)

    } else predictions._2

    val pred_targets: Tensor = if (mo_flag) {
      val all_preds =
        if (prob_timelags) scalers._2.i(predictions._1)
        else scalers._2.i(predictions._1)

      val repeated_times = tfi.stack(Seq.fill(causal_horizon)(pred_time_lags_test.floor), axis = -1)

      val conv_kernel = repeated_times.subtract(index_times).square.multiply(-1.0).exp.floor

      all_preds.multiply(conv_kernel).sum(axes = 1).divide(conv_kernel.sum(axes = 1))
    } else {
      scalers._2(0).i(predictions._1)
    }

    val test_labels = dataSet.test_dataset.data.map(_._2).map(t => dtfutils.toDoubleSeq(t).toSeq).toSeq

    val actual_targets = test_labels.zipWithIndex.map(zi => {
      val (z, index) = zi
      val time_lag = pred_time_lags_test(index).scalar.asInstanceOf[Double].toInt

      z(time_lag)
    })

    val reg_metrics = new RegressionMetricsTF(pred_targets, actual_targets)

    //write predictions and ground truth to a csv file

    write_processed_predictions(
      dtfutils.toDoubleSeq(pred_targets).toSeq,
      actual_targets,
      dtfutils.toDoubleSeq(pred_time_lags_test).toSeq,
      tf_summary_dir/("scatter_test-"+DateTime.now().toString("YYYY-MM-dd-HH-mm")+".csv"))


    (model, estimator, reg_metrics, tf_summary_dir, scalers, collated_data, norm_tf_data)
  }

  /**
    * Train and test a CNN based solar wind prediction architecture.
    *
    * @param collated_data Data set of temporally joined
    *                      image paths and GOES X-Ray fluxes.
    *                      This is generally the output after
    *                      executing [[collate_goes_data_range()]]
    *                      with the relevant parameters.
    * @param tt_partition A function which splits the data set
    *                     into train and test sections, based on
    *                     any Boolean function. If the function
    *                     returns true then the instance falls into
    *                     the training set else the test set
    * @param resample If set to true, the training data is resampled
    *                 to balance the occurrence of high flux and low
    *                 flux events.
    *
    * @param tempdir A working directory where the results will be
    *                archived, defaults to user_home_dir/tmp. The model
    *                checkpoints and other results will be stored inside
    *                another directory created in tempdir.
    * @param results_id The suffix added the results/checkpoints directory name.
    * @param stop_criteria When to stop training, an instance of [[StopCriteria]]
    * @param arch The neural architecture to train, for example see [[learn.cnn_sw_v1]]
    *
    * */
  def run_cdt_experiment_mc_omni(
    image_sources: Seq[SolarImagesSource],
    collated_data: HELIOS_MC_OMNI_DATA,
    tt_partition: MC_PATTERN => Boolean,
    resample: Boolean = false,
    preprocess_image: Map[SolarImagesSource, DataPipe[Image, Image]],
    images_to_bytes: Map[SolarImagesSource, DataPipe[Image, Array[Byte]]],
    processed_image_size: (Int, Int) = (-1, -1),
    num_channels_image: Int = 4,
    image_history: Int = 0,
    image_history_downsampling: Int = 1)(
    results_id: String,
    stop_criteria: StopCriteria,
    tempdir: Path = home/"tmp",
    arch: Layer[Output, (Output, Output)],
    lossFunc: Layer[((Output, Output), Output), Output],
    mo_flag: Boolean = true,
    prob_timelags: Boolean = true,
    optimizer: Optimizer = tf.train.AdaDelta(0.001),
    miniBatchSize: Int = 16,
    reuseExistingModel: Boolean = false): ExperimentResult[DataSet[MC_PATTERN], Tensor, Tensor, (Tensor, Tensor), (Output, Output)] = {

    //The directories to write model parameters and summaries.
    val resDirName = if(reuseExistingModel) results_id else "helios_omni_"+results_id

    val tf_summary_dir = tempdir/resDirName

    val num_outputs = collated_data.data.head._2._2.length

    /*
    * Create the data processing pipe for processing each image.
    *
    * 1. Load the image into a scrimage object
    * 2. Apply pre-processing filters on the image
    * 3. Convert the processed image into a byte array
    * 4. Load the byte array into a tensor
    * */

    val bytes_to_tensor = DataPipe((arr: Array[Byte]) => dtf.tensor_from_buffer(
      dtype = "UINT8", processed_image_size._1,
      processed_image_size._2,
      num_channels_image)(arr))//data.image_to_tensor(processed_image_size._1, num_channels_image)

    /**/


    val load_image_into_tensor: DataPipe[Map[SolarImagesSource, Seq[Path]], Option[Tensor]] = DataPipe(mc_image => {

      val bytes = mc_image.toSeq.sortBy(_._1.toString).map(kv => {

        val first_non_corrupted_file: (Int, Path) =
          kv._2
            .map(p => (available_bytes(p), p))
            .sortBy(_._1)
            .reverse
            .head

        val processing_for_channel = read_image > preprocess_image(kv._1) > images_to_bytes(kv._1)

        if (first_non_corrupted_file._1 > 0) Some(processing_for_channel(first_non_corrupted_file._2)) else None

      }).toArray


      if(bytes.forall(_.isDefined)) Some(bytes_to_tensor(bytes.flatMap(_.get))) else None

    })

    val load_targets_into_tensor = DataPipe((arr: Seq[Double]) => dtf.tensor_f32(num_outputs)(arr:_*))

    val dataSet: TF_DATA = helios.data.prepare_mc_helios_data_set(
      image_sources,
      collated_data,
      load_image_into_tensor,
      load_targets_into_tensor,
      tt_partition,
      resample,
      image_history,
      image_history_downsampling)

    val (norm_tf_data, scalers): SC_TF_DATA = scale_helios_dataset(dataSet)

    val causal_horizon = collated_data.data.head._2._2.length

    val data_shapes = (
      Shape(processed_image_size._1, processed_image_size._2, num_channels_image*(image_history_downsampling + 1)),
      Shape(causal_horizon)
    )

    val trainData = norm_tf_data.training_dataset.build[
      (Tensor, Tensor), (Output, Output),
      (DataType.Aux[UByte], DataType.Aux[Float]), (DataType, DataType),
      (Shape, Shape)](
      Left(identityPipe[(Tensor, Tensor)]),
      dataType = (UINT8, FLOAT32), data_shapes)
      .repeat()
      .shuffle(1000)
      .batch(miniBatchSize)
      .prefetch(10)

    /*
    * Start building tensorflow network/graph
    * */
    println("Building the regression model.")
    val input = tf.learn.Input(
      UINT8, Shape(-1) ++ data_shapes._1
    )

    val trainInput = tf.learn.Input(FLOAT32, Shape(-1) ++ data_shapes._2)

    val trainingInputLayer = tf.learn.Cast("TrainInput", FLOAT64)

    val loss = lossFunc >>
      tf.learn.ScalarSummary("Loss", "ModelLoss")

    //Now create the model
    val (model, estimator) = dtflearn.build_tf_model(
      arch, input, trainInput, trainingInputLayer,
      loss, optimizer, java.nio.file.Paths.get(tf_summary_dir.toString()),
      stop_criteria)(
      trainData)

    val nTest = norm_tf_data.test_dataset.size

    val predictions: (Tensor, Tensor) = dtfutils.buffered_preds[
      Tensor, Output, DataType, Shape, (Output, Output),
      Tensor, Output, DataType, Shape, Output,
      Tensor, (Tensor, Tensor), (Tensor, Tensor)](
      estimator, tfi.stack(norm_tf_data.test_dataset.data.toSeq.map(_._1), axis = 0),
      _buffer_size, nTest)

    val index_times = Tensor(
      (0 until causal_horizon).map(_.toDouble)
    ).reshape(
      Shape(causal_horizon)
    )

    val pred_time_lags_test: Tensor = if(prob_timelags) {
      val unsc_probs = predictions._2

      unsc_probs.topK(1)._2.reshape(Shape(nTest)).cast(FLOAT64)

    } else predictions._2

    val pred_targets: Tensor = if (mo_flag) {
      val all_preds =
        if (prob_timelags) scalers._2.i(predictions._1)
        else scalers._2.i(predictions._1)

      val repeated_times = tfi.stack(Seq.fill(causal_horizon)(pred_time_lags_test.floor), axis = -1)

      val conv_kernel = repeated_times.subtract(index_times).square.multiply(-1.0).exp.floor

      all_preds.multiply(conv_kernel).sum(axes = 1).divide(conv_kernel.sum(axes = 1))
    } else {
      scalers._2(0).i(predictions._1)
    }

    val test_labels = dataSet.test_dataset.data.map(_._2).map(t => dtfutils.toDoubleSeq(t).toSeq).toSeq

    val actual_targets = test_labels.zipWithIndex.map(zi => {
      val (z, index) = zi
      val time_lag = pred_time_lags_test(index).scalar.asInstanceOf[Double].toInt

      z(time_lag)
    })

    val reg_metrics = new RegressionMetricsTF(pred_targets, actual_targets)

    //write predictions and ground truth to a csv file

    write_processed_predictions(
      dtfutils.toDoubleSeq(pred_targets).toSeq,
      actual_targets,
      dtfutils.toDoubleSeq(pred_time_lags_test).toSeq,
      tf_summary_dir/("scatter_test-"+DateTime.now().toString("YYYY-MM-dd-HH-mm")+".csv"))

    val experiment_config = ExperimentConfig(mo_flag, prob_timelags, "mode")

    val results = SupervisedModelRun(
      (norm_tf_data, scalers),
      model, estimator, None,
      Some(reg_metrics),
      tf_summary_dir, None,
      Some((pred_targets, pred_time_lags_test))
    )

    val partitioned_data = collated_data.partition(DataPipe(tt_partition))

    ExperimentResult(
      experiment_config,
      partitioned_data.training_dataset,
      partitioned_data.test_dataset,
      results
    )
  }



  /**
    * Train and test a CNN based solar wind prediction architecture.
    *
    * @param collated_data Data set of temporally joined
    *                      image paths and GOES X-Ray fluxes.
    *                      This is generally the output after
    *                      executing [[collate_goes_data_range()]]
    *                      with the relevant parameters.
    * @param tt_partition A function which splits the data set
    *                     into train and test sections, based on
    *                     any Boolean function. If the function
    *                     returns true then the instance falls into
    *                     the training set else the test set
    * @param resample If set to true, the training data is resampled
    *                 to balance the occurrence of high flux and low
    *                 flux events.
    *
    * @param tempdir A working directory where the results will be
    *                archived, defaults to user_home_dir/tmp. The model
    *                checkpoints and other results will be stored inside
    *                another directory created in tempdir.
    * @param results_id The suffix added the results/checkpoints directory name.
    * @param stop_criteria Criteria to stop training.
    * @param arch The neural architecture to train.
    *
    * */
  def run_cdt_experiment_mc_omni_ext(
    image_sources: Seq[SOHO],
    collated_data: HELIOS_MC_OMNI_DATA_EXT,
    tt_partition: MC_PATTERN_EXT => Boolean,
    resample: Boolean = false,
    image_pre_process: Map[SOHO, DataPipe[Image, Image]],
    images_to_bytes: DataPipe[Seq[Image], Array[Byte]],
    num_channels_image: Int = 4)(
    results_id: String,
    stop_criteria: StopCriteria = dtflearn.max_iter_stop(5000),
    tempdir: Path = home/"tmp",
    arch: Layer[(Output, Output), (Output, Output)],
    lossFunc: Layer[((Output, Output), Output), Output],
    mo_flag: Boolean = true,
    prob_timelags: Boolean = true,
    optimizer: Optimizer = tf.train.AdaDelta(0.001),
    miniBatchSize: Int = 16,
    inMemoryModel: Boolean = false) = {

    val resDirName = "helios_omni_"+results_id

    val tf_summary_dir = tempdir/resDirName


    /*
    * After data has been joined/collated,
    * start loading it into tensors
    *
    * */
    val dataSet: TF_MC_DATA_EXT = helios.data.prepare_mc_helios_ts_data_set(
      image_sources,
      collated_data,
      tt_partition,
      image_pre_process,
      images_to_bytes,
      resample)


    val (norm_tf_data, scalers): SC_TF_MC_DATA_EXT =
      scale_helios_dataset_mc_ext(dataSet)

    val trainData =
      norm_tf_data.training_data[
        (Output, Output), (DataType, DataType), (Shape, Shape),
        Output, DataType, Shape]
        .repeat()
        .shuffle(10000)
        .batch(miniBatchSize)
        .prefetch(10)

    /*
    * Start building tensorflow network/graph
    * */
    println("Building the regression model.")

    val input = tf.learn.Input[
      (Tensor, Tensor), (Output, Output),
      (DataType.Aux[UByte], DataType.Aux[Double]),
      (DataType, DataType), (Shape, Shape)](
      (UINT8, FLOAT64),
      (
        Shape(
          -1,
          dataSet.trainData._1.shape(1),
          dataSet.trainData._1.shape(2),
          dataSet.trainData._1.shape(3)),
        Shape(
          -1,
          dataSet.trainData._2.shape(1))
      )
    )

    val causal_horizon = collated_data.head._2._2._2.length

    val trainInput = tf.learn.Input(FLOAT64, Shape(-1, causal_horizon))

    val trainingInputLayer = tf.learn.Cast("TrainInput", FLOAT64)

    val loss = lossFunc >>
      tf.learn.ScalarSummary("Loss", "ModelLoss")

    val summariesDir = java.nio.file.Paths.get(tf_summary_dir.toString())

    //Now create the model
    val (model, estimator) = dtflearn.build_tf_model(
      arch, input, trainInput, trainingInputLayer,
      loss, optimizer, summariesDir, stop_criteria)(
      trainData, inMemory = inMemoryModel)

    val predictions: (Tensor, Tensor) = dtfutils.predict_data[
      (Tensor, Tensor), (Output, Output), (DataType, DataType), (Shape, Shape), (Output, Output),
      Tensor, Output, DataType, Shape, Output,
      (Tensor, Tensor), (Tensor, Tensor)
      ](estimator, dataSet, (false, true), _buffer_size)._2.get

    val index_times = Tensor(
      (0 until causal_horizon).map(_.toDouble)
    ).reshape(
      Shape(causal_horizon)
    )


    val pred_time_lags_test = if(prob_timelags) {
      val unsc_probs = predictions._2

      unsc_probs.topK(1)._2.reshape(Shape(dataSet.nTest)).cast(FLOAT64)

    } else predictions._2


    val pred_targets: Tensor = if (mo_flag) {
      val all_preds =
        if (prob_timelags) scalers._2.i(predictions._1)
        else scalers._2.i(predictions._1)

      val repeated_times = tfi.stack(Seq.fill(causal_horizon)(pred_time_lags_test.floor), axis = -1)

      val conv_kernel = repeated_times.subtract(index_times).square.multiply(-1.0).exp.floor

      all_preds.multiply(conv_kernel).sum(axes = 1).divide(conv_kernel.sum(axes = 1))
    } else {
      scalers._2(0).i(predictions._1)
    }

    val actual_targets = (0 until dataSet.nTest).map(n => {
      val time_lag = pred_time_lags_test(n).scalar.asInstanceOf[Double].toInt
      dataSet.testLabels(n, time_lag).scalar.asInstanceOf[Double]
    })


    val reg_metrics = new RegressionMetricsTF(pred_targets, actual_targets)

    write_processed_predictions(
      dtfutils.toDoubleSeq(pred_targets).toSeq,
      actual_targets,
      dtfutils.toDoubleSeq(pred_time_lags_test).toSeq,
      tf_summary_dir/"scatter_test.csv")

    (model, estimator, reg_metrics, tf_summary_dir, scalers, collated_data, norm_tf_data)
  }

  /*def run_experiment_omni_dynamic_time_scales(
    collated_data: Stream[PATTERN],
    tt_partition: PATTERN => Boolean,
    resample: Boolean = false)(
    results_id: String, max_iterations: Int,
    tempdir: Path = home/"tmp",
    arch: Layer[Output, (Output, Output)]) = {

    val resDirName = "helios_omni_"+results_id

    val tf_summary_dir = tempdir/resDirName

    val checkpoints =
      if (exists! tf_summary_dir) ls! tf_summary_dir |? (_.isFile) |? (_.segments.last.contains("model.ckpt-"))
      else Seq()

    val checkpoint_max =
      if(checkpoints.isEmpty) 0
      else (checkpoints | (_.segments.last.split("-").last.split('.').head.toInt)).max

    val iterations = if(max_iterations > checkpoint_max) max_iterations - checkpoint_max else 0


    /*
    * After data has been joined/collated,
    * start loading it into tensors
    *
    * */

    val dataSet = helios.data.create_helios_data_set(
      collated_data,
      tt_partition,
      image_process = DataPipe((i: Image) => i.copy.scale(1.0/math.pow(2.0, 2.0))),
      DataPipe((i: Image) => i.argb.flatten.map(_.toByte)),
      4, resample)

    val trainImages = tf.data.TensorSlicesDataset(dataSet.trainData)

    val train_labels = dataSet.trainLabels

    val labels_mean = dataSet.trainLabels.mean(axes = Tensor(0))

    val labels_stddev = dataSet.trainLabels.subtract(labels_mean).square.mean(axes = Tensor(0)).sqrt

    val norm_train_labels = train_labels.subtract(labels_mean).divide(labels_stddev)

    val trainLabels = tf.data.TensorSlicesDataset(norm_train_labels)

    val trainData =
      trainImages.zip(trainLabels)
        .repeat()
        .shuffle(10000)
        .batch(64)
        .prefetch(10)

    /*
    * Start building tensorflow network/graph
    * */
    println("Building the regression model.")
    val input = tf.learn.Input(
      UINT8,
      Shape(
        -1,
        dataSet.trainData.shape(1),
        dataSet.trainData.shape(2),
        dataSet.trainData.shape(3))
    )

    val num_outputs = collated_data.head._2._2.length

    val trainInput = tf.learn.Input(FLOAT64, Shape(-1, num_outputs))

    val trainingInputLayer = tf.learn.Cast("TrainInput", INT64)

    val lossFunc = DynamicRBFSWLoss("Loss/DynamicRBFWeightedL2", num_outputs)

    val loss = lossFunc >>
      tf.learn.Mean("Loss/Mean") >>
      tf.learn.ScalarSummary("Loss", "ModelLoss")

    val optimizer = tf.train.AdaGrad(0.002)

    val summariesDir = java.nio.file.Paths.get(tf_summary_dir.toString())

    //Now create the model
    val (model, estimator) = dtflearn.build_tf_model(
      arch, input, trainInput, trainingInputLayer,
      loss, optimizer, summariesDir,
      dtflearn.max_iter_stop(iterations))(
      trainData)

    val predictions: (Tensor, Tensor) = estimator.infer(() => dataSet.testData)

    val pred_targets = predictions._1
      .multiply(labels_stddev(0))
      .add(labels_mean(0))

    val pred_time_lags = predictions._2(::, 1)

    val pred_time_scales = predictions._2(::, 2)

    val metrics = new HeliosOmniTSMetrics(
      dtf.stack(Seq(pred_targets, pred_time_lags), axis = 1), dataSet.testLabels,
      dataSet.testLabels.shape(1),
      pred_time_scales
    )

    (model, estimator, metrics, tf_summary_dir, labels_mean, labels_stddev, collated_data)
  }*/


}
