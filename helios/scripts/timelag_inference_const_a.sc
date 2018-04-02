import breeze.linalg.{DenseMatrix, qr}
import breeze.stats.distributions.Gaussian
import com.quantifind.charts.Highcharts._
import ammonite.ops.home
import org.joda.time.DateTime
import _root_.io.github.mandar2812.dynaml.tensorflow._
import _root_.io.github.mandar2812.dynaml.tensorflow.utils._
import _root_.io.github.mandar2812.dynaml.tensorflow.layers._
import _root_.io.github.mandar2812.dynaml.{DynaMLPipe => Pipe}
import _root_.io.github.mandar2812.dynaml.pipes._
import _root_.io.github.mandar2812.dynaml.repl.Router.main
import _root_.io.github.mandar2812.dynaml.probability.RandomVariable
import _root_.io.github.mandar2812.dynaml.evaluation._
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.ops.training.optimizers.Optimizer
import _root_.io.github.mandar2812.PlasmaML.helios.data.HeliosDataSet
import _root_.io.github.mandar2812.PlasmaML.helios.core._
import _root_.io.github.mandar2812.PlasmaML.utils._

def id[T] = Pipe.identityPipe[T]
//Output computation
val alpha = 100f
val compute_output = DataPipe(
  (v: Tensor) =>
    (
      v.square.sum().scalar.asInstanceOf[Float]*alpha,
      alpha*0.1f
    )
)

//Time Lag Computation
// 1/2*a*t^2 + u*t - s = 0
// t = (-u + sqrt(u*u + 2as))/a
val distance = alpha*20
val compute_time_lag = DataPipe((va: (Float, Float)) => {
  val (v, a) = va
  val dt = (-v + math.sqrt(v*v + 2*a*distance).toFloat)/(2*a)
  val vf = math.sqrt(v*v + 2f*a*distance).toFloat
  (dt, vf)
})

def autocorrelation(n: Int)(data: Stream[Double]): Stream[Double] = {
  val mean = data.sum/data.length
  val variance = data.map(_ - mean).map(math.pow(_, 2d)).sum/(data.length - 1d)


  (0 to n).map(lag => {
    val sliding_ts = data.sliding(lag+1).toSeq
    val len = sliding_ts.length - 1d

    sliding_ts.map(xs => (xs.head - mean) * (xs.last - mean)).sum/(len*variance)
  }).toStream
}


//Subroutine to generate synthetic
//input-lagged output time series.
def generate_data(
  d: Int = 3, n: Int = 5,
  sliding_window: Int,
  noise: Double = 0.5,
  noiserot: Double = 0.1) = {

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

  val translation_vecs = random_gaussian_vec(d).iid(n+500-1).draw

  val x_tail = translation_vecs.scanLeft(x0)((x, sc) => translation_op(sc, rotation_op(x)))

  val x: Seq[Tensor] = (Stream(x0) ++ x_tail).takeRight(n)

  val calculate_outputs =
    compute_output >
      compute_time_lag >
      DataPipe(
        DataPipe((d: Float) => d.toInt),
        DataPipe((v: Float) => v + (scala.util.Random.nextGaussian()*noise).toFloat)
      )


  val generate_data_pipe = StreamDataPipe(
    DataPipe(id[Int], BifurcationPipe(id[Tensor], calculate_outputs))  >
      DataPipe((pattern: (Int, (Tensor, (Int, Float)))) =>
        ((pattern._1, pattern._2._1.reshape(Shape(d))), (pattern._1+pattern._2._2._1, pattern._2._2._2)))
  )

  val times = (0 until n).toStream

  val data = generate_data_pipe(times.zip(x))

  val (causes, effects) = data.unzip

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

  val interpolated_output_signal = causes.map(_._1).map(t => (t, linear_segments.map(_.run(t.toDouble)).sum))

  val effectsMap = interpolated_output_signal
    .sliding(sliding_window)
    .map(window => (window.head._1, window.map(_._2)))
    .toMap

  //Join the features with sliding time windows of the output
  val joined_data = data.map(c =>
    if(effectsMap.contains(c._1._1)) (c._1._1, (c._1._2, Some(effectsMap(c._1._1)), c._2._1 - c._1._1))
    else (c._1._1, (c._1._2, None, c._2._1 - c._1._1)))
    .filter(_._2._2.isDefined)
    .map(p => (p._1, (p._2._1, p._2._2.get, p._2._3)))

  (data, joined_data)
}

