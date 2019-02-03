import ammonite.ops._
import org.joda.time._
import io.github.mandar2812.dynaml.repl.Router.main
import io.github.mandar2812.dynaml.tensorflow.{dtflearn, dtfutils}
import io.github.mandar2812.dynaml.pipes._
import io.github.mandar2812.PlasmaML.helios
import io.github.mandar2812.PlasmaML.helios.data
import io.github.mandar2812.PlasmaML.helios.data.{SDO, SOHO, SOHOData, SolarImagesSource}
import io.github.mandar2812.PlasmaML.helios.data.SDOData.Instruments._
import io.github.mandar2812.PlasmaML.helios.data.SOHOData.Instruments._
import _root_.io.github.mandar2812.dynaml.tensorflow.layers.{L2Regularization, L1Regularization}
import org.platanios.tensorflow.api.learn.StopCriteria
import org.platanios.tensorflow.api.{::, FLOAT32, FLOAT64, Shape, tf}
import org.platanios.tensorflow.api.ops.training.optimizers.Optimizer


@main
def main[T <: SolarImagesSource](
  year_range: Range             = 2001 to 2004,
  test_year: Int                = 2003,
  image_source: T               = SOHO(SOHOData.Instruments.MDIMAG, 512),
  re: Boolean                   = true,
  buffer_size: Int              = 2000,
  time_horizon: (Int, Int)      = (18, 56),
  time_history: Int             = 8,
  image_hist: Int               = 0,
  image_hist_downsamp: Int      = 0,
  conv_ff_stack_sizes: Seq[Int] = Seq(512, 256),
  hist_ff_stack_sizes: Seq[Int] = Seq(32, 16),
  ff_stack: Seq[Int]            = Seq(128, 64),
  opt: Optimizer                = tf.train.AdaDelta(0.01),
  reg: Double                   = 0.001,
  prior_wt: Double              = 0.85,
  error_wt: Double              = 1.0,
  temp: Double                  = 0.75,
  stop_criteria: StopCriteria   = dtflearn.max_iter_stop(5000),
  miniBatch: Int                = 16,
  tmpdir: Path                  = root/"home"/System.getProperty("user.name")/"tmp",
  path_to_images: Option[Path]  = None,
  existingModelDir: String      = "") = {


  print("Running experiment with test split from year: ")
  pprint.pprintln(test_year)

  helios.data.buffer_size_(buffer_size)

  val dataset = helios.data.generate_data_omni_ext[T](
    year_range, image_source,
    deltaT = time_horizon,
    history = time_history,
    images_data_dir = path_to_images)

  println("Starting data set created.")
  println("Proceeding to load images & labels into Tensors ...")
  val sw_threshold   = 700.0

  val test_start     = new DateTime(test_year, 1, 1, 0, 0)

  val test_end       = new DateTime(test_year, 12, 31, 23, 59)

  val tt_partition   = (p: (DateTime, (Path, (Seq[Double], Seq[Double])))) =>
    if (p._1.isAfter(test_start) && p._1.isBefore(test_end) && p._2._2._2.max >= sw_threshold) false
    else true


  val (image_sizes, magic_ratio) = image_source match {
    case SOHO(_, s) => (s, 268.0/512.0)
    case SDO(_, s)  => (s, 333.0/512.0)
  }

  val (image_filter, num_channels, image_to_byte) = data.image_process_metadata(image_source)

  val patch_range = data.get_patch_range(magic_ratio, image_sizes)

  val image_preprocess = data.image_central_patch(magic_ratio, image_sizes) > data.image_scale(0.5)

  //Set the path of the summary directory
  val summary_dir_prefix  = "swtl_"+image_source.toString
  val dt                  = DateTime.now()
  val summary_dir_postfix =
    if(re) "_re_"+dt.toString("YYYY-MM-dd-HH-mm")
    else "_"+dt.toString("YYYY-MM-dd-HH-mm")

  val (summary_dir , reuse): (String, Boolean)  =
    if(existingModelDir.isEmpty) (summary_dir_prefix+summary_dir_postfix, false)
    else (existingModelDir, true)

  if(reuse) println("\nReusing existing model in directory: "+existingModelDir+"\n")

  val causal_horizon = dataset.data.head._2._2._2.length
  val num_pred_dims  = 2*causal_horizon
  val ff_stack_sizes = ff_stack ++ Seq(num_pred_dims)
  val ff_index_conv  = 1
  val ff_index_hist  = ff_index_conv + conv_ff_stack_sizes.length
  val ff_index_fc    = ff_index_hist + hist_ff_stack_sizes.length


  val filter_depths = Seq(
    Seq(15, 15, 15, 15),
    Seq(10, 10, 10, 10),
    Seq(5, 5, 5, 5),
    Seq(1, 1, 1, 1)
  )

  val conv_ff_stack = dtflearn.feedforward_stack(
    (i: Int) => dtflearn.Phi("Act_"+i),
    FLOAT64)(
    conv_ff_stack_sizes,
    starting_index = ff_index_conv)


  val image_neural_stack = tf.learn.Cast("Input/Cast", FLOAT32) >>
    dtflearn.inception_stack(
      num_channels*(image_hist_downsamp + 1),
      filter_depths, tf.learn.ReLU(_),
      use_batch_norm = true)(starting_index = 1) >>
    tf.learn.Flatten("Flatten_1") >>
    conv_ff_stack

  val omni_history_stack = {
      tf.learn.Cast("Cast_Hist", FLOAT64) >>
        dtflearn.feedforward_stack(
          (i: Int) => dtflearn.Phi("Act_"+i),
          FLOAT64)(
          hist_ff_stack_sizes,
          starting_index = ff_index_hist)
  }

  val fc_stack = dtflearn.feedforward_stack(
    (i: Int) => dtflearn.Phi("Act_"+i),
    FLOAT64)(
    ff_stack_sizes,
    starting_index = ff_index_fc)

  val output_mapping = helios.learn.cdt_loss.output_mapping("Output/CDT-SW", causal_horizon)

  val architecture = dtflearn.tuple2_layer("OmniCTLStack", image_neural_stack, omni_history_stack) >>
    dtflearn.concat_tuple2("StackFeatures", axis = 1) >>
    fc_stack >>
    output_mapping

  val (_, layer_shapes_conv, layer_parameter_names_conv, layer_datatypes_conv) =
    dtfutils.get_ffstack_properties(
      -1, conv_ff_stack_sizes.last,
      conv_ff_stack_sizes.dropRight(1),
      starting_index = ff_index_conv,
      dType = "FLOAT64")

  val (_, layer_shapes_hist, layer_parameter_names_hist, layer_datatypes_hist) =
    dtfutils.get_ffstack_properties(
      -1, hist_ff_stack_sizes.last,
      hist_ff_stack_sizes.dropRight(1),
      dType = "FLOAT64",
      starting_index = ff_index_hist)

  val (_, layer_shapes_fc, layer_parameter_names_fc, layer_datatypes_fc) =
    dtfutils.get_ffstack_properties(
      -1, ff_stack_sizes.last,
      ff_stack_sizes.dropRight(1),
      dType = "FLOAT64",
      starting_index = ff_index_fc)

  val loss_func = helios.learn.cdt_loss(
    "Loss/CDT-SW",
    causal_horizon,
    prior_wt = prior_wt,
    temperature = temp) >>
    L2Regularization(
      layer_parameter_names_conv ++ layer_parameter_names_hist ++ layer_parameter_names_fc,
      layer_datatypes_conv ++ layer_datatypes_hist ++ layer_datatypes_fc,
      layer_shapes_conv ++ layer_shapes_hist ++ layer_shapes_fc,
      reg)

  helios.run_cdt_experiment_omni_ext(
    dataset, tt_partition, resample = re,
    preprocess_image = image_preprocess > image_filter,
    image_to_bytearr = image_to_byte,
    num_channels_image = num_channels,
    image_history = image_hist,
    image_history_downsampling = image_hist_downsamp,
    processed_image_size = (patch_range.length, patch_range.length))(
    summary_dir, stop_criteria, tmpdir,
    arch = architecture,
    lossFunc = loss_func,
    optimizer = opt)

}
