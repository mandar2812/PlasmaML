import _root_.io.github.mandar2812.dynaml.tensorflow._
import _root_.io.github.mandar2812.dynaml.{DynaMLPipe => Pipe}
import _root_.io.github.mandar2812.dynaml.pipes._
import _root_.io.github.mandar2812.dynaml.repl.Router.main
import _root_.io.github.mandar2812.dynaml.probability.RandomVariable
import _root_.io.github.mandar2812.dynaml.analysis._
import breeze.linalg.{DenseMatrix, DenseVector, qr}
import breeze.stats.distributions.Gaussian
import com.quantifind.charts.Highcharts._
import org.platanios.tensorflow.api._


def generate_data(d: Int = 3, n: Int = 5, noise: Double = 0.5, noiserot: Double = 0.1) = {

  val random_gaussian_vec = DataPipe((i: Int) => RandomVariable(
    () => dtf.tensor_f32(i, 1)((0 until i).map(_ => scala.util.Random.nextGaussian()*noise):_*)
  ))

  val normalise = DataPipe((t: RandomVariable[Tensor]) => t.draw.l2Normalize(0))

  val normalised_gaussian_vec = random_gaussian_vec > normalise

  val x0 = normalised_gaussian_vec(d)

  val random_gaussian_mat = DataPipe(
    (n: Int) => DenseMatrix.rand(n, n, Gaussian(0d, noiserot))
  )

  val rand_rot_mat =
    random_gaussian_mat >
      DataPipe((m: DenseMatrix[Double]) => qr(m).q) >
      DataPipe((m: DenseMatrix[Double]) => dtf.tensor_f32(m.rows, m.rows)(m.toArray:_*).transpose())


  val rotation = rand_rot_mat(d)

  val get_rotation_operator = MetaPipe((rotation_mat: Tensor) => (x: Tensor) => rotation_mat.matmul(x))

  val rotation_op = get_rotation_operator(rotation)

  val translation_op = DataPipe2((tr: Tensor, x: Tensor) => tr.add(x))

  val translation_vecs = random_gaussian_vec(d).iid(n-1).draw

  val x_tail = translation_vecs.scanLeft(x0)((x, sc) => translation_op(sc, rotation_op(x)))

  val x: Seq[Tensor] = Stream(x0) ++ x_tail

  val velocity_pipe = DataPipe((v: Tensor) => v.square.sum().sqrt.scalar.asInstanceOf[Float])

  def id[T] = Pipe.identityPipe[T]

  val calculate_outputs =
    velocity_pipe >
      BifurcationPipe(
        DataPipe((v: Float) => 10/(v+ 1E-6)),
        id[Float]) >
      DataPipe(DataPipe((d: Double) => d.toInt), id[Float])


  val generate_data_pipe = StreamDataPipe(
    DataPipe(id[Int], BifurcationPipe(id[Tensor], calculate_outputs))  >
      DataPipe((pattern: (Int, (Tensor, (Int, Float)))) =>
        ((pattern._1, pattern._2._1.reshape(Shape(d))), (pattern._1+pattern._2._2._1, pattern._2._2._2)))
  )

  val times = (0 until n).toStream

  generate_data_pipe(times.zip(x))

}

@main
def main(d: Int = 3, n: Int = 100, noise: Double = 0.5, noiserot: Double = 0.1) = {

  val data = generate_data(d, n, noise, noiserot)

  val (causes, effects) = data.unzip

  val energies = data.map(_._2._2)

  spline(energies)
  title("Energy Time Series")

  val effect_times = data.map(_._2._1)

  histogram(effects.map(_._2), numBins = 100)
  title("Distribution of output signal  ")

  histogram(effect_times.zip(causes.map(_._1)).map(c => c._1 - c._2), numBins = 10)
  title("Distribution of time lags")

  spline(effect_times)
  hold()
  spline(data.map(_._1._1))
  title("Time Warping/Delay")
  xAxis("Time of Cause, t")
  yAxis("Time of Effect, "+0x03C6.toChar+"(t)")
  legend(Seq("t_ = "+0x03C6.toChar+"(t)", "t_ = t"))
  unhold()

  val outputs = effects.groupBy(_._1).mapValues(v => v.map(_._2).sum/v.length.toDouble).toSeq.sortBy(_._1)


  val linear_segments = outputs.sliding(2).toList.map(s =>
    DataPipe((t: Double) => {

      val (tmin, tmax) = (s.head._1.toDouble, s.last._1.toDouble)
      val (v0, v1) = (s.head._2, s.last._2)
      val slope: Double = (v1 - v0)/(tmax - tmin)

      if(t >= tmin && t < tmax) v0 + slope*(t - tmin)
      else 0d
    })
  )

  pprint.pprintln(outputs)

  val interpolated_output = causes.map(_._1).map(t => (t, linear_segments.map(_.run(t.toDouble)).sum))

  pprint.pprintln(interpolated_output)

  line(outputs)
  hold()
  line(energies)
  line(interpolated_output)
  legend(Seq("Output Data with Lag", "Output Data without Lag", "Self Interpolated Output"))
  unhold()

  (causes, effects, outputs, linear_segments)
}
