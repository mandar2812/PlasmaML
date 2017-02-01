//DynaML imports
import breeze.linalg.DenseVector
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

val mlpKernel = new MLPKernel(0.5, 0.5)
//mlpKernel.block_all_hyper_parameters

val whiteNoiseKernel = new DiracKernel(1.0)
whiteNoiseKernel.block_all_hyper_parameters

OmniOSA.gridSize = 2
OmniOSA.gridStep = 0.2
OmniOSA.globalOpt = "GS"
OmniOSA.maxIterations = 500

//Set model validation data set ranges
/*OmniOSA.validationDataSections ++= Stream(
  ("2013/03/17/07", "2013/03/18/10"),
  ("2011/10/24/20", "2011/10/25/14"))*/

OmniOSA.clearExogenousVars()
//Get test results for Linear GP-AR model
//with a mean function given by the persistence
//model
val resPolyAR = OmniOSA.buildAndTestGP(
  mlpKernel+tKernel,
  whiteNoiseKernel,
  OmniOSA.meanFuncPersistence)

//Set solar wind speed and IMF Bz as exogenous variables
OmniOSA.setExogenousVars(List(24, 16), List(2,2))
//OmniOSA.globalOpt = "ML-II"

//Reset kernel and noise to initial states
//tKernel.setHyperParameters(Map("d" -> 1.0))
polynomialKernel.setoffset(0.75)
whiteNoiseKernel.setNoiseLevel(1.5)
mlpKernel.setw(0.709)
mlpKernel.setoffset(0.709)
//tKernel.setHyperParameters(Map("d" -> 0.75))
OmniOSA.gridSize = 2
//Get test results for a GP-ARX model
//with a mean function given by the persistence
//model
val resPolyARX = OmniOSA.buildAndTestGP(
  tKernel+mlpKernel,
  whiteNoiseKernel,
  OmniOSA.meanFuncPersistence)

OmniOSA.clearExogenousVars()


//Compare with base line of the Persistence model
val resPer = DstPersistenceMOExperiment(0)

//Print the results out on the console

//tKernel.setHyperParameters(Map("d" -> 0.75))
mlpKernel.setw(0.909)
mlpKernel.setoffset(0.909)
//whiteNoiseKernel.setNoiseLevel(2.0)
OmniOSA.gridSize = 3
OmniOSA.globalOpt = "ML-II"

OmniOSA.modelType_("GP-NARMAX")

val resPolyNM = OmniOSA.buildAndTestGP(
  tKernel+mlpKernel+rbfKernel,
  whiteNoiseKernel,
  OmniOSA.meanFuncPersistence)
resPolyNM.print

resPer.print()
resPolyAR.print()
resPolyARX.print()

OmniOSA.clearExogenousVars()
