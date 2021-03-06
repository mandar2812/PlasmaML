package io.github.mandar2812.PlasmaML.helios.core.timelag

import ammonite.ops._
import breeze.linalg.{DenseMatrix, qr}
import breeze.stats.distributions.{Bernoulli, Gaussian, LogNormal, Uniform}
import _root_.io.github.mandar2812.dynaml.{DynaMLPipe => Pipe}
import io.github.mandar2812.PlasmaML.helios
import io.github.mandar2812.PlasmaML.helios.core.{
  CausalDynamicTimeLagSO,
  MOGrangerLoss,
  RBFWeightedSWLoss
}
import io.github.mandar2812.PlasmaML.helios.data.HeliosDataSet
import io.github.mandar2812.PlasmaML.helios.fte
import io.github.mandar2812.dynaml.graphics.charts.Highcharts._
import io.github.mandar2812.dynaml.pipes._
import io.github.mandar2812.dynaml.probability.RandomVariable
import io.github.mandar2812.dynaml.tensorflow.data.TFDataSet
import io.github.mandar2812.dynaml.tensorflow._
import io.github.mandar2812.dynaml.tensorflow.data._
import io.github.mandar2812.dynaml.tensorflow.utils.GaussianScalerTF
import org.platanios.tensorflow.api.learn.{Mode, StopCriteria}
import org.platanios.tensorflow.api.learn.layers.{Activation, Layer, Loss}
import org.platanios.tensorflow.api._
import _root_.io.github.mandar2812.dynaml.tensorflow.evaluation.RegressionMetricsTF
import org.platanios.tensorflow.api.learn.hooks.Hook

