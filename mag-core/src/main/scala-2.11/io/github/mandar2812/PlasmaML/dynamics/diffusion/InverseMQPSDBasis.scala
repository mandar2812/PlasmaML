package io.github.mandar2812.PlasmaML.dynamics.diffusion

import breeze.linalg.DenseVector
import io.github.mandar2812.dynaml.analysis.RadialBasis
import io.github.mandar2812.dynaml.pipes.{Basis, DataPipe}
import io.github.mandar2812.dynaml.analysis.implicits._
import spire.algebra.InnerProductSpace

/**
  * <h3>Phase Space Density: Inverse Multi-Quadric Mesh-Based Radial Basis<h3>
  *
  * Implements a inverse multi-quadric radial basis expansion
  * for the phase space density with nodes placed on a regular
  * space time mesh.
  *
  * */
class InverseMQPSDBasis(beta: Double)(
  lShellLimits: (Double, Double), nL: Int,
  timeLimits: (Double, Double), nT: Int,
  logScales: (Boolean, Boolean) = (false, false))
  extends PSDRadialBasis(
    lShellLimits, nL,
    timeLimits, nT,
    logScales) {

  require(beta > 0, "Beta parameter must be positive")

  val activation = RadialBasis.invMultiQuadric(beta)

  val field: InnerProductSpace[(Double, Double), Double] = innerProdTuple2

  override val f_l: ((Double, Double)) => DenseVector[Double] = (x: (Double, Double)) => {
    DenseVector(
      centers.zip(scales).map(cs => {
        val (c, (theta_s, _)) = cs

        val d = field.minus(x, c)

        val scaledDist = d._1/theta_s

        val g_l = 1d + scaledDist*scaledDist

        val invThetaS = 1/theta_s

        -beta*invThetaS*math.abs(d._1)/g_l
      }).toArray) *:*
      f(x)
  }

  override val f_ll: ((Double, Double)) => DenseVector[Double] = (x: (Double, Double)) => {
    DenseVector(
      centers.zip(scales).map(cs => {
        val (c, (theta_s, _)) = cs

        val d = field.minus(x, c)

        val scaledDist = d._1/theta_s

        val g_l = 1d + scaledDist*scaledDist

        val invThetaS = 1/theta_s

        val sq = (s: Double) => s*s

        beta*invThetaS*(sq(d._1)*(0.5*beta+1) - g_l)/math.pow(g_l, 2+0.5*beta)
      }).toArray) *:*
      f(x)
  }

  override val f_t: ((Double, Double)) => DenseVector[Double] = (x: (Double, Double)) => {

    DenseVector(
      centers.zip(scales).map(cs => {
        val (c, (_, theta_t)) = cs

        val d = field.minus(x, c)

        val scaledDist = d._2/theta_t

        val g_t = 1d + scaledDist*scaledDist

        val invThetaT = 1/theta_t

        -beta*invThetaT*math.abs(d._2)/g_t
      }).toArray) *:*
      f(x)
  }


  /**
    * Calculate the function which must be multiplied to the current
    * basis in order to obtain the operator transformed basis.
    **/
  /*override def operator_basis(
    diffusionField: DataPipe[(Double, Double), Double],
    diffusionFieldGradL: DataPipe[(Double, Double), Double],
    lossTimeScale: DataPipe[(Double, Double), Double]): Basis[(Double, Double)] =
    Basis((x: (Double, Double)) => {

      val dll = diffusionField(x)
      val alpha = diffusionFieldGradL(x) - 2d*diffusionField(x)/x._1
      val lambda = lossTimeScale(x)

      DenseVector(
        centers.zip(scales).map(c => {
          val (x_c, (theta_s, theta_t)) = c

          val d = field.minus(x, x_c)

          val scaledDist = (d._1/theta_s, d._2/theta_t)

          val f = 1d + field.dot(scaledDist, scaledDist)

          val sq = (s: Double) => s*s

          val (invThetaS, invThetaT) = (1/theta_s, 1/theta_t)

          beta*invThetaS*dll*(sq(d._1)*(0.5*beta+1) - f)/math.pow(f, 2+0.5*beta) -
            beta*invThetaS*alpha*math.abs(d._1)/f +
            beta*invThetaT*math.abs(d._2)/f -
            lambda
        }).toArray)
    })
*/
  override protected val f: ((Double, Double)) => DenseVector[Double] =
    (x: (Double, Double)) => DenseVector(
      centers.zip(scales).map(cs => {
        val d = field.minus(x, cs._1)
        val scaledDist = (d._1/cs._2._1, d._2/cs._2._2)
        val r = math.sqrt(field.dot(scaledDist, scaledDist))

        activation(r)
      }).toArray
    )
}
