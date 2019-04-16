package io.github.mandar2812.PlasmaML.helios.core

import ammonite.ops._
import io.github.mandar2812.dynaml.utils.annotation.Experimental
import io.github.mandar2812.dynaml.tensorflow._
import io.github.mandar2812.dynaml.models._
import io.github.mandar2812.dynaml.utils
import io.github.mandar2812.dynaml.evaluation.Performance
import io.github.mandar2812.dynaml.pipes.{
  DataPipe,
  DataPipe2,
  MetaPipe,
  Encoder
}
import io.github.mandar2812.dynaml.tensorflow.data.{DataSet, TFDataSet}

import org.json4s._
import org.json4s.jackson.Serialization.{read => read_json, write => write_json}

import org.platanios.tensorflow.api.core.types.{IsFloatOrDouble, TF}
import org.platanios.tensorflow.api._

/**
  * <h3>Probabilistic Dynamic Time Lag Model</h3>
  *
  * @param time_window The size of the time window in steps.
  * @param modelFunction Generates a tensorflow model instance
  *                      from hyper-parameters.
  * @param model_config_func Generates model training configuration
  *                          from hyper-parameters.
  * @param hyp_params A collection of hyper-parameters.
  * @param persistent_hyp_params The subset of the hyper-parameters which
  *                              are not updated.
  * @param params_to_mutable_params A one-to-one invertible mapping between
  *                                 the loss function parameters to the
  *                                 cannonical parameters "alpha" and "sigma_sq".
  * @param training_data The training data collection.
  * @param tf_data_handle_ops An instance of [[dtflearn.model.Ops]], describes
  *                           how the data patterns should be loaded into a
  *                           Tensorflow dataset handle.
  * @param fitness_to_scalar A function which processes all the computed metrics
  *                          and returns a single fitness score.
  * @param validation_data An optional validation data collection.
  *
  * @param data_split_func An optional data pipeline which divides the
  *                        training collection into a train and validation split.
  *
  * */
