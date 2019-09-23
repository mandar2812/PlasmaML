import _root_.io.github.mandar2812.dynaml.{utils => dutils}
import $file.csss
import $file.csss_pdt_model_tuning
import $file.csss_so_tuning
import $file.env
import _root_.io.github.mandar2812.dynaml.repl.Router.main
import org.joda.time._
import ammonite.ops._
import ammonite.ops.ImplicitWd._
import _root_.io.github.mandar2812.PlasmaML.helios.core.timelag
import _root_.io.github.mandar2812.PlasmaML.omni.OMNIData
import org.platanios.tensorflow.api._

@main
def main(
  csss_job_id: String = "",
  test_year: Int = 2015,
  test_month: Int = 10,
  test_rotation: Int = -1,
  network_size: Seq[Int] = Seq(50, 50)
) = {

  val dt = DateTime.now()

  val csss_exp = csss_pdt_model_tuning(
    csss_job_id = if(csss_job_id != "") Some(csss_job_id) else None,
    start_year = 2008,
    end_year = 2016,
    test_year = test_year,
    test_month = test_month,
    test_rotation = if(test_rotation == -1) None else Some(test_rotation),
    crop_latitude = 90d,
    sw_threshold = 600d,
    fraction_pca = 1d,
    fte_step = 0,
    history_fte = 0,
    log_scale_omni = false,
    log_scale_fte = true,
    time_window = csss.time_window,
    ts_transform_output = csss.median_sw_6h,
    network_size = network_size,
    use_persistence = true,
    activation_func = (i: Int) => timelag.utils.getReLUAct3[Double](1, 1, i, 0f),
    hyper_optimizer = "gs",
    num_samples = 4,
    quantity = OMNIData.Quantities.V_SW,
    omni_ext = Seq(
      OMNIData.Quantities.sunspot_number,
      OMNIData.Quantities.F10_7
    ),
    reg_type = "L2",
    batch_size = 128,
    max_iterations = csss.ext_iterations,
    max_iterations_tuning = csss.base_iterations,
    pdt_iterations_tuning = csss.base_it_pdt,
    pdt_iterations_test = csss.ext_it_pdt,
    optimization_algo = tf.train.Adam(0.001f),
    summary_dir = env.summary_dir,
    get_training_preds = true,
    data_scaling = "hybrid",
    use_copula = true,
    existing_exp = None
  )

  try {
    %%(
      'Rscript,
      csss.script,
      csss_exp.results.summary_dir,
      csss.scatter_plots_test(csss_exp.results.summary_dir).last,
      "test_"
    )
  } catch {
    case e: Exception => e.printStackTrace()
  }


  println("CDT Model Performance:")
  csss_exp.results.metrics_test.get.print()

}
