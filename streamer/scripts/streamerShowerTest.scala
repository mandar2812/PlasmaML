import breeze.linalg.DenseVector
import io.github.mandar2812.PlasmaML.streamer.StreamerShowerEmulator
import io.github.mandar2812.dynaml.kernels._

import io.github.mandar2812.dynaml.analysis.VectorField

val num_features = 5
implicit val ev = VectorField(num_features)


val stK = new TStudentKernel(2.2)
val sqExpK = new SEKernel(1.5, 0.7)
val tKernel = new TStudentKernel(0.5+1.0/num_features)
tKernel.blocked_hyper_parameters = tKernel.hyper_parameters
val mlpKernel = new MLPKernel(10.0/num_features.toDouble, 1.382083995440671)

sqExpK.blocked_hyper_parameters = List("amplitude")
val linearK = new PolynomialKernel(1, 0.0)
linearK.blocked_hyper_parameters = linearK.hyper_parameters

val perKernel = new PeriodicKernel(4.5, 2.5)

val cauK = new CauchyKernel(2.1)
cauK.blocked_hyper_parameters = cauK.hyper_parameters

val n = new DiracKernel(0.01)
n.blocked_hyper_parameters = n.hyper_parameters

StreamerShowerEmulator.globalOpt = "GS"

StreamerShowerEmulator.trainingSize = 6000
StreamerShowerEmulator.testSize = 2000

val resStreamerSTP = StreamerShowerEmulator(linearK + mlpKernel + tKernel, n, 4.0, 2, 0.2, false, 15)

resStreamerSTP.print

val resStreamerGP = StreamerShowerEmulator(linearK + mlpKernel + tKernel, n, 3, 0.2, false, 25)

resStreamerGP.print
