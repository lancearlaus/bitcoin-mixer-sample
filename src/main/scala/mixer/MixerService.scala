package mixer

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.event.Logging
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import http.HttpService
import mixer.FlowGraphs._
import stream.rate.Rate

import scala.collection.concurrent
import scala.concurrent.duration.FiniteDuration

trait MixerService extends HttpService with JsonProtocol {

  private implicit lazy val log = Logging(system, classOf[MixerService])
  val mixers = concurrent.TrieMap[Address, MixerReference]()
  val inCounter = new AtomicInteger()

  lazy val transactionsUri = Uri(config.getString("jobcoin.transactions.url"))
  lazy val pollingPeriod = FiniteDuration(config.getDuration("mixer.pollingPeriod").toMillis, TimeUnit.MILLISECONDS)
  lazy val mixAddress = config.getString("mixer.mix.address")
  lazy val mixIncrement = BigDecimal(config.getString("mixer.mix.increment"))
  lazy val poissonRate = Rate(1, FiniteDuration(config.getDuration("mixer.mix.transfer.mean.delay").toMillis, TimeUnit.MILLISECONDS))

  def inAddress() = s"mixIn${inCounter.getAndIncrement()}"

  abstract override def route: Route = {
    (get & path("mixers")) {
      complete(mixers.keys)
    } ~
    (get & path("mixers" / Segment)) { address =>
      complete(mixers.get(address).map(_.state()))
    } ~
    (post & path("mixers")) {
      entity(as[MixRequest]) { req =>
        log.info(s"Received mix creation request: $req")
        // Create and run a new mixer for the given specification
        complete(runMixer(MixSpecification(inAddress(), mixAddress, req.out, mixIncrement)))
      }
    }
  } ~ super.route


  def runMixer(specification: MixSpecification): MixSpecification = {
    val runnable = mixerGraph(
      specification,
      transactions(transactionsUri, pollingPeriod),
      poissonDelay(poissonRate),
      postTransaction(transactionsUri))
    val mixerRef = runnable.run(materializer)

    mixers += (specification.in -> mixerRef)

    specification
  }

}
