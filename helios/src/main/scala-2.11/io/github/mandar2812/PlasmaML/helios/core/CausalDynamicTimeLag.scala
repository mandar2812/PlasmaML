package io.github.mandar2812.PlasmaML.helios.core

import io.github.mandar2812.dynaml.utils.annotation.Experimental
import io.github.mandar2812.dynaml.tensorflow._
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.learn.Mode
import org.platanios.tensorflow.api.learn.layers.{Layer, Loss}
import org.platanios.tensorflow.api.ops.Output

/**
  * <h3>Causal Dynamic time-lag Inference: Weighted Loss</h3>
  *
  * The loss functions assumes the prediction architecture
  * returns 2 &times; h number of outputs, where h = [[size_causal_window]].
  *
  * The first h outputs are predictions of the target signal, for
  * each time step in the horizon.
  *
  * The next h outputs are unscaled probability predictions regarding
  * the causal time lag between the input and output signals, computed
  * for any input pattern..
  *
  * The loss term for each data sample in the mini-batch is computed
  * independently as follows.
  *
  * L = &Sigma; <sub>i</sub> (|f<sub>i</sub> - y<sub>i</sub>|<sup>2</sup> &times; (1 + c &times; p<sub>i</sub>)
  * + &gamma; (&sqrt; p<sup>*</sup><sub>i</sub> - &sqrt; p<sub>i</sub>)<sup>2</sup>)
  *
  * @param name A string identifier for the loss instance.
  * @param size_causal_window The size of the finite time horizon
  *                           to look for causal effects.
  * @param prior_wt The multiplicative constant applied on the
  *                 "prior" term; i.e. a divergence term between the
  *                 predicted probability distribution of the
  *                 time lags and the so called
  *                 "target probability distribution".
  * @param temperature The Gibbs temperature which scales the softmax probability
  *                    transformation while computing the target probability distribution.
  *
  * @param specificity Refers to the weight `c` in loss expression, a higher value of c
  *                    encourages the model to focus more on the learning input-output
  *                    relationships for the time steps which it perceives as more probable
  *                    to contain the causal time lag.
  *
  * @param divergence The kind of divergence term to be used as a prior over the probability
  *                   distribution predicted for the time lag. Available options include.
  *                   <ul>
  *                     <li>
  *                       <a href="https://en.wikipedia.org/wiki/Hellinger_distance">
  *                         Hellinger distance</a> (default)
  *                     </li>
  *                     <li>
  *                       <a href="https://en.wikipedia.org/wiki/Kullback–Leibler_divergence">
  *                         Kullback-Leibler Divergence</a>
  *                     </li>
  *                     <li>
  *                       <a href="https://en.wikipedia.org/wiki/Cross_entropy">Cross Entropy</a>
  *                     </li>
  *                     <li>
  *                       <a href="https://en.wikipedia.org/wiki/Jensen–Shannon_divergence">
  *                         Jensen-Shannon Divergence</a>
  *                     </li>
  *                   </ul>
  *
  * */
case class CausalDynamicTimeLag(
  override val name: String,
  size_causal_window: Int,
  prior_wt: Double = 1.5,
  error_wt: Double = 1.0,
  temperature: Double = 1.0,
  specificity: Double = 1.0,
  divergence: String = "Hellinger") extends
  Loss[((Output, Output), Output)](name) {

  override val layerType: String = s"WTSLoss[horizon:$size_causal_window]"

  override protected def _forward(input: ((Output, Output), Output))(implicit mode: Mode): Output = {

    val preds   = input._1._1
    val prob    = input._1._2
    val targets = input._2

    val model_errors = preds.subtract(targets)

    val target_prob =
      if(divergence == "Hellinger") model_errors.square.multiply(-1.0).divide(temperature).softmax()
      else dtf.tensor_f64(
        1, size_causal_window)(
        (1 to size_causal_window).map(_ => 1.0/size_causal_window):_*).toOutput

    def kl(p: Output, q: Output): Output = p.divide(q).log.multiply(p).sum(axes = 1).mean()

    val m = target_prob.add(prob).divide(2.0)

    val prior_term = divergence match {
      case "L2" =>
        target_prob.subtract(prob).square.sum(axes = 1).divide(2.0).mean()
      case "Hellinger" =>
        target_prob.sqrt.subtract(prob.sqrt).square.sum(axes = 1).divide(2.0).sqrt.mean()
      case "Jensen-Shannon" =>
        kl(target_prob, m).add(kl(prob, m)).multiply(0.5)
      case "Cross-Entropy" =>
        target_prob.multiply(prob.log).sum(axes = 1).multiply(-1.0).mean()
      case "Kullback-Leibler" =>
        kl(prob, target_prob)
      case _ =>
        Tensor(0.0).toOutput

    }

    model_errors.square
      .multiply(prob.multiply(specificity).add(1))
      .sum(axes = 1).mean()
      .multiply(error_wt)
      .add(prior_term.multiply(prior_wt))
  }
}