class PDTModel[Pattern, In, IT, ID, IS, T: TF: IsFloatOrDouble, Loss: TF: IsFloatOrDouble](
  val time_window: Int,
  override val modelFunction: TunableTFModel.ModelFunc[
    In,
    Output[T],
    (Output[T], Output[T]),
    Loss,
    IT,
    ID,
    IS,
    Tensor[T],
    DataType[T],
    Shape,
    (Tensor[T], Tensor[T]),
    (DataType[T], DataType[T]),
    (Shape, Shape)
  ],
  val model_config_func: dtflearn.tunable_tf_model.ModelConfigFunction[
    In,
    Output[T]
  ],
  override val hyp_params: Seq[String],
  val persistent_hyp_params: Seq[String],
  val params_to_mutable_params: Encoder[
    dtflearn.tunable_tf_model.HyperParams,
    dtflearn.tunable_tf_model.HyperParams
  ],
  override protected val training_data: DataSet[Pattern],
  override val tf_data_handle_ops: dtflearn.model.TFDataHandleOps[
    Pattern,
    IT,
    Tensor[T],
    (Tensor[T], Tensor[T]),
    In,
    Output[T]
  ],
  override val fitness_to_scalar: DataPipe[Seq[Tensor[Float]], Double] =
    DataPipe[Seq[Tensor[Float]], Double](m =>
        m.map(_.scalar.toDouble).sum / m.length),
  override protected val validation_data: Option[DataSet[Pattern]] = None,
  override protected val data_split_func: Option[DataPipe[Pattern, Boolean]] =
    None)
    extends TunableTFModel[
      Pattern, In, Output[T], (Output[T], Output[T]), 
      Loss, IT, ID, IS, Tensor[T], DataType[T], Shape, 
      (Tensor[T], Tensor[T]), (DataType[T], DataType[T]), 
      (Shape, Shape)](
      modelFunction,
      model_config_func,
      hyp_params,
      training_data,
      tf_data_handle_ops,
      Seq(PDTModel.s0, PDTModel.c1, PDTModel.c2),
      fitness_to_scalar,
      validation_data,
      data_split_func
    ) {

  val mutable_params_to_metric_functions: DataPipe[
    dtflearn.tunable_tf_model.HyperParams,
    Seq[
      DataPipe2[(Output[T], Output[T]), Output[T], Output[Float]]
    ]
  ] =
    DataPipe(
      (c: Map[String, Double]) =>
        Seq(
          PDTModel.s0,
          PDTModel.c1,
          PDTModel.c2
        )
    )

  val params_to_metric_funcs: DataPipe[
    dtflearn.tunable_tf_model.HyperParams,
    Seq[
      DataPipe2[(Output[T], Output[T]), Output[T], Output[Float]]
    ]
  ] = params_to_mutable_params > mutable_params_to_metric_functions

  val metrics_to_mutable_params
    : DataPipe[Seq[Tensor[Float]], dtflearn.tunable_tf_model.HyperParams] =
    DataPipe((s: Seq[Tensor[Float]]) => {
      val s0 = s(0).scalar.toDouble
      val c1 = s(1).scalar.toDouble / s0

      Map(
        "alpha"    -> math.max(time_window * (1d - c1) / (c1 * (time_window - 1)), 0d),
        "sigma_sq" -> s0 * (time_window - c1) / (time_window - 1)
      )
    })

  private def update(
    p: Map[String, Double],
    h: Map[String, Double],
    config: Option[dtflearn.model.Config[In, Output[T]]] = None,
    eval_trigger: Option[Int] = None
  ): Map[String, Double] = {

    //Train and evaluate the model on the given hyper-parameters
    val train_config = config match {
      case None => modelConfigFunc(p.toMap)
      case Some(config) => config
    }

    val stability_metrics = params_to_metric_funcs(h)
      .zip(PDTModel.stability_quantities)
      .map(fitness_function => {
        Performance[((Output[T], Output[T]), (In, Output[T]))](
          fitness_function._2,
          DataPipe[
            ((Output[T], Output[T]), (In, Output[T])),
            Output[Float]
          ](
            c => fitness_function._1(c._1, c._2._2)
          )
        )
      })

    val eval_metrics = eval_trigger match {
      case None => None
      case Some(t) =>
        Some(
          PDTModel.stability_quantities
            .zip(params_to_metric_funcs(h))
        )
    }

    val updated_params = try {
      val model = train_model(
        p ++ h,
        Some(train_config),
        evaluation_metrics = eval_metrics,
        eval_trigger,
        true
      )

      println("Computing PDT stability metrics.")
      val metrics = model.evaluate(
        train_data_tf,
        train_split.size,
        stability_metrics,
        train_config.data_processing.copy(shuffleBuffer = 0, repeat = 0),
        true,
        null
      )
      (metrics_to_mutable_params > params_to_mutable_params.i)(metrics)
    } catch {
      case e: java.lang.IllegalStateException =>
        h
      case e: Throwable =>
        e.printStackTrace()
        h
    }

    println("\nUpdated Parameters: ")
    pprint.pprintln(p ++ updated_params)
    println()
    updated_params
  }

  def solve(
    pdt_iterations: Int,
    hyper_params: TunableTFModel.HyperParams,
    config: Option[dtflearn.model.Config[In, Output[T]]] = None,
    eval_trigger: Option[Int] = None
  ): Map[String, Double] = {

    val (p, t) =
      hyper_params.toSeq.partition(kv => persistent_hyp_params.contains(kv._1))

    if(pdt_iterations > 0) (1 to pdt_iterations).foldLeft(t.toMap)((s, it) => {

      val buffstr = if(it >= 10) "="*(math.log10(it).toInt) else ""

      println()
      println(s"╔=═════════════════════════════════════════${buffstr}═╗")
      println(s"║ PDT Alternate Optimization - Iteration: ${it} ║")
      println(s"╚══════════════════════════════════════════${buffstr}=╝")
      println()
      update(p.toMap, s, config, eval_trigger)
    })
    else t.toMap
  }

  def build(
    pdt_iterations: Int,
    hyper_params: dtflearn.tunable_tf_model.HyperParams,
    config: Option[dtflearn.model.Config[In, Output[T]]] = None,
    eval_trigger: Option[Int] = None
  ) = {
    val p = hyper_params.filterKeys(persistent_hyp_params.contains _)

    //Train and evaluate the model on the given hyper-parameters

    //Start by loading the model configuration,
    //which depends only on the `persistent`
    //hyper-parameters.
    val train_config = config match {
      case None => modelConfigFunc(p.toMap)
      case Some(config) => config
    }

    //Run the hyper-parameter refinement procedure.
    val final_config: Map[String, Double] =
      solve(pdt_iterations, hyper_params, Some(train_config), eval_trigger)

    val stability_metrics = params_to_metric_funcs(final_config)
      .zip(PDTModel.stability_quantities)
      .map(fitness_function => {
        Performance[((Output[T], Output[T]), (In, Output[T]))](
          fitness_function._2,
          DataPipe[
            ((Output[T], Output[T]), (In, Output[T])),
            Output[Float]
          ](
            c => fitness_function._1(c._1, c._2._2)
          )
        )
      })

    val eval_metrics = eval_trigger match {
      case None => None
      case Some(t) =>
        Some(
          PDTModel.stability_quantities
            .zip(params_to_metric_funcs(final_config))
        )
    }

    println("\nTraining model based on final chosen parameters")
    pprint.pprintln(p.toMap ++ final_config)
    println()

    val model = train_model(
      p.toMap ++ final_config,
      Some(train_config),
      evaluation_metrics = eval_metrics,
      eval_trigger,
      true
    )

    (model, final_config)
  }

  override def energy(
    hyper_params: TunableTFModel.HyperParams,
    options: Map[String, String]
  ): Double = {

    val p = hyper_params.filterKeys(persistent_hyp_params.contains _)

    //Train and evaluate the model on the given hyper-parameters

    //Start by loading the model configuration,
    //which depends only on the `persistent`
    //hyper-parameters.
    val train_config = modelConfigFunc(p.toMap)

    //The number of times the mutable hyper-parameters
    //will be updated.
    val loop_count = options.getOrElse("loops", "2").toInt

    //Now compute the model fitness score.
    val (fitness, comment, final_config) = try {

      
      //Run the refinement procedure.
      val (model, final_config) = build(
        loop_count,
        hyper_params,
        eval_trigger = options.get("evalTrigger").map(_.toInt)
      )

      val stability_metrics = params_to_metric_funcs(final_config)
        .zip(PDTModel.stability_quantities)
        .map(fitness_function => {
          Performance[((Output[T], Output[T]), (In, Output[T]))](
            fitness_function._2,
            DataPipe[
              ((Output[T], Output[T]), (In, Output[T])),
              Output[Float]
            ](
              c => fitness_function._1(c._1, c._2._2)
            )
          )
        })

      println("Computing Energy.")
      val e = fitness_to_scalar(
        model.evaluate(
          validation_data_tf,
          validation_split.size,
          stability_metrics,
          train_config.data_processing.copy(shuffleBuffer = 0, repeat = 0),
          true,
          null
        )
      )

      (e, None, final_config)
    } catch {
      case e: java.lang.IllegalStateException =>
        (Double.PositiveInfinity, Some(e.getMessage), hyper_params)
      case e: Throwable =>
        e.printStackTrace()
        (Double.PositiveInfinity, Some(e.getMessage), hyper_params)
    }

    //Append the model fitness to the hyper-parameter configuration
    val hyp_config_json = write_json(
      p.toMap ++ final_config ++ Map(
        "energy"  -> fitness,
        "comment" -> comment.getOrElse("")
      )
    )

    //Write the configuration along with its fitness into the model
    //instance's summary directory
    write.append(train_config.summaryDir / "state.json", hyp_config_json + "\n")

    //Return the model fitness.
    fitness
  }

}

