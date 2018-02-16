package io.github.mandar2812.PlasmaML.helios.core

import io.github.mandar2812.dynaml.evaluation.MetricsTF
import org.platanios.tensorflow.api._
import _root_.io.github.mandar2812.dynaml.tensorflow._

/**
  *
  * */
//TODO:: Check implementation
class HeliosOmniTSMetrics(
  predictions: Tensor, targets: Tensor,
  size_causal_window: Int, time_scale: Double) extends
  MetricsTF(Seq("weighted_avg_err"), predictions, targets) {

  private[this] val scaling = Tensor(size_causal_window.toDouble)

  override protected def run(): Tensor = {

    val y = predictions(::, 0)

    val timelags = predictions(::, 1).sigmoid.multiply(scaling).cast(INT32)

    val size_batch = predictions.shape(0)

    val repeated_times = dtf.stack(Seq.fill(size_causal_window)(timelags), axis = -1)

    val index_times = Tensor((0 until size_causal_window).map(_.toDouble)).reshape(Shape(size_causal_window))

    val repeated_index_times = dtf.stack(Seq.fill(size_batch)(index_times), axis = 0)

    val repeated_preds = dtf.stack(Seq.fill(size_causal_window)(y), axis = -1)

    val convolution_kernel = (repeated_index_times - repeated_times)
      .square
      .multiply(-0.5)
      .divide(time_scale)
      .exp

    val weighted_loss_tensor =
      (repeated_preds - targets)
        .multiply(convolution_kernel)
        .sum(axes = 1)
        .divide(convolution_kernel.sum(axes = 1))
        .mean()

    weighted_loss_tensor
  }
}