object CausalDynamicTimeLag {
  def output_mapping(name: String, size_causal_window: Int): Layer[Output, (Output, Output)] =
    new Layer[Output, (Output, Output)](name) {
      override val layerType: String = s"OutputWTSLoss[horizon:$size_causal_window]"

      override protected def _forward(input: Output)(implicit mode: Mode): (Output, Output) = {
        (input(::, 0::size_causal_window), input(::, size_causal_window::).softmax())
      }
    }
}

@Experimental
case class CausalDynamicTimeLagSO(
  override val name: String,
  size_causal_window: Int,
  prior_wt: Double = 1.5,
  temperature: Double = 1.0,
  specificity: Double = 1.0,
  divergence: String = "Hellinger") extends
  Loss[((Output, Output), Output)](name) {

  override val layerType: String = s"WTSLossSO[horizon:$size_causal_window]"

  override protected def _forward(input: ((Output, Output), Output))(implicit mode: Mode): Output = {

    val preds               = input._1._1
    val repeated_preds      = tf.stack(Seq.fill(size_causal_window)(preds), axis = -1)
    val prob                = input._1._2
    val targets             = input._2

    val model_errors = repeated_preds.subtract(targets)

    val prior_prob =
      if(divergence == "Hellinger") model_errors.square.multiply(-1.0).divide(temperature).softmax()
      else dtf.tensor_f64(
        1, size_causal_window)(
        (1 to size_causal_window).map(_ => 1.0/size_causal_window):_*).toOutput

    def kl(p: Output, q: Output): Output = p.divide(q).log.multiply(p).sum(axes = 1).mean()

    val m = prior_prob.add(prob).divide(2.0)

    val prior_term =
      if(divergence == "Jensen-Shannon") kl(prior_prob, m).add(kl(prob, m)).multiply(0.5)
      else if(divergence == "Hellinger") prior_prob.sqrt.subtract(prob.sqrt).square.sum(axes = 1).sqrt.divide(math.sqrt(2.0)).mean()
      else if(divergence == "Cross-Entropy") prior_prob.multiply(prob.log).sum(axes = 1).multiply(-1.0).mean()
      else if(divergence == "Kullback-Leibler") kl(prob, prior_prob)
      else Tensor(0.0).toOutput

    model_errors.square.multiply(prob.multiply(specificity).add(1)).sum(axes = 1).mean()
      .add(prior_term.multiply(prior_wt))
  }
}


@Experimental
object WeightedTimeSeriesLossBeta {
  def output_mapping(name: String, size_causal_window: Int): Layer[Output, (Output, Output)] =
    new Layer[Output, (Output, Output)](name) {
      override val layerType: String = s"OutputWTSLossBeta[horizon:$size_causal_window]"

      override protected def _forward(input: Output)(implicit mode: Mode): (Output, Output) = {
        val preds = input(::, 0::size_causal_window)
        val alpha_beta = input(::, size_causal_window::).square().add(1.0)


        val (alpha, beta) = (alpha_beta(::, 0), alpha_beta(::, 1))

        val (stacked_alpha, stacked_beta) = (
          tf.stack(Seq.fill(size_causal_window)(alpha), axis = 1),
          tf.stack(Seq.fill(size_causal_window)(beta),  axis = 1))

        val index_times   = Tensor(0 until size_causal_window).reshape(Shape(1, size_causal_window)).toOutput

        val n             = Tensor(size_causal_window - 1.0).toOutput
        val n_minus_t     = index_times.multiply(-1.0).add(n)

        val norm_const    = stacked_alpha.logGamma
          .add(stacked_beta.logGamma)
          .subtract(stacked_alpha.add(stacked_beta).logGamma)

        val prob          = index_times
          .add(stacked_alpha).logGamma
          .add(n_minus_t.add(stacked_beta).logGamma)
          .add(n.add(1.0).logGamma)
          .subtract(index_times.add(1.0).logGamma)
          .subtract(n_minus_t.add(1.0).logGamma)
          .subtract(norm_const).exp

        (preds, prob)
      }
    }
}

@Experimental
object WeightedTimeSeriesLossPoisson {
  def output_mapping(name: String, size_causal_window: Int): Layer[Output, (Output, Output)] =
    new Layer[Output, (Output, Output)](name) {
      override val layerType: String = s"OutputPoissonWTSLoss[horizon:$size_causal_window]"

      override protected def _forward(input: Output)(implicit mode: Mode): (Output, Output) = {

        val preds = input(::, 0::size_causal_window)

        val lambda = input(::, size_causal_window).square

        val stacked_lambda = tf.stack(Seq.fill(size_causal_window)(lambda), axis = 1)

        val index_times    = Tensor(0 until size_causal_window).reshape(Shape(1, size_causal_window)).toOutput

        val unsc_prob      = index_times.multiply(stacked_lambda.logGamma)
          .subtract(index_times.add(1.0).logGamma)
          .subtract(stacked_lambda).exp

        val norm_const     = unsc_prob.sum(axes = 1)

        val prob           = unsc_prob.divide(tf.stack(Seq.fill(size_causal_window)(norm_const), axis = 1))

        (preds, prob)
      }
    }
}