object PDTModel {

  final val mutable_params: Seq[String] = Seq("alpha", "sigma_sq")

  val stability_quantities = Seq("s0", "c1", "c2")

  def s0[T: TF: IsFloatOrDouble] =
    DataPipe2[(Output[T], Output[T]), Output[T], Output[Float]](
      (outputs, targets) => {

        val (preds, probs) = outputs

        val sq_errors = preds.subtract(targets).square

        sq_errors.mean(axes = 1).castTo[Float]
      }
    )

  def c1[T: TF: IsFloatOrDouble] =
    DataPipe2[(Output[T], Output[T]), Output[T], Output[Float]](
      (outputs, targets) => {

        val (preds, probs) = outputs

        val sq_errors = preds.subtract(targets).square

        probs.multiply(sq_errors).sum(axes = 1).castTo[Float]
      }
    )

  def c2[T: TF: IsFloatOrDouble] =
    DataPipe2[(Output[T], Output[T]), Output[T], Output[Float]](
      (outputs, targets) => {

        val (preds, probs) = outputs

        val sq_errors = preds.subtract(targets).square
        val c1        = probs.multiply(sq_errors).sum(axes = 1, keepDims = true)
        probs
          .multiply(sq_errors.subtract(c1).square)
          .sum(axes = 1)
          .castTo[Float]
      }
    )

