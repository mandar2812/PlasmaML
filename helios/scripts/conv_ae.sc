import ammonite.ops._
import org.joda.time._
import io.github.mandar2812.dynaml.repl.Router.main
import io.github.mandar2812.dynaml.pipes.DataPipe
import io.github.mandar2812.dynaml.tensorflow.{dtflearn, dtfutils}
import _root_.io.github.mandar2812.PlasmaML.helios
import _root_.io.github.mandar2812.PlasmaML.helios.data
import io.github.mandar2812.PlasmaML.helios.core.AutoEncoder
import io.github.mandar2812.PlasmaML.helios.data.{SDO, SOHO, SOHOData, SolarImagesSource}
import io.github.mandar2812.PlasmaML.helios.data.SDOData.Instruments._
import io.github.mandar2812.PlasmaML.helios.data.SOHOData.Instruments._
import _root_.io.github.mandar2812.dynaml.tensorflow.layers.{L2Regularization, L1Regularization}
import org.platanios.tensorflow.api.Output
import org.platanios.tensorflow.api.learn.{Mode, StopCriteria}
import org.platanios.tensorflow.api.learn.layers.Layer
import org.platanios.tensorflow.api.{FLOAT32, FLOAT64, tf}
import org.platanios.tensorflow.api.ops.training.optimizers.Optimizer

@main
def main[T <: SolarImagesSource](
  year_range: Range             = 2011 to 2017,
  test_year: Int                = 2015,
  image_source: T               = SOHO(MDIMAG, 512),
  buffer_size: Int              = 2000,
  image_hist: Int               = 0,
  image_hist_downsamp: Int      = 0,
  opt: Optimizer                = tf.train.AdaDelta(0.01),
  reg: Double                   = 0.001,
  stop_criteria: StopCriteria   = dtflearn.max_iter_stop(5000),
  miniBatch: Int                = 16,
  tmpdir: Path                  = root/"home"/System.getProperty("user.name")/"tmp",
  path_to_images: Option[Path]  = None,
  existingModelDir: String      = "") = {

  //Data with MDI images

  print("Running experiment with test split from year: ")
  pprint.pprintln(test_year)

  data.buffer_size_(buffer_size)

  val dataset = data.generate_image_data[T](
    year_range, image_source,
    images_data_dir = path_to_images)

  println("Starting data set created.")
  println("Proceeding to load images & labels into Tensors ...")

  val test_start     = new DateTime(test_year, 1, 1, 0, 0)
  val test_end       = new DateTime(test_year, 12, 31, 23, 59)

  val tt_partition   = (p: (DateTime, Path)) =>
    if (p._1.isAfter(test_start) && p._1.isBefore(test_end)) false
    else true


  val (image_sizes, magic_ratio) = image_source match {
    case SOHO(_, s) => (s, 268.0/512.0)
    case SDO(_, s)  => (s, 333.0/512.0)
  }

  val (image_filter, num_channels, image_to_byte) = data.image_process_metadata(image_source)

  val patch_range = data.get_patch_range(magic_ratio, image_sizes)

  val image_preprocess = data.image_central_patch(magic_ratio, image_sizes) > data.image_scale(0.5)

  //Set the path of the summary directory
  val summary_dir_prefix  = "ae_"+image_source.toString
  val dt                  = DateTime.now()
  val summary_dir_postfix = "_"+dt.toString("YYYY-MM-dd-HH-mm")

  val (summary_dir , reuse): (String, Boolean)  =
    if(existingModelDir.isEmpty) (summary_dir_prefix+summary_dir_postfix, false)
    else (existingModelDir, true)

  if(reuse) println("\nReusing existing model in directory: "+existingModelDir+"\n")

  /*
  * Construct architecture components.
  *
  * 1) Convolutional segment: Consists of two convolutional pyramids interspersed by batch normalisation
  * */

  val filter_depths_stack1 = Seq(
    Seq(15, 15, 15, 15),
    Seq(10, 10, 10, 10)
  )

  val filter_depths_stack2 = Seq(
    Seq(5, 5, 5, 5),
    Seq(1, 1, 1, 1)
  )

  val identity_act = DataPipe[String, Layer[Output, Output]](dtflearn.identity(_))

  val conv_section = tf.learn.Cast("Input/Cast", FLOAT32) >>
    dtflearn.inception_stack(
      num_channels*(image_hist_downsamp + 1),
      filter_depths_stack1, identity_act,
      use_batch_norm = false)(1) >>
    dtflearn.batch_norm("BatchNorm_1") >>
    tf.learn.ReLU("ReLU_1", 0.01f) >>
    dtflearn.inception_stack(
      filter_depths_stack1.last.sum, filter_depths_stack2,
      identity_act, use_batch_norm = false)(5) >>
    dtflearn.batch_norm("BatchNorm_2") >>
    tf.learn.ReLU("ReLU_2", 0.01f)


  val deconv_section = tf.learn.Cast("Enc/Cast", FLOAT32) >>
    dtflearn.conv2d(
      size = 3,
      filter_depths_stack2.last.sum,
      num_channels*(image_hist_downsamp + 1),
      (1, 1))(index = 7) >>
    tf.learn.ReLU("ReLU_7", 0f)

  val architecture = AutoEncoder("ConvAE", conv_section, deconv_section)


  val loss_func = new Layer[(Output, (Output, Output)), (Output, Output)]("SeparateOutputs") {
    override val layerType = "SepOutputs"
    override protected def _forward(input: (Output, (Output, Output)))(implicit mode: Mode): (Output, Output) =
      (input._1, input._2._2)
    }

  helios.run_unsupervised_experiment(
    dataset, tt_partition,
    preprocess_image = image_preprocess > image_filter,
    image_to_bytearr = image_to_byte,
    num_channels_image = num_channels,
    image_history = image_hist,
    image_history_downsampling = image_hist_downsamp,
    processed_image_size = (patch_range.length, patch_range.length))(
    summary_dir, stop_criteria, tmpdir,
    arch = architecture,
    lossFunc = loss_func >> tf.learn.L2Loss("Loss/L2"),
    optimizer = opt,
    reuseExistingModel = reuse)

}