package object utils {

  //Define some types for convenience.

  type PATTERN[T]     = ((Int, Tensor[T]), (Float, Float))
  type SLIDINGPATT[T] = (Int, (Tensor[T], Stream[Double], Float))
  type PATTERNBL[T]   = (Tensor[T], Tensor[T])

  type DATA[T]        = Stream[PATTERN[T]]
  type SLIDINGDATA[T] = Stream[SLIDINGPATT[T]]
  type TLDATA[T]      = (DATA[T], SLIDINGDATA[T])
  type DATABL[T]      = Stream[PATTERNBL[T]]

  type PROCDATA[T] = (HeliosDataSet[T, T], (Tensor[T], Tensor[T]))

  type PROCDATA2[T] =
    (TFDataSet[(Tensor[T], Tensor[T])], (Tensor[T], Tensor[T]))

  type NNPROP = (Seq[Int], Seq[Shape], Seq[String], Seq[String])

  //Alias for the identity pipe/mapping
  def id[T]: DataPipe[T, T] = Pipe.identityPipe[T]

  //A Polynomial layer builder
  def layer_poly[T: TF: IsFloatOrDouble](power: Int)(n: String): Activation[T] =
    new Activation(n) {
      override val layerType = "Poly"
      override def forwardWithoutContext(
        input: Output[T]
      )(
        implicit mode: Mode
      ): Output[T] = {
        input.pow(Tensor(power).castTo[T])
      }
    }

  def sin[T: TF: IsFloatOrDouble](n: String): Activation[T] =
    new Activation(n) {
      override val layerType = "Sin"
      override def forwardWithoutContext(
        input: Output[T]
      )(
        implicit mode: Mode
      ): Output[T] = {
        input.sin
      }
    }

  def getSinAct[T: TF: IsFloatOrDouble](s: Int, i: Int): Activation[T] =
    if (i - s == 0) sin[T](s"Act_$i")
    else tf.learn.Sigmoid(s"Act_$i")

  def getPolyAct[T: TF: IsFloatOrDouble](
    degree: Int,
    s: Int,
    i: Int
  ): Activation[T] =
    if (i - s == 0) layer_poly[T](degree)(s"Act_$i")
    else tf.learn.Sigmoid(s"Act_$i")

  def getReLUAct[T: TF: IsFloatOrDouble](
    s: Int,
    i: Int,
    leakyness: Float = 0.01f
  ): Activation[T] =
    if ((i - s) % 2 == 0) tf.learn.ReLU(s"Act_$i", leakyness)
    else tf.learn.Sigmoid(s"Act_$i")

  def getReLUAct2[T: TF: IsFloatOrDouble](
    s: Int,
    i: Int,
    leakyness: Float = 0.01f
  ): Activation[T] =
    if ((i - s) == 0) tf.learn.ReLU(s"Act_$i", leakyness)
    else tf.learn.Sigmoid(s"Act_$i")

  def getReLUAct3[T: TF: IsFloatOrDouble](
    start: Int,
    repeat: Int,
    i: Int,
    leakyness: Float = 0.01f
  ): Activation[T] =
    if ((i - start) < repeat) tf.learn.ReLU(s"Act_$i", leakyness)
    else tf.learn.Sigmoid(s"Act_$i")

  def getELUAct[T: TF: IsFloatOrDouble](
    start: Int,
    repeat: Int,
    i: Int
  ): Activation[T] =
    if ((i - start) < repeat) tf.learn.ELU(s"Act_$i")
    else tf.learn.Sigmoid(s"Act_$i")

  def getSELUAct[T: TF: IsFloatOrDouble](
    start: Int,
    repeat: Int,
    i: Int
  ): Activation[T] =
    if ((i - start) < repeat) tf.learn.SELU(s"Act_$i")
    else tf.learn.Sigmoid(s"Act_$i")

  /**
    * Calculate sliding autocorrelation of a time series.
    *
    * @param n The maximum time lag until which the autocorrelation
    *          spectrum should be computed.
    * @param data The (univariate) time series.
    * */
  def autocorrelation(n: Int)(data: Stream[Double]): Stream[Double] = {
    val mean = data.sum / data.length
    val variance = data
      .map(_ - mean)
      .map(math.pow(_, 2d))
      .sum / (data.length - 1d)

    (0 to n)
      .map(lag => {
        val sliding_ts = data.sliding(lag + 1).toSeq
        val len        = sliding_ts.length - 1d

        sliding_ts
          .map(xs => (xs.head - mean) * (xs.last - mean))
          .sum / (len * variance)
      })
      .toStream
  }

  /**
    * Subroutine to generate synthetic input-lagged output time series.
    *
    * x(n+1) = (1 - &alpha;). R(n) &times; x(n) + &epsilon;
    *
    * y(t + &Delta;t(x(t))) = f[x(t)]
    *
    * &Delta;t(x(t)) = g[x(t)]
    *
    * @param compute_output_and_lag A data pipe which takes the input x(t) a tensor and
    *                               computes y(t + &Delta;t(x(t))) the output and &Delta;(x(t)), the
    *                               causal time lag.
    *
    * @param d The dimensions in the input time series x(t)
    * @param n The length of the time series x(t)
    * @param noise The variance of &epsilon;
    * @param noiserot The variance of elements of R<sub>i,j</sub>,
    *                 a randomly generated matrix used to compute
    *                 an orthogonal transformation (rotation) of
    *                 x(t).
    * @param alpha A relaxation parameter which controls the
    *              auto-correlation time scale of x(t)
    *
    * @param sliding_window The size of the sliding time window [y(t), ..., y(t+h)]
    *                       to construct. This is used as training label for the model.
    * */
  def generate_data[T: TF: IsFloatOrDouble](
    compute_output_and_lag: DataPipe[Tensor[T], (Float, Float)],
    sliding_window: Int,
    d: Int = 3,
    n: Int = 5,
    noiserot: Double = 0.1,
    alpha: Double = 0.0,
    noise: Double = 0.5
  ): TLDATA[T] = {

    val random_gaussian_vec: DataPipe[Int, RandomVariable[Tensor[T]]] =
      DataPipe(
        (i: Int) =>
          RandomVariable(
            () =>
              dtf
                .tensor_f64(i, 1)(
                  (0 until i)
                    .map(_ => scala.util.Random.nextGaussian() * noise): _*
                )
                .castTo[T]
          )
      )

    val normalise: DataPipe[RandomVariable[Tensor[T]], Tensor[T]] =
      DataPipe((t: RandomVariable[Tensor[T]]) => t.draw.l2Normalize(0))

    val normalised_gaussian_vec = random_gaussian_vec > normalise

    val random_gaussian_mat: DataPipe[Int, DenseMatrix[Double]] = DataPipe(
      (n: Int) => DenseMatrix.rand(n, n, Gaussian(0d, noiserot))
    )

    //A data pipe which returns a random rotation matrix, given n, the number of dimensions
    val rand_rot_mat: DataPipe[Int, Tensor[T]] =
      random_gaussian_mat >
        DataPipe((m: DenseMatrix[Double]) => qr(m).q) >
        DataPipe(
          (m: DenseMatrix[Double]) =>
            dtf
              .tensor_f64(m.rows, m.rows)(m.toArray: _*)
              .castTo[T]
              .transpose[Int]()
        )

    val get_rotation_operator = MetaPipe(
      (rotation_mat: Tensor[T]) => (x: Tensor[T]) => rotation_mat.matmul(x)
    )

    //Get the rotation operator for the randomly generated rotation.
    val rotation_op: DataPipe[Tensor[T], Tensor[T]] = get_rotation_operator(
      rand_rot_mat(d)
    )

    val one = Tensor(1.0d).castTo[T]

    val alpha_t = Tensor(alpha).castTo[T]

    val translation_op = DataPipe2(
      (tr: Tensor[T], x: Tensor[T]) =>
        tr.add(x.multiply(Tensor(1.0f - alpha.toFloat).castTo[T]))
    )

    //Create a time series of random increments d(t)
    val translation_vecs = random_gaussian_vec(d).iid(n + 500 - 1).draw

    //Impulse vecs
    val impulse: RandomVariable[Tensor[T]] =
      RandomVariable(new Bernoulli(0.999)).iid(d) >
        StreamDataPipe((f: Boolean) => if (f) 1d else Uniform(0.9d, 2d).draw()) >
        DataPipe((s: Stream[Double]) => dtf.tensor_f64(d, 1)(s: _*).castTo[T])

    val impulse_vecs = impulse.iid(n + 500 - 1).draw

    //Generate the time series x(t) = R.x(t-1) + d(t)
    val x0 = normalised_gaussian_vec(d)

    val x_tail =
      translation_vecs
        .zip(impulse_vecs)
        .scanLeft(x0)(
          (x, sc) => sc._2 * (rotation_op(x) * (one - alpha_t)) + sc._1
        )

    val x: Seq[Tensor[T]] = (Stream(x0) ++ x_tail).takeRight(n)

    //Takes input x(t) and returns {y(t + delta(x(t))), delta(x(t))}
    val calculate_outputs: DataPipe[Tensor[T], (Float, Float)] =
      compute_output_and_lag

    //Finally create the data pipe which takes a stream of x(t) and
    //generates the input output pairs.
    val generate_data_pipe = StreamDataPipe(
      DataPipe(id[Int], BifurcationPipe(id[Tensor[T]], calculate_outputs)) >
        DataPipe(
          (pattern: (Int, (Tensor[T], (Float, Float)))) =>
            (
              (pattern._1, tfi.reshape[T, Int](pattern._2._1, Shape(d))),
              (pattern._1 + pattern._2._2._1, pattern._2._2._2)
            )
        )
    )

    val times = (0 until n).toStream

    val data = generate_data_pipe(times.zip(x))

    val (causes, effects) = data.unzip

    /*
     * Create a variable `outputs` which is
     * a collection of time lags and targets
     * (∂t, y(t)).
     *
     * This is done by:
     *
     * 1. grouping the collection by observation time
     * 2. averaging any targets which are incident at the same time
     * 3. sorting the collection to yield a temporal sequence y(t)
     *
     * */
    val outputs =
      effects
        .groupBy(_._1.toInt)
        .mapValues(v => v.map(_._2).sum / v.length.toDouble)
        .toSeq
        .sortBy(_._1)

    val starting_lag = outputs.map(_._1).min

    //Interpolate the gaps in the generated data.
    val linear_segments = outputs
      .sliding(2)
      .toList
      .map(
        s =>
          DataPipe((t: Double) => {

            val (tmin, tmax)  = (s.head._1.toDouble, s.last._1.toDouble)
            val (v0, v1)      = (s.head._2, s.last._2)
            val slope: Double = (v1 - v0) / (tmax - tmin)

            if (t >= tmin && t < tmax) v0 + slope * (t - tmin)
            else 0d
          })
      )

    val interpolated_output_signal =
      causes
        .drop(starting_lag)
        .map(_._1)
        .map(t => (t, linear_segments.map(_.run(t.toDouble)).sum))

    val effectsMap = interpolated_output_signal
      .sliding(sliding_window)
      .map(window => (window.head._1, window.map(_._2)))
      .toMap

    //Join the features with sliding time windows of the output
    val joined_data: SLIDINGDATA[T] = data
      .map(
        (c: ((Int, Tensor[T]), (Float, Float))) =>
          if (effectsMap.contains(c._1._1))
            (c._1._1, (c._1._2, Some(effectsMap(c._1._1)), c._2._1 - c._1._1))
          else (c._1._1, (c._1._2, None, c._2._1 - c._1._1))
      )
      .filter(_._2._2.isDefined)
      .map(p => (p._1, (p._2._1, p._2._2.get, p._2._3)))

    (data, joined_data)
  }

  /**
    * Introduce confounding factors in a generated data set.
    * Implemented by hiding a fraction of the input features.
    *
    * @param dataset A generated time series data set, of type [[TLDATA]]
    *
    * @param confounding_factor A fraction, which represents the degree of confounding.
    *                           a value of 0 represents no confounding factors
    *                           in the final data set.
    * */
  def confound_data[T: TF: IsFloatOrDouble](
    dataset: TLDATA[T],
    confounding_factor: Double
  ): TLDATA[T] = {
    require(
      confounding_factor >= 0d && confounding_factor <= 1d,
      "The confounding factor can only be between 0 and 1"
    )

    val d = dataset._1.head._1._2.shape(0).scalar

    val num_sel_dims = math.ceil(d * (1d - confounding_factor)).toInt

    val (data, joined_data) = dataset

    val data_con =
      data.map(patt => ((patt._1._1, patt._1._2(0 :: num_sel_dims)), patt._2))

    val joined_data_con = joined_data.map(
      patt => (patt._1, (patt._2._1(0 :: num_sel_dims), patt._2._2, patt._2._3))
    )

    (data_con, joined_data_con)
  }

  def confound_data[T: TF: IsFloatOrDouble](
    dataset: DATABL[T],
    confounding_factor: Double
  ): DATABL[T] = {
    require(
      confounding_factor >= 0d && confounding_factor <= 1d,
      "The confounding factor can only be between 0 and 1"
    )

    val d = dataset.head._1.shape(0).scalar

    val num_sel_dims = math.ceil(d * (1d - confounding_factor)).toInt

    val data_con =
      dataset.map(patt => (patt._1(0 :: num_sel_dims), patt._2))

    data_con
  }

  /**
    * Plot the synthetic data set produced by [[generate_data()]].
    * */
  def plot_data[T: TF: IsFloatOrDouble](dataset: TLDATA[T]): Unit = {

    val (data, joined_data) = dataset

    val sliding_window = joined_data.head._2._2.length

    val (causes, effects) = data.unzip

    val energies = data.map(_._2._2)

    val effect_times = data.map(_._2._1)

    val outputs = effects
      .groupBy(_._1.toInt)
      .mapValues(v => v.map(_._2).sum / v.length.toDouble)
      .toSeq
      .sortBy(_._1)

    try {

      histogram(effect_times.zip(causes.map(_._1)).map(c => c._1 - c._2))
      title("Distribution of time lags")

    } catch {
      case _: java.util.NoSuchElementException =>
        println("Can't plot histogram due to `No Such Element` exception")
      case _: Throwable => println("Can't plot histogram due to exception")
    }

    line(outputs)
    hold()
    line(energies)
    legend(Seq("Output Data with Lag", "Output Data without Lag"))
    unhold()

    spline(autocorrelation(2 * sliding_window)(data.map(_._2._2.toDouble)))
    title("Auto-covariance of time series")

  }

  /**
    * Creates a data pipeline which takes a [[SLIDINGDATA]] object
    * and returns a [[PROCDATA]] instance.
    *
    * The pipeline splits the data set into training and test then loads
    * them into a [[HeliosDataSet]] object. Uses the [[data_splits_to_tensors()]]
    * method.
    *
    * The ground truth time lags are also returned.
    *
    * @param num_training The size of the training data set.
    * @param num_test The size of the test data set.
    * @param sliding_window The size of the causal time window.
    *
    * */
  def load_data_into_tensors[T: TF: IsFloatOrDouble](
    num_training: Int,
    num_test: Int,
    sliding_window: Int
  ): DataPipe[SLIDINGDATA[T], PROCDATA[T]] =
    DataPipe((data: SLIDINGDATA[T]) => {

      require(
        num_training + num_test == data.length,
        "Size of train and test data " + "must add up to total size of data!"
      )

      (data.take(num_training), data.takeRight(num_test))
    }) >
      data_splits_to_tensors(sliding_window)

  /**
    * Takes training and test data sets of type [[SLIDINGDATA]]
    * and loads them in an object of type [[PROCDATA]].
    *
    * @param sliding_window The size of the causal time window.
    *
    * */
  def data_splits_to_tensors[T: TF: IsFloatOrDouble](
    sliding_window: Int
  ): DataPipe2[SLIDINGDATA[T], SLIDINGDATA[T], PROCDATA[T]] =
    DataPipe2((training_data: SLIDINGDATA[T], test_data: SLIDINGDATA[T]) => {

      val features_train = dtf.stack(training_data.map(_._2._1), axis = 0)

      val features_test = dtf.stack(test_data.map(_._2._1), axis = 0)

      val labels_tr_flat = training_data.toList.flatMap(_._2._2.toList)

      val labels_train = dtf
        .tensor_f64(training_data.length, sliding_window)(labels_tr_flat: _*)
        .castTo[T]

      val labels_te_flat = test_data.toList.flatMap(_._2._2.toList)

      val labels_test = dtf
        .tensor_f64(test_data.length, sliding_window)(labels_te_flat: _*)
        .castTo[T]

      val (train_time_lags, test_time_lags): (Tensor[T], Tensor[T]) = (
        dtf
          .tensor_f64(training_data.length)(
            training_data.toList.map(d => d._2._3.toDouble): _*
          )
          .castTo[T],
        dtf
          .tensor_f64(test_data.length)(
            test_data.toList.map(d => d._2._3.toDouble): _*
          )
          .castTo[T]
      )

      //Create a helios data set.
      val tf_dataset = HeliosDataSet(
        features_train,
        labels_train,
        training_data.length,
        features_test,
        labels_test,
        test_data.length
      )

      (tf_dataset, (train_time_lags, test_time_lags))
    })

  /**
    * Takes training and test data sets of type [[SLIDINGDATA]]
    * and loads them in an object of type [[PROCDATA2]].
    *
    * [[PROCDATA2]] consists of a DynaML [[TFDataSet]] object,
    * along with the ground truth causal time lags for the
    * train and test sets.
    *
    * @param causal_window The size of the causal time window.
    *
    * */
  def data_splits_to_dataset[T: TF: IsFloatOrDouble](
    causal_window: Int
  ): DataPipe2[SLIDINGDATA[T], SLIDINGDATA[T], PROCDATA2[T]] =
    DataPipe2((training_data: SLIDINGDATA[T], test_data: SLIDINGDATA[T]) => {

      //Get the ground truth values of the causal time lags.
      val (train_time_lags, test_time_lags): (Tensor[T], Tensor[T]) = (
        dtf
          .tensor_f64(training_data.length)(
            training_data.toList.map(d => math.floor(d._2._3.toDouble)): _*
          )
          .castTo[T],
        dtf
          .tensor_f64(test_data.length)(
            test_data.toList.map(d => math.floor(d._2._3.toDouble)): _*
          )
          .castTo[T]
      )

      //Create the data set
      val train_dataset = dtfdata
        .dataset(training_data)
        .map(
          DataPipe(
            (p: SLIDINGPATT[T]) =>
              (p._2._1, dtf.tensor_f64(causal_window)(p._2._2: _*).castTo[T])
          )
        )

      val test_dataset = dtfdata
        .dataset(test_data)
        .map(
          DataPipe(
            (p: SLIDINGPATT[T]) =>
              (p._2._1, dtf.tensor_f64(causal_window)(p._2._2: _*).castTo[T])
          )
        )

      val tf_dataset = TFDataSet(train_dataset, test_dataset)

      (tf_dataset, (train_time_lags, test_time_lags))
    })

  /**
    * Scale training features/labels, on the test data; apply scaling
    * only to the features.
    *
    * */
  def scale_helios_dataset[T: TF: IsFloatOrDouble] =
    DataPipe((dataset: HeliosDataSet[T, T]) => {

      val (norm_tr_data, scalers) = dtfpipe
        .gaussian_standardization[T, T]
        .run(dataset.trainData, dataset.trainLabels)

      (
        dataset.copy(
          trainData = norm_tr_data._1,
          trainLabels = norm_tr_data._2,
          testData = scalers._1(dataset.testData)
        ),
        scalers
      )
    })

  /**
    * Data pipeline used by [[run_exp_joint()]] and [[run_exp_stage_wise()]]
    * methods to scale data before training.
    * */
  def scale_data_v1[T: TF: IsFloatOrDouble] = DataPipe(
    scale_helios_dataset[T],
    id[(Tensor[T], Tensor[T])]
  )

  /**
    * Data pipeline used by [[run_exp_hyp()]]
    * method to scale data before training.
    * */
  def scale_data_v2[T: TF: IsFloatOrDouble] = DataPipe(
    fte.data.scale_dataset[T],
    id[(Tensor[T], Tensor[T])]
  )

  /**
    * Returns the properties [[NNPROP]] (i.e. layer sizes, shapes, parameter names, & data types)
    * of a feed-forward/dense neural stack which consists of layers of equal size.
    *
    * @param d The dimensionality of the input (assumed to be a rank 1 tensor).
    * @param num_pred_dims The dimensionality of the network output.
    * @param num_neurons The size of each hidden layer.
    * @param num_hidden_layers The number of hidden layers.
    *
    * */
  def get_ffnet_properties(
    d: Int,
    num_pred_dims: Int,
    num_neurons: Int,
    num_hidden_layers: Int
  ): NNPROP = {

    val net_layer_sizes = Seq(d) ++ Seq.fill(num_hidden_layers)(num_neurons) ++ Seq(
      num_pred_dims
    )
    val layer_shapes =
      net_layer_sizes.sliding(2).toSeq.map(c => Shape(c.head, c.last))
    val layer_parameter_names =
      (1 to net_layer_sizes.tail.length).map(s => "Linear_" + s + "/Weights")
    val layer_datatypes = Seq.fill(net_layer_sizes.tail.length)("FLOAT64")

    (net_layer_sizes, layer_shapes, layer_parameter_names, layer_datatypes)
  }

  /**
    * Returns the properties [[NNPROP]] (i.e. layer sizes, shapes, parameter names, & data types)
    * of a feed-forward/dense neural stack which consists of layers of unequal size.
    *
    * @param d The dimensionality of the input (assumed to be a rank 1 tensor).
    * @param num_pred_dims The dimensionality of the network output.
    * @param layer_sizes The size of each hidden layer.
    * @param dType The data type of the layer weights and biases.
    * @param starting_index The numeric index of the first layer, defaults to 1.
    *
    * */
  def get_ffnet_properties(
    d: Int,
    num_pred_dims: Int,
    layer_sizes: Seq[Int],
    dType: String = "FLOAT64",
    starting_index: Int = 1
  ): NNPROP = {

    val net_layer_sizes = Seq(d) ++ layer_sizes ++ Seq(num_pred_dims)

    val layer_shapes =
      net_layer_sizes.sliding(2).toSeq.map(c => Shape(c.head, c.last))

    val size = net_layer_sizes.tail.length

    val layer_parameter_names = (starting_index until starting_index + size)
      .map(i => s"Linear_$i/Weights")

    val layer_datatypes = Seq.fill(net_layer_sizes.tail.length)(dType)

    (net_layer_sizes, layer_shapes, layer_parameter_names, layer_datatypes)
  }

  /**
    * Creates an output mapping layer which
    * produces outputs in the form desired by
    * time lag based loss functions in [[helios.core]].
    *
    * @param causal_window The size of the sliding causal time window.
    * @param mo_flag Set to true if the model produces predictions for each
    *                time step in the causal window.
    * @param prob_timelags Set to true if the time lag prediction is in the
    *                      form of a probability distribution over the causal
    *                      time window.
    * @param time_scale An optional parameter, used only if `mo_flag` and
    *                   `prob_timelags` are both set to false.
    * */
  def get_output_mapping[T: TF: IsFloatOrDouble](
    causal_window: Int,
    mo_flag: Boolean,
    prob_timelags: Boolean,
    dist_type: String,
    time_scale: Double = 1.0
  ): Layer[Output[T], (Output[T], Output[T])] =
    if (!mo_flag) {

      if (!prob_timelags)
        RBFWeightedSWLoss.output_mapping(
          "Output/RBFWeightedL1",
          causal_window,
          time_scale
        )
      else
        CausalDynamicTimeLagSO.output_mapping(
          "Output/SOProbWeightedTS",
          causal_window
        )

    } else if (mo_flag && !prob_timelags) {

      MOGrangerLoss.output_mapping("Output/MOGranger", causal_window)

    } else {
      dist_type match {
        case "poisson" =>
          helios.learn.cdt_poisson_loss
            .output_mapping(name = "Output/PoissonWeightedTS", causal_window)

        case "beta" =>
          helios.learn.cdt_beta_loss
            .output_mapping(name = "Output/BetaWeightedTS", causal_window)

        case "gaussian" =>
          helios.learn.cdt_gaussian_loss
            .output_mapping(name = "Output/GaussianWeightedTS", causal_window)

        case _ =>
          helios.learn.cdt_loss
            .output_mapping(name = "Output/ProbWeightedTS", causal_window)
      }
    }

  /**
    * Calculate the size of the
    * penultimate layer of a neural stack
    * used for causal time lag prediction.
    * @param causal_window The size of the sliding causal time window.
    * @param mo_flag Set to true if the model produces predictions for each
    *                time step in the causal window.
    * @param prob_timelags Set to true if the time lag prediction is in the
    *                      form of a probability distribution over the causal
    *                      time window.
    * */
  def get_num_output_dims(
    causal_window: Int,
    mo_flag: Boolean,
    prob_timelags: Boolean,
    dist_type: String
  ): Int =
    if (!mo_flag) 2
    else if (mo_flag && !prob_timelags) causal_window + 1
    else if (!mo_flag && prob_timelags) causal_window + 1
    else {
      dist_type match {
        case "poisson"  => causal_window + 1
        case "gaussian" => causal_window + 2
        case "beta"     => causal_window + 2
        case _          => 2 * causal_window
      }
    }

  def get_train_hooks(
    p: Path,
    it: Int,
    epochFlag: Boolean,
    num_data: Int,
    batch_size: Int,
    freq: Int = 4,
    freq_checkpoint: Int = 1
  ): Set[Hook] =
    if (epochFlag) {

      val epochSize = num_data / batch_size
      dtflearn.model._train_hooks(
        p,
        it * epochSize / freq,
        it * epochSize / freq,
        it * epochSize / freq_checkpoint
      )
    } else {
      dtflearn.model._train_hooks(p, it / freq, it / freq, it / freq_checkpoint)
    }

  /**
    * Creates a Tensorflow stop condition
    *
    * @param it Maximum number of iterations
    * @param tol The tolerance, above which loss improvement
    *            is required for continuing training
    * @param epochF Set to true if parameter `it` is
    *               to be treated as maximum number of epochs
    * @param num_data The size of the data set used in training.
    *                 Relevant only if `epochF` is set to true
    * @param batch_size Relevant only if `epochF` is set to true.
    * */
  def get_stop_condition(
    it: Int,
    tol: Double,
    epochF: Boolean,
    num_data: Int,
    batch_size: Int
  ): StopCriteria =
    if (epochF) {

      val epochSize = num_data / batch_size

      tf.learn.StopCriteria(
        maxSteps = Some(it * epochSize),
        maxEpochs = Some(it),
        relLossChangeTol = Some(tol)
      )
    } else {
      dtflearn.rel_loss_change_stop(tol, it)
    }

  /**
    * Get the appropriate causal time lag loss function.
    * @param sliding_window The size of the sliding causal time window.
    * @param mo_flag Set to true if the model produces predictions for each
    *                time step in the causal window.
    * @param prob_timelags Set to true if the time lag prediction is in the
    *                      form of a probability distribution over the causal
    *                      time window.
    * */
  def get_loss[
    P: TF: IsFloatOrDouble,
    T: TF: IsNumeric: IsNotQuantized,
    L: TF: IsFloatOrDouble
  ](sliding_window: Int,
    mo_flag: Boolean,
    prob_timelags: Boolean,
    p: Double = 1.0,
    time_scale: Double = 1.0,
    corr_sc: Double = 2.5,
    c_cutoff: Double = 0.0,
    prior_wt: Double = 1d,
    prior_divergence: helios.learn.cdt_loss.Divergence =
      helios.learn.cdt_loss.KullbackLeibler,
    target_dist: helios.learn.cdt_loss.TargetDistribution =
      helios.learn.cdt_loss.Boltzmann,
    temp: Double = 1.0,
    error_wt: Double = 1.0,
    c: Double = 1.0
  ): Loss[((Output[P], Output[P]), Output[T]), L] =
    if (!mo_flag) {
      if (!prob_timelags) {
        RBFWeightedSWLoss(
          "Loss/RBFWeightedL1",
          sliding_window,
          kernel_time_scale = time_scale,
          kernel_norm_exponent = p,
          corr_cutoff = c_cutoff,
          prior_scaling = corr_sc,
          batch = 512
        )
      } else {
        helios.learn.cdt_loss_so(
          "Loss/ProbWeightedTS",
          sliding_window,
          prior_wt = prior_wt,
          error_wt = error_wt,
          temperature = 0.75,
          divergence = prior_divergence,
          specificity = c
        )
      }

    } else if (mo_flag && !prob_timelags) {

      MOGrangerLoss(
        "Loss/MOGranger",
        sliding_window,
        error_exponent = p,
        weight_error = prior_wt
      )

    } else {
      helios.learn.cdt_loss(
        "Loss/ProbWeightedTS",
        sliding_window,
        prior_wt = prior_wt,
        error_wt = error_wt,
        temperature = temp,
        divergence = prior_divergence,
        target_distribution = target_dist,
        specificity = c
      )
    }

  def get_pdt_loss[
    P: TF: IsFloatOrDouble,
    T: TF: IsNumeric: IsNotQuantized,
    L: TF: IsFloatOrDouble
  ](sliding_window: Int,
    error_var: Double,
    specificity: Double
  ): Loss[((Output[P], Output[P]), Output[T]), L] =
    helios.learn.pdt_loss("Loss/PDT", error_var, specificity, sliding_window)

  // Utilities for computing CDT model stability.

  type ZipPattern = ((Tensor[Double], Tensor[Double]), Tensor[Double])

  type StabTriple = Tensor[Double]

  type DataTriple =
    (DataSet[Tensor[Double]], DataSet[Tensor[Double]], DataSet[Tensor[Double]])

  case class Stability(
    s0: Double,
    c1: Double,
    c2: Double,
    c2_d: Double,
    n: Int) {

    val is_stable: Boolean = c2 < 2 * c1 * c1

    val degenerate_unstable: Boolean = c2_d > 2d * (1d - (1d / n))
  }

  def compute_stability_metrics(
    predictions: DataSet[Tensor[Double]],
    probabilities: DataSet[Tensor[Double]],
    targets: DataSet[Tensor[Double]],
    state: Map[String, Double],
    params_enc: Encoder[
      Map[String, Double],
      Map[String, Double]
    ],
    std_pipe: DataPipe[Tensor[Double], (Tensor[Double], GaussianScalerTF[
        Double
      ])] = dtfpipe.gauss_std[Double]()
  ): Stability = {

    val (alpha, sigma_sq) = {
      val enc_state = params_enc(state)
      (enc_state("alpha"), enc_state("sigma_sq"))
    }

    val n = predictions.data.head.shape(0)

    val one = Tensor(1d).reshape(Shape())

    val two = Tensor(2d).reshape(Shape())

    //First standardize the targets and predictions
    //because during training the saddle point probabilities
    //were computed with respect to the standardized quantities.
    val (_, gauss_scaler) = std_pipe(tfi.stack(targets.data.toSeq, axis = 0))

    val compute_metrics = DataPipe[ZipPattern, StabTriple](zp => {

      val ((y, prob), t) = zp

      val sq_error = y.subtract(t).square

      val std_sq_error = gauss_scaler(y).subtract(gauss_scaler(t)).square

      val un_p = prob * (
        tfi.exp(
          tfi.log(one + alpha) / two - (std_sq_error * alpha) / (two * sigma_sq)
        )
      )

      //Calculate the saddle point probability
      val p = un_p / un_p.sum(axes = 0, keepDims = true)

      val s0 = sq_error.sum().scalar

      val c1 = p.multiply(sq_error).sum().scalar

      val c2 = p.multiply(sq_error.subtract(c1).square).sum().scalar

      val c2_d = sq_error.subtract(s0 / n).square.sum().scalar

      dtf.tensor_f64(5)(s0, c1, c2, c2_d, 1.0)
    })

    val result = predictions
      .zip(probabilities)
      .zip(targets)
      .map(compute_metrics)
      .reduce(DataPipe2[StabTriple, StabTriple, StabTriple](_ + _))

    val s0 = result(0).divide(result(4)).scalar / n

    val c1 = result(1).divide(result(4)).scalar / s0

    val c2 = result(2).divide(result(4)).scalar / (s0 * s0)

    val c2_d = result(3).divide(result(4)).scalar / (n * s0 * s0)

    Stability(s0, c1, c2, c2_d, n)
  }

  def read_cdt_model_preds(
    preds: Path,
    probs: Path,
    targets: Path
  ): DataTriple = {

    val read_lines = DataPipe[Path, Iterable[String]](read.lines ! _)

    val split_lines = IterableDataPipe(
      (line: String) => line.split(',').map(_.toDouble)
    )

    val load_into_tensor = IterableDataPipe(
      (ls: Array[Double]) => dtf.tensor_f64(ls.length)(ls.toSeq: _*)
    )

    val load_data = read_lines > split_lines > load_into_tensor

    (
      dtfdata.dataset(Seq(preds)).flatMap(load_data),
      dtfdata.dataset(Seq(probs)).flatMap(load_data),
      dtfdata.dataset(Seq(targets)).flatMap(load_data)
    )

  }

  def collect_predictions[T: TF: IsFloatOrDouble](
    preds: DataSet[(Tensor[T], Tensor[T])]
  ): (Tensor[T], Tensor[T]) =
    (
      tfi.stack(
        preds.map((p: (Tensor[T], Tensor[T])) => p._1).data.toSeq,
        axis = 0
      ),
      tfi.stack(
        preds.map((p: (Tensor[T], Tensor[T])) => p._2).data.toSeq,
        axis = 0
      )
    )

  def collect_batched_predictions_bl[T: TF: IsFloatOrDouble](
    preds: DataSet[Tensor[T]]
  ): Tensor[T] =
  tfi.concatenate(
    preds.data.toSeq,
    axis = 0
  )

  /**
    * Process the predictions made by a causal time lag model.
    *
    * */
  def process_predictions[T: TF: IsFloatOrDouble](
    predictions: (Tensor[T], Tensor[T]),
    time_window: Int,
    multi_output: Boolean = true,
    probabilistic_time_lags: Boolean = true,
    timelag_pred_strategy: String = "mode",
    scale_outputs: Option[GaussianScalerTF[T]] = None
  ): (Tensor[T], Tensor[T]) = {

    val index_times = Tensor(
      (0 until time_window).map(_.toDouble)
    ).reshape(
        Shape(time_window)
      )
      .castTo[T]

    val pred_time_lags = if (probabilistic_time_lags) {
      val unsc_probs = predictions._2

      if (timelag_pred_strategy == "mode")
        unsc_probs.topK(1)._2.reshape(Shape(predictions._1.shape(0))).castTo[T]
      else unsc_probs.multiply(index_times).sum(axes = 1)

    } else predictions._2

    val pred_targets: Tensor[T] = if (multi_output) {

      val all_preds =
        if (scale_outputs.isDefined) scale_outputs.get.i(predictions._1)
        else predictions._1

      val repeated_times =
        tfi.stack(Seq.fill(time_window)(pred_time_lags.floor), axis = -1)

      val conv_kernel = repeated_times
        .subtract(index_times)
        .square
        .multiply(Tensor(-1.0).castTo[T])
        .exp
        .floor

      all_preds
        .multiply(conv_kernel)
        .sum(axes = 1)
        .divide(conv_kernel.sum(axes = 1))

    } else {

      if (scale_outputs.isDefined) {
        val scaler = scale_outputs.get
        scaler(0).i(predictions._1)
      } else predictions._1

    }

    (pred_targets, pred_time_lags)

  }

  def plot_time_series[T: TF: IsNotQuantized: IsReal](
    targets: Tensor[T],
    predictions: Tensor[T],
    plot_title: String
  ): Unit = {
    line(
      dtfutils.toDoubleSeq(targets).zipWithIndex.map(c => (c._2, c._1)).toSeq
    )
    hold()
    line(
      dtfutils
        .toDoubleSeq(predictions)
        .zipWithIndex
        .map(c => (c._2, c._1))
        .toSeq
    )
    legend(Seq("Actual Output Signal", "Predicted Output Signal"))
    title(plot_title)
    unhold()
  }

  def plot_time_series[T: TF: IsNotQuantized: IsReal](
    targets: Stream[(Int, Double)],
    predictions: Tensor[T],
    plot_title: String
  ): Unit = {
    line(targets.toSeq)
    hold()
    line(
      dtfutils
        .toDoubleSeq(predictions)
        .zipWithIndex
        .map(c => (c._2, c._1))
        .toSeq
    )
    legend(Seq("Actual Output Signal", "Predicted Output Signal"))
    title(plot_title)
    unhold()
  }

  /**
    * Takes a tensor of rank 1 (Shape(n)) and plots a histogram.
    * */
  @throws[java.util.NoSuchElementException]
  @throws[Exception]
  def plot_histogram[T: TF: IsNotQuantized: IsReal](
    data: Tensor[T],
    plot_title: String
  ): Unit = {
    try {

      histogram(dtfutils.toDoubleSeq(data).toSeq)
      title(plot_title)

    } catch {
      case _: java.util.NoSuchElementException =>
        println("Can't plot histogram due to `No Such Element` exception")
      case _: Throwable => println("Can't plot histogram due to exception")
    }
  }

  /**
    * Plot input-output pairs as a scatter plot.
    *
    * @param input A Stream of input patterns.
    * @param input_to_scalar A function which processes each multi-dimensional
    *                        pattern to a scalar value.
    * @param targets A tensor containing the ground truth values of the target
    *                function.
    * @param predictions A tensor containing the model predictions.
    * @param xlab x-axis label
    * @param ylab y-axis label
    * @param plot_title The plot title, as a string.
    * */
  def plot_input_output[T: TF: IsNotQuantized: IsReal](
    input: Stream[Tensor[T]],
    input_to_scalar: Tensor[T] => Double,
    targets: Tensor[T],
    predictions: Tensor[T],
    xlab: String,
    ylab: String,
    plot_title: String
  ): Unit = {

    val processed_inputs = input.map(input_to_scalar)

    scatter(processed_inputs.zip(dtfutils.toDoubleSeq(predictions).toSeq))

    hold()
    scatter(processed_inputs.zip(dtfutils.toDoubleSeq(targets).toSeq))
    xAxis(xlab)
    yAxis(ylab)
    title(plot_title)
    legend(Seq("Model", "Data"))
    unhold()
  }

  /**
    * Plot multiple input-output pairs on a scatter plot.
    *
    * @param input A Stream of input patterns.
    * @param input_to_scalar A function which processes each multi-dimensional
    *                        pattern to a scalar value.
    * @param predictions A sequence of tensors containing the predictions for each model/predictor.
    * @param xlab x-axis label
    * @param ylab y-axis label
    * @param plot_legend A sequence of labels for each model/predictor,
    *                    to be displayed as the plot legend.
    * @param plot_title The plot title, as a string.
    * */
  def plot_input_output[T: TF: IsNotQuantized: IsReal](
    input: Stream[Tensor[T]],
    input_to_scalar: Tensor[T] => Double,
    predictions: Seq[Tensor[T]],
    xlab: String,
    ylab: String,
    plot_legend: Seq[String],
    plot_title: String
  ): Unit = {

    val processed_inputs = input.map(input_to_scalar)

    scatter(processed_inputs.zip(dtfutils.toDoubleSeq(predictions.head).toSeq))
    hold()
    predictions.tail.foreach(pred => {
      scatter(processed_inputs.zip(dtfutils.toDoubleSeq(pred).toSeq))
    })

    xAxis(xlab)
    yAxis(ylab)
    title(plot_title)
    legend(plot_legend)
    unhold()
  }

  /**
    * Plot input-output pairs as a scatter plot.
    *
    * @param xy The x and y axis data, a sequence of tuples
    * @param xlab x-axis label
    * @param ylab y-axis label
    * @param plot_title The plot title, as a string.
    * */
  def plot_scatter(
    xy: Seq[(Double, Double)],
    xlab: Option[String] = None,
    ylab: Option[String] = None,
    plot_title: Option[String] = None
  ): Unit = {
    scatter(xy)
    if (xlab.isDefined) xAxis(xlab.get)
    if (ylab.isDefined) yAxis(ylab.get)
    if (plot_title.isDefined) title(plot_title.get)
  }

  /**
    * Write a time lag data set to disk.
    *
    * Writes three csv files of the form.
    *
    * <ul>
    *   <li>summary_dir/identifier_features.csv</li>
    *   <li>summary_dir/identifier_output_lag.csv</li>
    *   <li>summary_dir/identifier_targets.csv</li>
    * </ul>
    *
    * @param data A generated data set of type [[TLDATA]]
    * @param summary_dir Path where the data should be written
    * @param identifier A string which starts each file name
    * */
  def write_data_set[T: TF: IsFloatOrDouble: IsReal](
    data: TLDATA[T],
    summary_dir: Path,
    identifier: String
  ): Unit = {

    //Write the features.
    write.over(
      summary_dir / s"${identifier}_features.csv",
      data._2
        .map(_._2._1)
        .map(x => dtfutils.toDoubleSeq(x).mkString(","))
        .mkString("\n")
    )

    write.over(
      summary_dir / s"${identifier}_output_lag.csv",
      data._1
        .map(_._2)
        .map(x => s"${x._1},${x._2}")
        .mkString("\n")
    )

    //Write the slided outputs.
    write.over(
      summary_dir / s"${identifier}_targets.csv",
      data._2
        .map(_._2._2)
        .map(_.mkString(","))
        .mkString("\n")
    )

  }

  /**
    * Write model outputs to disk.
    *
    * Writes two csv files of the form.
    *
    * <ul>
    *   <li>summary_dir/identifier_predictions.csv</li>
    *   <li>summary_dir/identifier_probabilities.csv</li>
    * </ul>
    *
    * @param outputs Model outputs
    * @param summary_dir Path where the data should be written
    * @param identifier A string which starts each file name
    * */
  def write_model_outputs[T: TF: IsNotQuantized: IsReal](
    outputs: (Tensor[T], Tensor[T]),
    summary_dir: Path,
    identifier: String,
    append: Boolean = false
  ): Unit = {

    val h = outputs._1.shape(1)

    if (append) {
      write.append(
        summary_dir / s"${identifier}_predictions.csv",
        dtfutils
          .toDoubleSeq(outputs._1)
          .grouped(h)
          .map(_.mkString(","))
          .mkString("\n")
      )

      write.append(
        summary_dir / s"${identifier}_probabilities.csv",
        dtfutils
          .toDoubleSeq(outputs._2)
          .grouped(h)
          .map(_.mkString(","))
          .mkString("\n")
      )
    } else {
      write.over(
        summary_dir / s"${identifier}_predictions.csv",
        dtfutils
          .toDoubleSeq(outputs._1)
          .grouped(h)
          .map(_.mkString(","))
          .mkString("\n")
      )

      write.over(
        summary_dir / s"${identifier}_probabilities.csv",
        dtfutils
          .toDoubleSeq(outputs._2)
          .grouped(h)
          .map(_.mkString(","))
          .mkString("\n")
      )
    }

  }

  /**
    * Write model predictions and ground truth to disk,
    * in the form:
    *
    * summary_dir/identifier_scatter.csv
    *
    * @param predictions A sequence of predicted outputs and lags
    * @param ground_truth Corresponding actual outputs and lags
    * @param summary_dir Path where the data should be written
    * @param identifier A string which starts each file name
    * */
  def write_predictions_and_gt(
    predictions: Seq[(Double, Double)],
    ground_truth: Seq[(Double, Double)],
    summary_dir: Path,
    identifier: String
  ): Unit = {

    write.over(
      summary_dir / s"${identifier}_scatter.csv",
      "predv,predlag,actualv,actuallag\n" +
        predictions
          .zip(ground_truth)
          .map(
            c => s"${c._1._1},${c._1._2},${c._2._1},${c._2._2}"
          )
          .mkString("\n")
    )
  }

  /**
    * Write evaluation metrics to disk
    * */
  def write_performance[T: TF: IsReal](
    train_performance: (RegressionMetricsTF[T], RegressionMetricsTF[T]),
    test_performance: (RegressionMetricsTF[T], RegressionMetricsTF[T]),
    directory: Path
  ): Unit = {

    write.over(
      directory / s"training_performance.json",
      s"[${train_performance._1.to_json},\n${train_performance._2.to_json}]"
    )

    write.over(
      directory / s"test_performance.json",
      s"[${test_performance._1.to_json},\n${test_performance._2.to_json}]"
    )
  }

  def write_performance_baseline[T: TF: IsReal](
    train_performance: RegressionMetricsTF[T],
    test_performance: RegressionMetricsTF[T],
    directory: Path
  ): Unit = {

    write.over(
      directory / s"training_performance.json",
      s"${train_performance.to_json}"
    )

    write.over(
      directory / s"test_performance.json",
      s"${test_performance.to_json}"
    )
  }

}