  def c1[T: TF: IsFloatOrDouble](alpha: T, sigma_sq: T, n: Int) =
    DataPipe2[(Output[T], Output[T]), Output[T], Output[Float]](
      (outputs, targets) => {

        val (preds, probs) = outputs

        val sq_errors = preds.subtract(targets).square

        val one = Tensor(1d).toOutput.castTo[T]

        val two = Tensor(2d).toOutput.castTo[T]

        val un_p = probs * (
          tf.exp(
            tf.log(one + alpha) / two - (sq_errors * alpha) / (two * sigma_sq)
          )
        )

        //Calculate the saddle point probability
        val p = un_p / un_p.sum(axes = 1, keepDims = true)

        val c1 = p.multiply(sq_errors).sum(axes = 1).castTo[Float]

        c1
      }
    )

  def c2[T: TF: IsFloatOrDouble](alpha: T, sigma_sq: T, n: Int) =
    DataPipe2[(Output[T], Output[T]), Output[T], Output[Float]](
      (outputs, targets) => {

        val (preds, probs) = outputs

        val sq_errors = preds.subtract(targets).square
        val one       = Tensor(1d).toOutput.castTo[T]
        val two       = Tensor(2d).toOutput.castTo[T]

        val un_p = probs * (
          tf.exp(
            tf.log(one + alpha) / two - (sq_errors * alpha) / (two * sigma_sq)
          )
        )

        //Calculate the saddle point probability
        val p = un_p / un_p.sum(axes = 1, keepDims = true)

        val c1 = p.multiply(sq_errors).sum(axes = 1, keepDims = true)

        p.multiply(sq_errors.subtract(c1).square)
          .sum(axes = 1)
          .castTo[Float]
      }
    )

  def apply[Pattern, In, IT, ID, IS, T: TF: IsFloatOrDouble, Loss: TF: IsFloatOrDouble](
    time_window: Int,
    modelFunction: TunableTFModel.ModelFunc[
      In,
      Output[T],
      (Output[T], Output[T]),
      Loss,
      IT,
      ID,
      IS,
      Tensor[T],
      DataType[T],
      Shape,
      (Tensor[T], Tensor[T]),
      (DataType[T], DataType[T]),
      (Shape, Shape)
    ],
    model_config_func: dtflearn.tunable_tf_model.ModelConfigFunction[
      In,
      Output[T]
    ],
    hyp_params: Seq[String],
    persistent_hyp_params: Seq[String],
    params_to_mutable_params: Encoder[
      dtflearn.tunable_tf_model.HyperParams,
      dtflearn.tunable_tf_model.HyperParams
    ],
    training_data: DataSet[Pattern],
    tf_data_handle_ops: dtflearn.model.TFDataHandleOps[
      Pattern,
      IT,
      Tensor[T],
      (Tensor[T], Tensor[T]),
      In,
      Output[T]
    ],
    fitness_to_scalar: DataPipe[Seq[Tensor[Float]], Double] =
      DataPipe[Seq[Tensor[Float]], Double](m =>
          m.map(_.scalar.toDouble).sum / m.length),
    validation_data: Option[DataSet[Pattern]] = None,
    data_split_func: Option[DataPipe[Pattern, Boolean]] = None
  ) = new PDTModel[Pattern, In, IT, ID, IS, T, Loss](
    time_window,
    modelFunction,
    model_config_func,
    hyp_params,
    persistent_hyp_params,
    params_to_mutable_params,
    training_data,
    tf_data_handle_ops,
    fitness_to_scalar,
    validation_data,
    data_split_func
  )

}
