package io.github.mandar2812.PlasmaML.helios.core

import org.platanios.tensorflow.api.learn.Mode
import org.platanios.tensorflow.api.learn.layers.Loss
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.ops.Output

/**
  * <h3>RBF-Kernel Weighted Solar Wind Loss (KSW Loss)</h3>
  *
  * A weighted loss function which enables fuzzy learning of
  * the solar wind propagation from heliospheric
  * images to ACE.
  *
  * @param name Unique string identifier of the loss/layer
  * @param size_causal_window The size of the finite time horizon, over
  *                           which time-lag and signal predictions are made.
  * @param kernel_time_scale  The time scale of the Radial Basis Function (RBF) convolution
  *                           kernel, defaults to 1.
  * @param kernel_norm_exponent The Lp norm used in the kernel, defaults to 2
  * @param corr_cutoff The cut-off applied to the Spearman correlation between
  *                    the predicted targets and time lags, defaults to -0.75.
  * @param prior_scaling A scaling parameter multiplier applied in the prior term, defaults to 1.
  * @param prior_weight The weight of the prior term in the loss-function, defaults to 1.
  * @param batch The batch size for each training epoch.
  * @author mandar2812
  * */
case class RBFWeightedSWLoss(
  override val name:    String,
  size_causal_window:   Int,
  kernel_time_scale:    Double = 3d,
  kernel_norm_exponent: Double = 2,
  corr_cutoff:          Double = -0.75,
  prior_weight:         Double = 1.0,
  prior_scaling:        Double = 1.0,
  batch:                Int    = -1,
  scale_lags: Boolean = true)
  extends Loss[(Output, Output)](name) {

  override val layerType: String = s"RBFSW[horizon:$size_causal_window, timescale:$kernel_time_scale]"

  private[this] val scaling = Tensor(size_causal_window.toDouble-1d)

  override protected def _forward(input: (Output, Output), mode: Mode): Output = {

    val predictions   = input._1(::, 0)
    val unscaled_lags = input._1(::, 1)
    val targets       = input._2


    //Perform scaling of time lags using the generalized logistic curve.
    //First define some parameters.

    //val alpha: tf.Variable = tf.variable("alpha", FLOAT32, Shape(), tf.RandomUniformInitializer())
    //val nu:    tf.Variable = tf.variable("nu",    FLOAT32, Shape(), tf.OnesInitializer)
    //val q:     tf.Variable = tf.variable("Q",     FLOAT32, Shape(), tf.OnesInitializer) 

    val alpha = Tensor(0.5)
    val nu = Tensor(1.0)
    val q = Tensor(1.0)

    val timelags           = if (scale_lags) {
      unscaled_lags
        .multiply(alpha.add(1E-6).square.multiply(-1.0))
        .exp
        .multiply(q.square)
        .add(1.0)
        .pow(nu.square.pow(-1.0).multiply(-1.0))
        .multiply(scaling)
        .floor
    } else {
      unscaled_lags.floor
    }

    //Determine the batch size, if not provided @TODO: Iron out bugs in this segment
    val batchSize =
      if(batch == -1) predictions.size.toInt/predictions.shape.toTensor().prod().scalar.asInstanceOf[Int]
      else batch

    //Sort the predicted targets and time lags, obtaining
    //the ranks of each element.
    val rank_preds = predictions.topK(batchSize)._2.cast(FLOAT32)
    val rank_unsc_lags = timelags.topK(batchSize)._2.cast(FLOAT32)

    //Standardize (mean center) the ranked tensors
    val (ranked_preds_mean, ranked_unscaled_lags_mean) = (rank_preds.mean(), rank_unsc_lags.mean())

    val (ranked_preds_std, ranked_unscaled_lags_std)   = (
      rank_preds.subtract(ranked_preds_mean).square.mean().sqrt,
      rank_unsc_lags.subtract(ranked_unscaled_lags_mean).square.mean().sqrt)

    val (norm_ranked_targets, norm_ranked_unsc_lags)   = (
      rank_preds.subtract(ranked_preds_mean).divide(ranked_preds_std),
      rank_unsc_lags.subtract(ranked_unscaled_lags_mean).divide(ranked_unscaled_lags_std))


    val repeated_times      = tf.stack(Seq.fill(size_causal_window)(timelags), axis = -1)

    val repeated_preds      = tf.stack(Seq.fill(size_causal_window)(predictions), axis = -1)

    val index_times: Output = Tensor(
      (0 until size_causal_window).map(_.toDouble)
    ).reshape(
      Shape(size_causal_window)
    )

    //Calculate the convolution kernel of the loss function.
    val convolution_kernel = repeated_times.subtract(index_times)
      .abs
      .pow(kernel_norm_exponent)
      .multiply(-1.0/kernel_norm_exponent)
      .divide(kernel_time_scale)
      .exp

    val convolution_kernel_temporal = repeated_preds.subtract(targets)
      .abs
      .pow(2.0)
      .multiply(-1/2.0)
      .divide(1.0)
      .exp

    //Convolve the kernel with the loss tensor, yielding the weighted loss tensor
    val weighted_loss_tensor = repeated_preds
      .subtract(targets)
      .square
      .multiply(convolution_kernel)
      .sum(axes = 1)
      .divide(convolution_kernel.sum(axes = 1))
      .mean()

    val weighted_temporal_loss_tensor = repeated_times
      .subtract(index_times)
      .square
      .multiply(convolution_kernel_temporal)
      .sum(axes = 1)
      .divide(convolution_kernel_temporal.sum(axes = 1))
      .mean()

    /*
    * Compute the prior term, which is an affine transformation
    * of the empirical Spearman Correlation between
    * the predicted targets and unscaled time lags.
    * */
    val prior =
      norm_ranked_targets.multiply(norm_ranked_unsc_lags)
        .mean()
        .subtract(corr_cutoff)
        .multiply(prior_scaling)

    val offset: Double = (1.0 - math.abs(corr_cutoff))*prior_scaling

    weighted_loss_tensor.add(weighted_temporal_loss_tensor).add(prior).add(offset)
  }
}
