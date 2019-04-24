import $exec.helios.scripts.csss
import $exec.helios.scripts.csss_pdt_model_tuning
import $exec.helios.scripts.env


val csss_exp = csss_pdt_model_tuning(
  start_year = 2010,
  end_year = 2016,
  test_year = 2015,
  sw_threshold = 600d,
  crop_latitude = 25d,
  fraction_pca = 1d,
  fte_step = 24,
  history_fte = 0,
  log_scale_omni = false,
  log_scale_fte = false,
  causal_window = (56, 56),
  network_size = Seq(50, 50),
  activation_func = (i: Int) => dtflearn.Phi(s"Act_$i"),
  hyper_optimizer = "gs",
  num_samples = 20,
  quantity = OMNIData.Quantities.V_SW,
  reg_type = "L2",
  batch_size = 128,
  max_iterations = 200000,
  max_iterations_tuning = 20000,
  optimization_algo = tf.train.Adam(0.001f),
  summary_dir = env.summary_dir,
  get_training_preds = false,
  existing_exp = None//csss.experiments().lastOption
)


try {
  %%(
    'Rscript,
    script,
    csss_exp.results.summary_dir,
    csss.scatter_plots_test(csss_exp.results.summary_dir).last,
    "test_"
  )
} catch {
  case e: Exception => e.printStackTrace()
}

helios.visualise_cdt_results(csss.scatter_plots_test(csss_exp.results.summary_dir).last)