@main
def main(
  d: Int               = 3,
  n: Int               = 100,
  sliding_window: Int  = 15,
  noise: Double        = 0.5,
  noiserot: Double     = 0.1,
  iterations: Int      = 150000,
  optimizer: Optimizer = tf.train.AdaDelta(0.01),
  sum_dir: String      = "",
  reg: Double          = 0.01,
  p: Double            = 1.0,
  time_scale: Double   = 1.0,
  corr_sc: Double      = 2.5,
  c_cutoff: Double     = 0.0,
  prior_wt: Double     = 1d,
  mo_flag: Boolean = false) = {

  val train_fraction = 0.7

  val (data, collated_data) = generate_data(d, n, sliding_window, noise, noiserot)

  val (causes, effects) = data.unzip

  val energies = data.map(_._2._2)

  spline(energies)
  title("Output Time Series")

  val effect_times = data.map(_._2._1)

  histogram(effect_times.zip(causes.map(_._1)).map(c => c._1 - c._2))
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

  line(outputs)
  hold()
  line(energies)
  legend(Seq("Output Data with Lag", "Output Data without Lag"))
  unhold()

  val num_training = (collated_data.length*train_fraction).toInt
  val num_test = collated_data.length - num_training

  //Transform the generated data into a tensorflow compatible object
  val load_data_into_tensors = DataPipe((data: Stream[(Int, (Tensor, Stream[Double], Int))]) => {
    val features = dtf.stack(data.map(_._2._1), axis = 0)

    val labels = dtf.tensor_f64(
      data.length, sliding_window)(
      data.flatMap(_._2._2):_*)

    val labels_timelags = dtf.tensor_f64(data.length)(data.map(d => d._2._3.toDouble):_*)

    val (_, test_time_lags): (Tensor, Tensor) = (
      labels_timelags(0 :: num_training),
      labels_timelags(num_training :: ))


    //Create a helios data set.
    val tf_dataset = HeliosDataSet(
      features(0 :: num_training, ---), labels(0 :: num_training), num_training,
      features(num_training ::, ---), labels(num_training ::), num_test)

    (tf_dataset, test_time_lags)
  })

  //Scale training features/labels, apply scaling to test features
  val scale_data = DataPipe(
    DataPipe((dataset: HeliosDataSet) => {

      val (norm_tr_data, scalers) = dtfpipe.gaussian_standardization(dataset.trainData, dataset.trainLabels)

      (
        dataset.copy(
          trainData = norm_tr_data._1, trainLabels = norm_tr_data._2,
          testData = scalers._1(dataset.testData)),
        scalers
      )
    }),
    id[Tensor])

  val model_train_eval = DataPipe(
    (dataTuple: ((HeliosDataSet, (GaussianScalerTF, GaussianScalerTF)), Tensor)) => {

      val ((tf_dataset, scalers), test_time_lags) = dataTuple

      val miniBatch = 128

      val training_data = tf.data.TensorSlicesDataset(tf_dataset.trainData)
        .zip(tf.data.TensorSlicesDataset(tf_dataset.trainLabels)).repeat()
        .shuffle(100)
        .batch(miniBatch)
        .prefetch(10)

      val dt = DateTime.now()

      val summary_dir_index  = "timelag_const_a_"+dt.toString("YYYY-MM-dd-HH-mm")

      val tf_summary_dir     = home/'tmp/summary_dir_index


      val input              = tf.learn.Input(FLOAT64, Shape(-1, tf_dataset.trainData.shape(1)))

      val num_outputs        = sliding_window

      val trainInput         = tf.learn.Input(FLOAT64, Shape(-1, num_outputs))

      val trainingInputLayer = tf.learn.Cast("TrainInput", FLOAT64)

      val num_pred_dims = if(mo_flag) sliding_window + 1 else 2

      //Prediction architecture
      val architecture = {
        dtflearn.feedforward(20)(1) >>
          tf.learn.Sigmoid("Act_1") >>
          dtflearn.feedforward(15)(2) >>
          tf.learn.Sigmoid("Act_2") >>
          dtflearn.feedforward(num_pred_dims)(3)
      }

      val layer_parameter_names = Seq(
        "Linear_1/Weights",
        "Linear_2/Weights",
        "Linear_3/Weights")

      val layer_shapes = Seq(
        Shape(d, 20),
        Shape(20, 15),
        Shape(15, num_pred_dims)
      )

      val layer_datatypes = Seq("FLOAT64", "FLOAT64", "FLOAT64")

      val lossFunc = if (mo_flag) {
        MOGrangerLoss("Loss/MOGranger", num_outputs)
      } else {
        RBFWeightedSWLoss(
          "Loss/RBFWeightedL1", num_outputs,
          kernel_time_scale = time_scale,
          kernel_norm_exponent = p,
          corr_cutoff = c_cutoff,
          prior_scaling = corr_sc,
          prior_weight = prior_wt,
          batch = miniBatch)
      }

      val loss     = lossFunc >>
        L2Regularization(layer_parameter_names, layer_datatypes, layer_shapes, reg) >>
        tf.learn.ScalarSummary("Loss", "ModelLoss")

      val summariesDir =
        if (sum_dir == "") java.nio.file.Paths.get(tf_summary_dir.toString())
        else java.nio.file.Paths.get(sum_dir)

      val (model, estimator) = tf.createWith(graph = Graph()) {
        val model = tf.learn.Model(
          input, architecture, trainInput, trainingInputLayer,
          loss, optimizer)

        println("Training the regression model.")

        val estimator = tf.learn.FileBasedEstimator(
          model,
          tf.learn.Configuration(Some(summariesDir)),
          tf.learn.StopCriteria(maxSteps = Some(iterations)),
          Set(
            tf.learn.StepRateLogger(
              log = false, summaryDir = summariesDir,
              trigger = tf.learn.StepHookTrigger(5000)),
            tf.learn.SummarySaver(summariesDir, tf.learn.StepHookTrigger(5000)),
            tf.learn.CheckpointSaver(summariesDir, tf.learn.StepHookTrigger(5000))),
          tensorBoardConfig = tf.learn.TensorBoardConfig(summariesDir, reloadInterval = 5000))

        estimator.train(() => training_data, tf.learn.StopCriteria(maxSteps = Some(iterations)))

        (model, estimator)
      }

      val predictions = estimator.infer(() => tf_dataset.testData)

      val alpha = Tensor(0.5)
      val nu    = Tensor(1.0)
      val q     = Tensor(1.0)

      val unscaled_pred_time_lags_test = predictions(::, -1)

      val pred_time_lags_test = unscaled_pred_time_lags_test
        .multiply(alpha.add(1E-6).square.multiply(-1.0))
        .exp
        .multiply(q.square)
        .add(1.0)
        .pow(nu.square.pow(-1.0).multiply(-1.0))
        .multiply(num_outputs - 1.0)

      val reg_time_lag = new RegressionMetricsTF(pred_time_lags_test, test_time_lags)

      val pred_targets: Tensor = if (mo_flag) {
        val all_preds = scalers._2.i(predictions(::, 0 :: -1))

        val repeated_times      = tf.stack(Seq.fill(num_outputs)(pred_time_lags_test.floor), axis = -1)
        val index_times = Tensor(
          (0 until num_outputs).map(_.toDouble)
        ).reshape(
          Shape(num_outputs)
        )

        val conv_kernel = repeated_times.subtract(index_times).square.exp.floor.evaluate()

        all_preds.multiply(conv_kernel).sum(axes = 1).divide(conv_kernel.sum(axes = 1)).evaluate()
      } else {
        scalers._2(0).i(predictions(::, 0))
      }

      val actual_targets = (0 until num_test).map(n => {
        val time_lag = pred_time_lags_test(n).scalar.asInstanceOf[Double].toInt
        tf_dataset.testLabels(n, time_lag).scalar.asInstanceOf[Double]
      })

      val reg_metrics = new RegressionMetricsTF(pred_targets, actual_targets)

      /*val metrics = new HeliosOmniTSMetrics(
        dtf.stack(Seq(pred_targets, unscaled_pred_time_lags_test), axis = 1), tf_dataset.testLabels,
        tf_dataset.testLabels.shape(1),
        dtf.tensor_f32(tf_dataset.nTest)(Seq.fill(tf_dataset.nTest)(1d):_*)
      )*/

      ((tf_dataset, scalers), (model, estimator), reg_metrics, reg_time_lag,/* metrics,*/ tf_summary_dir)
    })

  //The processing pipeline
  val process_data = load_data_into_tensors > scale_data > model_train_eval

  val (
    (tf_dataset, scalers),
    (model, estimator),
    reg_metrics, reg_time_lag,
    /*hs_metrics,*/
    tf_summary_dir) = process_data(collated_data)

  val err_time_lag_test = reg_time_lag.preds.subtract(reg_time_lag.targets)

  val mae_lag = err_time_lag_test
    .abs.mean()
    .scalar
    .asInstanceOf[Double]

  val pred_time_lags_test = reg_time_lag.preds

  val pred_targets_test = reg_metrics.preds

  print("Mean Absolute Error in time lag = ")
  pprint.pprintln(mae_lag)

  try {
    histogram(pred_time_lags_test.entriesIterator.map(_.asInstanceOf[Double]).toSeq)
    title("Predicted Time Lags")
  } catch {
    case _: java.util.NoSuchElementException => println("Can't plot histogram due to `No Such Element` exception")
    case _ => println("Can't plot histogram due to exception")
  }

  try {

    histogram(err_time_lag_test.entriesIterator.toSeq.map(_.asInstanceOf[Double]))
    title("Histogram of Time Lag prediction errors")
  } catch {
    case e: java.util.NoSuchElementException => println("Can't plot histogram due to `No Such Element` exception")
    case _ => println("Can't plot histogram due to exception")
  }

  val test_signal_predicted = collated_data.slice(num_training, n).zipWithIndex.map(c => {
    val time_index = c._1._1
    val pred_lag = pred_time_lags_test(c._2).scalar.asInstanceOf[Double]
    val pred = pred_targets_test(c._2).scalar.asInstanceOf[Double]


    (time_index + pred_lag, pred)
  }).sortBy(_._1)


  line(collated_data.slice(num_training, n).map(c => (c._1+c._2._3, c._2._2(c._2._3))))
  hold()
  line(test_signal_predicted)
  legend(Seq("Actual Output Signal", "Predicted Output Signal"))
  title("Test Set Predictions")
  unhold()


  //Perform same visualisation for training set
  val training_preds = estimator.infer(() => tf_dataset.trainData)

  val alpha = Tensor(0.5)
  val nu    = Tensor(1.0)
  val q     = Tensor(1.0)

  val pred_time_lags_train = training_preds(::, 1)
    .multiply(alpha.add(1E-6).square.multiply(-1.0))
    .exp
    .multiply(q.square)
    .add(1.0)
    .pow(nu.square.pow(-1.0).multiply(-1.0))
    .multiply(sliding_window - 1.0)

  val pred_targets_train = scalers._2(0).i(training_preds(::, 0))

  val train_signal_predicted = collated_data.slice(0, num_training).zipWithIndex.map(c => {
    val time_index = c._1._1
    val pred_lag = pred_time_lags_train(c._2).scalar.asInstanceOf[Double]
    val pred = pred_targets_train(c._2).scalar.asInstanceOf[Double]

    (time_index + pred_lag, pred)
  }).sortBy(_._1)

  line(collated_data.slice(0, num_training).map(c => (c._1+c._2._3, c._2._2(c._2._3))))
  hold()
  line(train_signal_predicted)
  legend(Seq("Actual Output Signal", "Predicted Output Signal"))
  title("Training Set Predictions")
  unhold()

  line(autocorrelation(20)(data.map(_._2._2.toDouble)))
  title("Auto-covariance of time series")

  (
    (data, collated_data, tf_dataset),
    (model, estimator, tf_summary_dir),
    (reg_metrics, reg_time_lag/*, hs_metrics*/),
    scalers
  )

}
