import breeze.linalg._
import com.quantifind.charts.Highcharts._
import com.quantifind.charts.highcharts.AxisType
import io.github.mandar2812.PlasmaML.dynamics.diffusion.RadialDiffusion


val (nL,nT) = (10, 10)


val bins = List(1, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

val lShellLimits = (1.0, 5.0)
val timeLimits = (0.0, 5.0)

val omega = 2*math.Pi/(lShellLimits._2 - lShellLimits._1)
val theta = 0.06
val alpha = 0.005 + theta*math.pow(omega*lShellLimits._2, 2.0)

val referenceSolution = (l: Double, t: Double) => math.sin(omega*l)*(math.exp(-alpha*t) + 1.0)

val radialDiffusionSolver = (binsL: Int, binsT: Int) => new RadialDiffusion(lShellLimits, timeLimits, binsL, binsT)


//Perform verification of errors for constant nL

val lossesTime = bins.map(bT => {

  val rds = radialDiffusionSolver(nL, bT)

  println("Solving for delta T = "+rds.deltaT)

  val lShellVec = DenseVector.tabulate[Double](nL+1)(i =>
    if(i < nL) lShellLimits._1+(rds.deltaL*i)
    else lShellLimits._2).toArray.toSeq

  val initialPSDGT: DenseVector[Double] = DenseVector(
    lShellVec.map(l => referenceSolution(l - lShellLimits._1, 0.0)).toArray
  )

  val timeVec = DenseVector.tabulate[Double](bT+1)(i =>
    if(i < bT) timeLimits._1+(rds.deltaT*i)
    else timeLimits._2).toArray.toSeq


  val diffProVec = lShellVec.map(l => theta*l*l)
  val lossProVec = lShellVec.map(l => alpha - math.pow(l*omega, 2.0)*theta)

  println("\tInitialising diffusion profiles and boundary fluxes ...")
  val diffProfileGT = DenseMatrix.tabulate[Double](nL+1,bT+1)((i,_) => diffProVec(i))
  val lossProfileGT = DenseMatrix.tabulate[Double](nL+1,bT+1)((i,j) => lossProVec(i)/(1 + math.exp(alpha*timeVec(j))))
  val boundFluxGT = DenseMatrix.tabulate[Double](nL+1,bT+1)((i,j) =>
    if(i == nL || i == 0) referenceSolution(i * rds.deltaL, j * rds.deltaT)
    else 0.0)

  println("\tGenerating neural computation stack & computing solution")

  val solution = rds.solve(lossProfileGT, diffProfileGT, boundFluxGT)(initialPSDGT)

  val referenceSol = timeVec.map(t =>
    DenseVector(lShellVec.map(lS => referenceSolution(lS-lShellLimits._1, t)).toArray))

  println("\tCalculating RMSE with respect to reference solution\n")
  val error = math.sqrt(solution.zip(referenceSol).map(c => math.pow(norm(c._1 - c._2, 2.0), 2.0)).sum/(bT+1.0))

  (rds.deltaT, error)

})

spline(lossesTime)
title("Forward Solver Error")
xAxisType(AxisType.logarithmic)
xAxis("delta T")
yAxis("RMSE")


val lossesSpace = bins.map(bL => {

  val rds = radialDiffusionSolver(bL, nT)

  println("Solving for delta L = "+rds.deltaL)
  val lShellVec = DenseVector.tabulate[Double](bL+1)(i =>
    if(i < bL) lShellLimits._1+(rds.deltaL*i)
    else lShellLimits._2).toArray.toSeq

  val initialPSDGT: DenseVector[Double] = DenseVector(
    lShellVec.map(l => referenceSolution(l - lShellLimits._1, 0.0)).toArray
  )

  val timeVec = DenseVector.tabulate[Double](nT+1)(i =>
    if(i < nT) timeLimits._1+(rds.deltaT*i)
    else timeLimits._2).toArray.toSeq


  val diffProVec = lShellVec.map(l => theta*l*l)
  val lossProVec = lShellVec.map(l => alpha - math.pow(l*omega, 2.0)*theta)

  println("\tInitialising diffusion profiles and boundary fluxes ...")
  val diffProfileGT = DenseMatrix.tabulate[Double](bL+1,nT)((i,_) => diffProVec(i))
  val lossProfileGT = DenseMatrix.tabulate[Double](bL+1,nT)((i,j) => lossProVec(i)/(1 + math.exp(alpha*timeVec(j))))
  val boundFluxGT = DenseMatrix.tabulate[Double](bL+1,nT)((i,j) =>
    if(i == bL || i == 0) referenceSolution(i * rds.deltaL, j * rds.deltaT)
    else 0.0)

  println("\tGenerating neural computation stack & computing solution")

  val solution = rds.solve(lossProfileGT, diffProfileGT, boundFluxGT)(initialPSDGT)

  val referenceSol = timeVec.map(t =>
    DenseVector(lShellVec.map(lS => referenceSolution(lS-lShellLimits._1, t)).toArray))

  println("\tCalculating RMSE with respect to reference solution\n")
  val error = math.sqrt(
    solution.zip(referenceSol).map(c => math.pow(norm(c._1 - c._2, 2.0)/(bL+1.0), 2.0)).sum/(nT+1.0)
  )

  (rds.deltaL, error)

})


spline(lossesSpace)
title("Forward Solver Error")
xAxisType(AxisType.logarithmic)
xAxis("delta L")
yAxis("RMSE")
