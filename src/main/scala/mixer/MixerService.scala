package mixer

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorSystem, Cancellable}
import akka.agent.Agent
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpRequest, RequestEntity, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.javadsl.RunnableGraph
import akka.stream.scaladsl._
import akka.stream.{ClosedShape, Materializer}
import http.HttpService
import jobcoin.JobcoinService
import spray.json._
import stream.rate.{DelayStage, Poisson, Rate}

import scala.collection.concurrent
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}

import jobcoin.Jobcoin._


trait MixerService extends HttpService with MixerService.JsonProtocol with JobcoinService.JsonProtocol {

  private implicit lazy val log = Logging(system, classOf[MixerService])

  // Set of currently active mixers
  val mixers = concurrent.TrieMap[Address, MixerReference]()
  val inCounter = new AtomicInteger()

  // Mixer configuration
  lazy val transactionsUri = Uri(config.getString("jobcoin.transactions.url"))
  lazy val pollingPeriod = FiniteDuration(config.getDuration("mixer.pollingPeriod").toMillis, TimeUnit.MILLISECONDS)
  lazy val mixAddress = config.getString("mixer.mix.address")
  lazy val mixIncrement = BigDecimal(config.getString("mixer.mix.increment"))
  lazy val poissonRate = Rate(1, FiniteDuration(config.getDuration("mixer.mix.transfer.mean.delay").toMillis, TimeUnit.MILLISECONDS))

  // The service route for mixer creation and querying
  abstract override def route: Route = {
    (get & path("mixers")) {
      complete(mixers.keys)
    } ~
    (get & path("mixers" / Segment)) { address =>
      complete(mixers.get(address).map(_.state()))
    } ~
    (post & path("mixers")) {
      entity(as[MixRequest]) { req =>
        // Create and run a new mixer for the given specification
        log.info(s"Received mix creation request: $req")
        complete(runMixer(MixSpecification(inAddress(), mixAddress, req.out, mixIncrement)))
      }
    }
  } ~ super.route


  // Generates a new mixer input address, monotonically increasing string for now
  def inAddress() = s"mixIn${inCounter.getAndIncrement()}"

  def runMixer(specification: MixSpecification): MixSpecification = {
    val runnable = mixerGraph(
      specification,
      transactions(transactionsUri, pollingPeriod),
      poissonDelay(poissonRate),
      postTransaction(transactionsUri))
    val mixerRef = runnable.run(materializer)

    log.info(s"Created new mixer (spec: $specification)")

    mixers += (specification.in -> mixerRef)

    specification
  }


  /**
   * Top level mixer runnable graph that is materialized for each requested mix and is expected to run until cancelled.
   *
   * The mixer consumes the transaction log and calculates and posts the necessary transactions to execute the mix
   * according to the provided specification.
   * These transactions are then streamed through a (randomized) delay before posting to improve anonymization.
   *
   * The graph materializes a reference to the mixer which contains two values:
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
    delay: Flow[TransactionRequest, TransactionRequest, _],
    post: Sink[TransactionRequest, _]
    )(implicit materializer: Materializer, system: ActorSystem): RunnableGraph[MixerReference] = {

    RunnableGraph.fromGraph(FlowGraph.create(
      transactions,
      tail,
      mix(specification),
      Unzip[Mixer, List[TransactionRequest]],
      agent(Agent(Mixer(specification))),
      transfers,
      delay,
      post
    )((cancellable, _, _, _, agent, _, _, _) => MixerReference(cancellable, agent)) {
      implicit b => (transactions, tail, mix, unzip, agent, transfers, delay, post) =>
        import FlowGraph.Implicits._

        // @formatter:off
        transactions ~> tail ~> mix ~> unzip.in
                                       unzip.out0 ~> agent
                                       unzip.out1 ~> transfers ~> delay ~> post
        // @formatter:on
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
    Source.tick(Duration.Zero, period, ()).map { tick =>
      log.debug(s"Retrieving transactions from $uri...")
      Http().singleRequest(HttpRequest(uri = uri)).flatMap { response =>
        response.status match {
          case OK => Unmarshal(response.entity).to[List[Transaction]]
          case s => {
            log.error(s"Failed to retrieve transactions (status: $s)")
            Future.successful(List.empty[Transaction])
          }
        }
      }
    }.flatMapConcat(txs => Source(txs))
  }

  /**
   * Tails the transaction log, emitting batches of appended transactions.
   *
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
   *
   * The initial transaction batch is assumed to be the complete current log with subsequent batches representing
   * ongoing updates to the log.
   * The first element emitted is the current state along with any outstanding transactions.
   * Subsequent elements contain the updated state and any newly generated transactions.
   *
   * @param spec mix specification used to calculate mix transactions
   * @return
   */
  def mix(spec: MixSpecification): Flow[List[Transaction], (Mixer, List[TransactionRequest]), Unit] = {
    Flow[List[Transaction]].prefixAndTail(1).map { case (prefix, tail) =>
      val prefixState = (Mixer(spec) ++ prefix(0))._1
      val prefixSource = Source.single((prefixState, prefixState.oustanding))
      val tailSource = tail.scan((prefixState, List.empty[TransactionRequest]))(_._1 ++ _).drop(1)
      prefixSource ++ tailSource
    }.flatMapConcat(identity)
  }

  /**
   * Sends the latest mix state to an agent to enable external visibility, e.g. via an HTTP route.
   */
  def agent(agent: Agent[Mixer]) = Sink
    .foreach[Mixer](agent.send)
    .mapMaterializedValue(_ => agent)

  /**
   * Flattens transfer batches into a single stream of transfers.
   */
  val transfers = Flow[List[TransactionRequest]]
    .flatMapConcat(trs => Source(trs))

  /**
   * Pass-through flow that delays emitting elements according to a Poisson distribution
   * with the specified mean arrival rate.
   */
  def poissonDelay[A](rate: Rate): Flow[A, A, _] = Flow[A]
    .via(DelayStage[A](Poisson.arrivalTimes(rate)))

  /**
   * Posts transactions requests to the given URI.
   */
  def postTransaction(uri: Uri)(implicit materializer: Materializer, system: ActorSystem): Sink[TransactionRequest, _] = {
    Sink.foreach[TransactionRequest] { req =>
      log.info(s"Posting transaction request (req: $req, uri: $uri)")
      for {
        requestEntity <- Marshal(req).to[RequestEntity]
        response <- Http().singleRequest(HttpRequest(method = POST, uri = uri, entity = requestEntity))
      } response.status match {
          case OK => log.info(s"Successfully posted transaction $req")
          case _ => for {
            errRes <- Unmarshal(response.entity).to[ErrorResponse]
            message <- errRes.error
          } log.error(s"Error posting transaction (req: $req, error: $message)")
      }
    }
  }


}


object MixerService {

  trait JsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
    import JobcoinService.JsonProtocol._

    implicit val mixSpecificationFormat = jsonFormat4(MixSpecification)
    implicit val mixRequestFormat = jsonFormat1(MixRequest)
    implicit val accountFormat = jsonFormat3(Account.apply)
    implicit val mixBalancesFormat = jsonFormat(MixerBalances.apply, "in", "mix", "out")
    implicit val mixStateFormat = jsonFormat(Mixer.apply, "balances", "increment", "transfers", "transactions")

  }

  object JsonProtocol extends JsonProtocol
}
