package mixer

import akka.actor.{ActorSystem, Cancellable}
import akka.agent.Agent
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.javadsl.RunnableGraph
import akka.stream.scaladsl._
import akka.stream.{ClosedShape, Materializer}
import stream.rate.{DelayStage2, Poisson, Rate}

import scala.concurrent.Future
import scala.concurrent.duration._

object FlowGraphs extends JsonProtocol {

  /**
   * Top level mixer runnable graph that is materialized for each requested mix and is expected to run until cancelled.
   *
   * The mixer consumes the transaction log and calculates and posts the necessary transfers to execute the mix
   * according to the provided specification.
   *
   * The graph materializes two values.
   * A Cancellable that can be used to cancel the transaction source, thus terminating the mixer.
   * An Agent that can be used to obtain the latest mix state to provide external visibility, e.g. via an HTTP route.
   *
   * @param specification mix specification used to calculate transfers
   * @param transactions transaction log source
   * @param delay transfer scheduling delay
   * @param post transfer posting sink used to submit transfers
   */
  def mixerGraph(
    specification: MixSpecification,
    transactions: Source[List[Transaction], Cancellable],
    delay: Flow[Transfer, Transfer, _],
    post: Sink[Transfer, _]
  )(implicit materializer: Materializer, system: ActorSystem): RunnableGraph[(Cancellable, Agent[MixState])] = {

    implicit val executionContext = materializer.executionContext

    RunnableGraph.fromGraph(FlowGraph.create(
      transactions,
      tail,
      mix(specification),
      Unzip[MixState, List[Transfer]],
      agent(Agent(MixState(specification))),
      transfers,
      delay,
      post
    )((cancellable, _, _, _, agent, _, _, _) => (cancellable, agent)) {
      implicit b => (transactions, tail, mix, unzip, agent, transfers, delay, post) =>
        import FlowGraph.Implicits._

        transactions ~> tail ~> mix ~> unzip.in
                                       unzip.out0 ~> agent
                                       unzip.out1 ~> transfers ~> delay ~> post
        ClosedShape
    })
  }

  /**
   * Polls the given endpoint for transactions.
   *
   * @param uri transaction endpoint URI
   * @param period polling period
   * @return
   */
  def transactions(uri: Uri, period: FiniteDuration)(implicit materializer: Materializer, system: ActorSystem): Source[List[Transaction], Cancellable] = {
    implicit val executionContext = materializer.executionContext
    Source.tick(Duration.Zero, period, ()).map { tick =>
      Http().singleRequest(HttpRequest(uri = uri)).flatMap { response =>
        response.status match {
          case OK => Unmarshal(response.entity).to[List[Transaction]]
          case _ => Future.successful(List.empty[Transaction])
        }
      }
    }.flatMapConcat(txs => Source(txs))
  }

  /**
   * Tails the transaction log, emitting batches of appended transactions.
   * The transaction log is assumed to be an append-only data structure with each incoming stream element containing
   * all prior transactions and any new transactions committed since the previous element.
   * New transactions are selected by splitting the current transaction log at the size of the previous log.
   * Note that transaction batches are produced, rather that flattening, to enable distinct processing
   * by downstream stages for initial and ongoing updates.
   */
  val tail: Flow[List[Transaction], List[Transaction], Unit] = Flow[List[Transaction]]
    .scan((0, List.empty[Transaction])) { case ((after, _), txs) =>
      txs.splitAt(after) match { case (_, tail) =>
        (txs.length, tail)
      }}
    .drop(1)
    .map { case (_, tail) => tail }
    .filterNot(_.isEmpty)

  /**
   * Calculates the current mix state and resulting transfers over the transaction log.
   * The initial transaction batch is assumed to be the complete current log with subsequent batches representing
   * ongoing updates to the log.
   * The first element emitted is the current state along with any outstanding transfers.
   * Subsequent elements contain the updated state and any newly generated transfers.
   *
   * @param spec mix specification used to calculate mix transfers
   * @return
   */
  def mix(spec: MixSpecification): Flow[List[Transaction], (MixState, List[Transfer]), Unit] = {
    Flow[List[Transaction]].prefixAndTail(1).map { case (prefix, tail) =>
        val prefixState = (MixState(spec) ++ prefix(0))._1
        val prefixSource = Source.single((prefixState, prefixState.oustanding))
        val tailSource = tail.scan((prefixState, List.empty[Transfer]))(_._1 ++ _).drop(1)
        prefixSource ++ tailSource
    }.flatMapConcat(identity)
  }

  /**
   * Sends the latest mix state to an agent to enable external visibility, e.g. via an HTTP route.
   */
  def agent(agent: Agent[MixState]) = Sink
    .foreach[MixState](agent.send)
    .mapMaterializedValue(_ => agent)

  /**
   * Flattens transfer batches into a single stream of transfers.
   */
  val transfers = Flow[List[Transfer]]
    .flatMapConcat(trs => Source(trs))

  /**
   * Pass-through flow that delays emitting elements according to a Poisson distribution
   * with the specified mean arrival rate.
   */
  def poissonDelay[A](rate: Rate): Flow[A, A, _] = Flow[A].via(DelayStage2[A](Poisson.arrivalTimes(rate)))

}
