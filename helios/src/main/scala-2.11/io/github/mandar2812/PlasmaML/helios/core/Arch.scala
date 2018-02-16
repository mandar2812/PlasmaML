package io.github.mandar2812.PlasmaML.helios.core

import io.github.mandar2812.dynaml.tensorflow._
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.ops.NN.SamePadding

/**
  * A collection of architectures building blocks
  * for learning and predicting from solar images.
  *
  * */
object Arch {


  /**
    * CNN architecture for GOES XRay flux
    * */
  private[PlasmaML] val cnn_goes_v1 = {
    tf.learn.Cast("Input/Cast", FLOAT32) >>
      dtflearn.conv2d_unit(Shape(2, 2, 4, 64), (1, 1))(0) >>
      dtflearn.conv2d_unit(Shape(2, 2, 64, 32), (2, 2))(1) >>
      dtflearn.conv2d_unit(Shape(2, 2, 32, 16), (4, 4))(2) >>
      dtflearn.conv2d_unit(Shape(2, 2, 16, 8), (8, 8), dropout = false)(3) >>
      tf.learn.MaxPool("MaxPool_3", Seq(1, 2, 2, 1), 1, 1, SamePadding) >>
      tf.learn.Flatten("Flatten_3") >>
      tf.learn.Linear("FC_Layer_4", 128) >>
      tf.learn.ReLU("ReLU_4", 0.1f) >>
      tf.learn.Linear("FC_Layer_5", 64) >>
      tf.learn.ReLU("ReLU_5", 0.1f) >>
      tf.learn.Linear("FC_Layer_6", 8) >>
      tf.learn.Sigmoid("Sigmoid_6") >>
      tf.learn.Linear("OutputLayer", 1)
  }

  private[PlasmaML] val cnn_goes_v1_1 = {
    tf.learn.Cast("Input/Cast", FLOAT32) >>
      dtflearn.conv2d_unit(Shape(2, 2, 4, 64), (1, 1))(0) >>
      dtflearn.conv2d_unit(Shape(2, 2, 64, 32), (2, 2))(1) >>
      dtflearn.conv2d_unit(Shape(2, 2, 32, 16), (4, 4))(2) >>
      dtflearn.conv2d_unit(Shape(2, 2, 16, 8), (8, 8))(3) >>
      dtflearn.conv2d_unit(Shape(2, 2, 8, 4), (16, 16), dropout = false)(4) >>
      tf.learn.MaxPool("MaxPool_3", Seq(1, 2, 2, 1), 1, 1, SamePadding) >>
      tf.learn.Flatten("Flatten_3") >>
      tf.learn.Linear("FC_Layer_4", 64) >>
      tf.learn.SELU("SELU_5") >>
      tf.learn.Linear("FC_Layer_5", 8) >>
      tf.learn.Sigmoid("Sigmoid_5") >>
      tf.learn.Linear("OutputLayer", 1)
  }


  private[PlasmaML] val cnn_sw_v1 = {
    tf.learn.Cast("Input/Cast", FLOAT32) >>
      dtflearn.conv2d_unit(Shape(2, 2, 4, 64), (1, 1))(0) >>
      dtflearn.conv2d_unit(Shape(2, 2, 64, 32), (2, 2))(1) >>
      dtflearn.conv2d_unit(Shape(2, 2, 32, 16), (4, 4))(2) >>
      dtflearn.conv2d_unit(Shape(2, 2, 16, 8), (8, 8), dropout = false)(3) >>
      tf.learn.MaxPool("MaxPool_3", Seq(1, 2, 2, 1), 1, 1, SamePadding) >>
      tf.learn.Flatten("Flatten_3") >>
      tf.learn.Linear("FC_Layer_4", 128) >>
      tf.learn.ReLU("ReLU_4", 0.1f) >>
      tf.learn.Linear("FC_Layer_5", 64) >>
      tf.learn.ReLU("ReLU_5", 0.1f) >>
      tf.learn.Linear("FC_Layer_6", 8) >>
      tf.learn.Sigmoid("Sigmoid_6") >>
      tf.learn.Linear("OutputLayer", 2)
  }

}
