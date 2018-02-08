package io.github.mandar2812.PlasmaML.helios.core

import org.platanios.tensorflow.api.learn.Mode
import org.platanios.tensorflow.api.learn.layers._
import org.platanios.tensorflow.api.ops
import org.platanios.tensorflow.api.ops.Output

/**
  * <h3>Weighted L2 Loss Function</h3>
  * */
class WeightedL2FluxLoss(
  override val name: String)
  extends Loss[(Output, Output)](name) {

  override val layerType: String = "WeightedL2FluxLoss"

  override protected def _forward(input: (Output, Output), mode: Mode): Output =
    ops.NN.l2Loss((input._1 - input._2)*input._2.sigmoid.square, name = name)
}

