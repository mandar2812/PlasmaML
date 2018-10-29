import _root_.io.github.mandar2812.dynaml.tensorflow._
import _root_.io.github.mandar2812.dynaml.pipes._
import _root_.io.github.mandar2812.dynaml.repl.Router.main
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.ops.training.optimizers.Optimizer
import _root_.io.github.mandar2812.PlasmaML.utils._

import $file.timelagutils

@main
def main(
  d: Int                        = 3,
  n: Int                        = 100,
  sliding_window: Int           = 15,
  noise: Double                 = 0.5,
  noiserot: Double              = 0.1,
  alpha: Double                 = 0.0,
  train_test_separate: Boolean  = false,
  num_neurons: Seq[Int]         = Seq(40),
  iterations: Int               = 150000,
  miniBatch: Int                = 32,
  optimizer: Optimizer          = tf.train.AdaDelta(0.01),
  sum_dir_prefix: String        = "const_a",
  reg: Double                   = 0.01,
  p: Double                     = 1.0,
  time_scale: Double            = 1.0,
  corr_sc: Double               = 2.5,
  c_cutoff: Double              = 0.0,
  prior_wt: Double              = 1d,
  c: Double                     = 1d,
  prior_type: String            = "Hellinger",
  temp: Double                  = 1.0,
  error_wt: Double              = 1.0,
  mo_flag: Boolean              = true,
  prob_timelags: Boolean        = true,
  dist_type: String             = "default",
  timelag_pred_strategy: String = "mode"): timelagutils.ExperimentResult = {

  //Output computation
  val beta = 100f
  val compute_output = DataPipe(
    (v: Tensor) =>
      (
        v.square.mean().scalar.asInstanceOf[Float]*beta*0.5f/d,
        beta*0.05f
      )
  )

  //Time Lag Computation
  // 1/2*a*t^2 + u*t - s = 0
  // t = (-u + sqrt(u*u + 2as))/a
  val distance = beta*10

  val compute_time_lag = DataPipe((va: (Float, Float)) => {
    val (v, a) = va
    val dt = (math.sqrt(v*v + 2*a*distance).toFloat - v)/a
    val vf = math.sqrt(v*v + 2f*a*distance).toFloat
    (dt, vf + scala.util.Random.nextGaussian().toFloat)
  })

  val num_pred_dims = timelagutils.get_num_output_dims(sliding_window, mo_flag, prob_timelags, dist_type)

  val (net_layer_sizes, layer_shapes, layer_parameter_names, layer_datatypes) =
    timelagutils.get_ffnet_properties(d, num_pred_dims, num_neurons)

  val output_mapping = timelagutils.get_output_mapping(sliding_window, mo_flag, prob_timelags, dist_type, time_scale)

  //Prediction architecture
  val architecture = dtflearn.feedforward_stack(
    timelagutils.getAct(1), FLOAT64)(
    net_layer_sizes.tail) >> output_mapping


  val lossFunc = timelagutils.get_loss(
    sliding_window, mo_flag,
    prob_timelags, p, time_scale,
    corr_sc, c_cutoff,
    prior_wt, prior_type,
    temp, error_wt, c)

  val loss = lossFunc >>
    L2Regularization(layer_parameter_names, layer_datatypes, layer_shapes, reg) >>
    tf.learn.ScalarSummary("Loss", "ModelLoss")

  val dataset: timelagutils.TLDATA = timelagutils.generate_data(
    d, n, sliding_window,
    noise, noiserot, alpha,
    compute_output > compute_time_lag)

  if(train_test_separate) {
    val dataset_test: timelagutils.TLDATA = timelagutils.generate_data(
      d, n, sliding_window,
      noise, noiserot, alpha,
      compute_output > compute_time_lag)

    timelagutils.run_exp2(
      (dataset, dataset_test),
      architecture, loss,
      iterations, optimizer,
      miniBatch, sum_dir_prefix,
      mo_flag, prob_timelags,
      timelag_pred_strategy)
  } else {

    timelagutils.run_exp(
      dataset, architecture, loss,
      iterations, optimizer,
      miniBatch,
      sum_dir_prefix,
      mo_flag, prob_timelags,
      timelag_pred_strategy)
  }
}