@Experimental
object WeightedTimeSeriesLossGaussian {
  def output_mapping(name: String, size_causal_window: Int): Layer[Output, (Output, Output)] =
    new Layer[Output, (Output, Output)](name) {
      override val layerType: String = s"OutputGaussianWTSLoss[horizon:$size_causal_window]"

      override protected def _forward(input: Output)(implicit mode: Mode): (Output, Output) = {

        val preds = input(::, 0::size_causal_window)

        val mean = input(::, size_causal_window)

        //Precision is 1/sigma^2
        val precision = input(::, size_causal_window + 1).square

        val stacked_mean = tf.stack(Seq.fill(size_causal_window)(mean), axis = 1)
        val stacked_pre  = tf.stack(Seq.fill(size_causal_window)(precision), axis = 1)

        val index_times    = Tensor(0 until size_causal_window).reshape(Shape(1, size_causal_window)).toOutput

        val unsc_prob      = index_times.subtract(stacked_mean).square.multiply(-0.5).multiply(stacked_pre).exp

        val norm_const     = unsc_prob.sum(axes = 1)

        val prob           = unsc_prob.divide(tf.stack(Seq.fill(size_causal_window)(norm_const), axis = 1))

        (preds, prob)
      }
    }
}


object CausalDynamicTimeLagSO {
  def output_mapping(name: String, size_causal_window: Int): Layer[Output, (Output, Output)] =
    new Layer[Output, (Output, Output)](name) {
      override val layerType: String = s"OutputWTSLossSO[horizon:$size_causal_window]"

      override protected def _forward(input: Output)(implicit mode: Mode): (Output, Output) = {
        (input(::, 0), input(::, 1::).softmax())
      }
    }
}


@Experimental
case class MOGrangerLoss(
  override val name: String,
  size_causal_window: Int,
  error_exponent: Double = 2.0,
  weight_error: Double,
  scale_lags: Boolean = true) extends
  Loss[((Output, Output), Output)](name) {

  override val layerType = s"MOGrangerLoss[horizon:$size_causal_window]"

  private[this] val scaling = Tensor(size_causal_window.toDouble-1d)

  override protected def _forward(input: ((Output, Output), Output))(implicit mode: Mode) = {

    val alpha = Tensor(1.0)
    val nu = Tensor(1.0)
    val q = Tensor(1.0)


    val predictions    = input._1._1
    val timelags       = input._1._2

    val targets        = input._2

    val repeated_times = tf.stack(Seq.fill(size_causal_window)(timelags), axis = -1)

    val index_times: Output = Tensor(
      (0 until size_causal_window).map(_.toDouble)
    ).reshape(
      Shape(size_causal_window)
    )

    val error_tensor = predictions.subtract(targets)

    val convolution_kernel_temporal = error_tensor
      .abs.pow(error_exponent)
      .l2Normalize(axes = 1)
      .square
      .subtract(1.0)
      .multiply(-1/2.0)

    val weighted_temporal_loss_tensor = repeated_times
      .subtract(index_times)
      .square
      .multiply(convolution_kernel_temporal)
      .sum(axes = 1)
      .divide(convolution_kernel_temporal.sum(axes = 1))
      .mean()

    error_tensor.square.sum(axes = 1).multiply(0.5*weight_error).mean().add(weighted_temporal_loss_tensor)
  }
}

object MOGrangerLoss {

  def output_mapping(
    name: String,
    size_causal_window: Int,
    scale_lags: Boolean = true): Layer[Output, (Output, Output)] =
    new Layer[Output, (Output, Output)](name) {

      override val layerType: String = s"OutputMOGrangerLoss[horizon:$size_causal_window]"

      private[this] val scaling = Tensor(size_causal_window.toDouble-1d)

      val alpha = Tensor(1.0)
      val nu    = Tensor(1.0)
      val q     = Tensor(1.0)

      override protected def _forward(input: Output)(implicit mode: Mode): (Output, Output) = {

        val lags = if (scale_lags) {
          input(::, -1)
            .multiply(alpha.add(1E-6).square.multiply(-1.0))
            .exp
            .multiply(q.square)
            .add(1.0)
            .pow(nu.square.pow(-1.0).multiply(-1.0))
            .multiply(scaling)
        } else {
          input(::, -1)
        }

        (input(::, 0::size_causal_window), lags)
      }
    }
}