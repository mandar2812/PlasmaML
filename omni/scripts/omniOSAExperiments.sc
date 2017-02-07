//DynaML imports
import io.github.mandar2812.dynaml.analysis.VectorField
import io.github.mandar2812.dynaml.kernels._
//Import Omni programs
import io.github.mandar2812.PlasmaML.omni._

val polynomialKernel = new PolynomialKernel(1, 0.5)
polynomialKernel.block("degree")

implicit val ev = VectorField(OmniOSA.input_dimensions)

val tKernel = new TStudentKernel(0.01)
tKernel.block_all_hyper_parameters

val rbfKernel = new RBFKernel(1.7)

val mlpKernel = new MLPKernel(80.0, 20.0)

val whiteNoiseKernel = new DiracKernel(0.2)
whiteNoiseKernel.block_all_hyper_parameters

OmniOSA.gridSize = 2
OmniOSA.gridStep = 0.2
OmniOSA.globalOpt = "ML"
OmniOSA.maxIterations = 250

OmniOSA.clearExogenousVars()
//Get test results for Linear GP-AR model
//with a mean function given by the persistence
//model
OmniOSA.setTarget(40, 10)
val resPolyAR = OmniOSA.buildAndTestGP(
  mlpKernel+tKernel,
  whiteNoiseKernel,
  OmniOSA.meanFuncPersistence)


OmniOSA.clearExogenousVars()
//Set solar wind speed and IMF Bz as exogenous variables
OmniOSA.setTarget(40, 6)
OmniOSA.setExogenousVars(List(24, 16), List(2,2))
//Reset kernel and noise to initial states
mlpKernel.setw(80.0)
mlpKernel.setoffset(20.0)
rbfKernel.setbandwidth(1.7)
OmniOSA.gridSize = 2
//Get test results for a GP-ARX model
//with a mean function given by the persistence
//model
val resPolyARX = OmniOSA.buildAndTestGP(
  tKernel+mlpKernel,
  whiteNoiseKernel,
  OmniOSA.meanFuncPersistence)


//Compare with base line of the Persistence model
val resPer = DstPersistenceMOExperiment(0)

OmniOSA.clearExogenousVars()

mlpKernel.setw(80.0)
mlpKernel.setoffset(20.0)
rbfKernel.setbandwidth(1.7)
OmniOSA.gridSize = 3
OmniOSA.globalOpt = "ML"
OmniOSA.modelType_("GP-NARMAX")
OmniOSA.setTarget(40, 6)
OmniOSA.setExogenousVars(List(24, 15, 16, 28), List(4), changeModelType = false)

val resPolyNM = OmniOSA.buildAndTestGP(
  tKernel+mlpKernel,
  whiteNoiseKernel,
  OmniOSA.meanFuncPersistence)

//Print the results out on the console
resPer.print()
resPolyAR.print()
resPolyARX.print()
resPolyNM.print()

OmniOSA.clearExogenousVars()

OmniOSA.globalOpt = "CSA"
OmniOSA.gridSize = 4
OmniOSA.maxIterations = 30
OmniOSA.useLogScale = true

mlpKernel.setw(10.0)
mlpKernel.setoffset(10.0)
OmniOSA.modelType_("GP-AR")

OmniOSA.experiment(
  tKernel+mlpKernel,
  whiteNoiseKernel,
  OmniOSA.meanFuncPersistence,
  3 to 12)


mlpKernel.setw(10.0)
mlpKernel.setoffset(10.0)
OmniOSA.modelType_("GP-ARX")

OmniOSA.experiment(
  tKernel+mlpKernel,
  whiteNoiseKernel,
  OmniOSA.meanFuncPersistence,
  3 to 12)
