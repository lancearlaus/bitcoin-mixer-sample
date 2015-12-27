package mixer

import akka.event.Logging
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import http.HttpService
import spray.json._
import scala.collection.JavaConverters._
import scala.collection.mutable

trait JobcoinService extends HttpService with JsonProtocol {

  private lazy val log = Logging(system, classOf[MixerService])
  val transactions = mutable.ListBuffer[Transaction]()
  val addresses = mutable.Map[Address, AddressDetail]().withDefault(a => AddressDetail(a))
  def initial: Map[Address, Amount] = config.getObject("jobcoin.initial").asScala.map { case (k, v) =>
    (k -> BigDecimal(v.unwrapped.toString))
  }.toMap

  // Initialize with some balances to make Jobcoin useful
  initialize()

  abstract override def route: Route = {
    pathPrefix("api") {
      (post & path("transactions")) {
        entity(as[TransactionRequest]) { tx =>
          log.info(s"Received transaction request: $tx")
          complete(postTransaction(tx))
        }
      } ~
      (get & path("transactions")) {
        complete(transactions.toList)
      } ~
      (get & path("addresses" / Segment)) { address =>
        complete(addresses(address))
      } ~
      (post & path("addresses")) {
        entity(as[FundingRequest]) { addr =>
          complete(fundAddress(addr))
        }
      }
    }
  } ~ super.route

  def initialize() = {
    transactions.clear()
    addresses.clear()
    initial.foreach { r => fundAddress(FundingRequest.tupled(r)) }
  }

  def fundAddress(req: FundingRequest): (StatusCode, JsValue) = {
    val tx = Transaction(req)
    val to = addresses(req.address)

    transactions += tx
    addresses += (req.address -> (to.copy(balance = to.balance + req.amount, transactions = (to.transactions :+ tx))))

    (OK -> Map("status" -> "OK").toJson)
  }

  def postTransaction(req: TransactionRequest): (StatusCode, JsValue) = {
    val newBalance = addresses(req.fromAddress).balance - req.amount

    if (newBalance < 0) {
      (UnprocessableEntity -> Map("error" -> "Insufficient Funds").toJson)
    } else {
      val tx = Transaction(req)
      val from = addresses(req.fromAddress)
      val to = addresses(req.toAddress)

      transactions += tx
      addresses += (req.fromAddress -> (from.copy(balance = newBalance, transactions = (from.transactions :+ tx))))
      addresses += (req.toAddress -> (to.copy(balance = (to.balance + tx.amount), transactions = (to.transactions :+ tx))))

      (OK -> Map("status" -> "OK").toJson)
    }

  }

}
