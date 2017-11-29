/*
* External Imports
* */
//Ammonite imports
import ammonite.ops._
import ammonite.ops.ImplicitWd._
//Import breeze for linear algebra
import breeze.linalg.{DenseVector, DenseMatrix}
import breeze.stats.distributions._
//Apache Spark for big data support
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
//Load Wisp-Highcharts for plotting
import com.quantifind.charts.Highcharts._
//Import spire implicits for definition of
//fields, algebraic structures on primitive types
import spire.implicits._
/*
* DynaML imports
* */
import io.github.mandar2812.dynaml.analysis.VectorField
//The pipes API
import io.github.mandar2812.dynaml.pipes._
import io.github.mandar2812.dynaml.DynaMLPipe
import io.github.mandar2812.dynaml.DynaMLPipe._
//Load the DynaML model api members
import io.github.mandar2812.dynaml.models._
import io.github.mandar2812.dynaml.models.neuralnets._
import io.github.mandar2812.dynaml.models.svm._
import io.github.mandar2812.dynaml.models.lm._
//Utility functions
import io.github.mandar2812.dynaml.utils
//Kernels for GP,SVM models
import io.github.mandar2812.dynaml.kernels._
//Load neural net primitives
import io.github.mandar2812.dynaml.models.neuralnets.TransferFunctions._
//The probability API
import io.github.mandar2812.dynaml.probability._
import io.github.mandar2812.dynaml.probability.distributions._
//Wavelet API
import io.github.mandar2812.dynaml.wavelets._
//OpenML support
//Renjin imports
import javax.script._
import org.renjin.script._
import org.renjin.sexp._

import io.github.mandar2812.PlasmaML.omni._
import io.github.mandar2812.PlasmaML.vanAllen._
import io.github.mandar2812.PlasmaML.dynamics.diffusion._

val r_engine_factory = new RenjinScriptEngineFactory()
implicit val renjin = r_engine_factory.getScriptEngine()
val r: String => SEXP = (s: String) => renjin.eval(s).asInstanceOf[SEXP]
val R: java.io.File => SEXP = (f: java.io.File) => renjin.eval(f).asInstanceOf[SEXP]

