import breeze.linalg._
import com.quantifind.charts.Highcharts._
import com.quantifind.charts.highcharts.AxisType
import io.github.mandar2812.PlasmaML.dynamics.diffusion.RadialDiffusion


val (nL,nT) = (10, 10)


val bins = List(1, 10, 50, 100, 500, 1000, 5000, 10000)

val lShellLimits = (1.0, 10.0)
val timeLimits = (0.0, 5.0)

//Define parameters of reference solution
val a = 2*math.Pi/(lShellLimits._2 - lShellLimits._1)
val b = math.log(2d)/timeLimits._2

val referenceSolution = (l: Double, t: Double) => math.sin(a*(l - lShellLimits._1))*(math.exp(b*t) - 1.0)
val loss = (l: Double, t: Double) => 0.0

val initialPSD = (l: Double) => referenceSolution(l, 0.0)

//Define parameters of radial diffusion system
val alpha = 0.9
val beta = 2.0

val dll = (l: Double, _: Double) => alpha*math.pow(l, beta)

val q = (l: Double, t: Double) => {
  b*math.sin(a*(l - lShellLimits._1))*math.exp(b*t) -
    a*alpha*(beta-2d)*math.pow(l, beta-1d)*(math.exp(b*t) - 1.0)*math.cos(a*(l - lShellLimits._1)) +
    a*a*alpha*math.pow(l, beta)*(math.exp(b*t) - 1.0)*math.sin(a*(l - lShellLimits._1))
}

val radialDiffusionSolver = (binsL: Int, binsT: Int) => new RadialDiffusion(lShellLimits, timeLimits, binsL, binsT)


//Perform verification of errors for constant nL

val lossesTime = bins.map(bT => {

  val rds = radialDiffusionSolver(nL, bT)

  println("Solving for delta T = "+rds.deltaT)

  val (lShellVec, timeVec) = RadialDiffusion.buildStencil(lShellLimits, nL, timeLimits, bT)

  println("\tGenerating neural computation stack")

  val solution = rds.solve(q, dll, loss)(initialPSD)

  val referenceSol = timeVec.map(t => DenseVector(lShellVec.map(lS => referenceSolution(lS, t)).toArray))

  println("\tCalculating RMSE with respect to reference solution\n")
  val error = math.sqrt(solution.zip(referenceSol).map(c => math.pow(norm(c._1 - c._2, 2.0), 2.0)).sum/(bT+1.0))

  (rds.deltaT, error)

})


val lossesSpace = bins.map(bL => {

  val rds = radialDiffusionSolver(bL, nT)

  println("Solving for delta L = "+rds.deltaL)

  val (lShellVec, timeVec) = RadialDiffusion.buildStencil(lShellLimits, bL, timeLimits, nT)

  println("\tGenerating neural computation stack")

  val solution = rds.solve(q, dll, loss)(initialPSD)

  val referenceSol = timeVec.map(t => DenseVector(lShellVec.map(lS => referenceSolution(lS, t)).toArray))

  println("\tCalculating RMSE with respect to reference solution\n")
  val error = math.sqrt(solution.zip(referenceSol).map(c => math.pow(norm(c._1 - c._2, 2.0), 2.0)/(bL+1.0)).sum/(nT+1d))

  (rds.deltaL, error)

})


spline(lossesTime)
title("Forward Solver Error")
xAxisType(AxisType.logarithmic)
xAxis("delta T")
yAxis("RMSE")


spline(lossesSpace)
title("Forward Solver Error")
xAxisType(AxisType.logarithmic)
xAxis("delta L")
yAxis("RMSE")
