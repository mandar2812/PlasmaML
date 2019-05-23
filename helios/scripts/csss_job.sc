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

@main
def main(
  csss_job_id: String,
  test_year: Int = 2015,
  test_month: Int = 10,
  network_size: Seq[Int] = Seq(40, 60)
) = {

  val dt = DateTime.now()

  val csss_exp = csss_pdt_model_tuning(
    start_year = 2008,
    end_year = 2017,
    test_year = test_year,
    test_month = test_month,
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
    reg_type = "L2",
    batch_size = 128,
    max_iterations = csss.ext_iterations,
    max_iterations_tuning = csss.base_iterations,
    pdt_iterations_tuning = csss.base_it_pdt,
    pdt_iterations_test = csss.ext_it_pdt,
    optimization_algo = org.platanios.tensorflow.api.tf.train.Adam(0.001f),
    summary_dir = env.summary_dir / csss_job_id,
    get_training_preds = true,
    data_scaling = "gauss",
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


  val csss_fixed = csss_so_tuning.baseline(
    csss_exp.results.summary_dir,
    network_size = network_size,
    activation_func = (i: Int) => timelag.utils.getReLUAct3[Double](1, 1, i, 0f),
    optimization_algo = org.platanios.tensorflow.api.tf.train.Adam(0.01f),
    max_iterations = csss.ext_iterations,
    max_iterations_tuning = csss.base_iterations,
    batch_size = 128,
    num_samples = 4
  )

  println("CDT Model Performance:")
  csss_exp.results.metrics_test.get.print()

  println("Base Line Model Performance:")
  csss_fixed.results.metrics_test.get.print()

}
