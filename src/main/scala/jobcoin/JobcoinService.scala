package jobcoin

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}

import akka.event.Logging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import http.HttpService
import jobcoin.Jobcoin._
import mixer.MixerService
import spray.json._

import scala.collection.JavaConverters._

trait JobcoinService extends HttpService with JobcoinService.JsonProtocol {

  private lazy val log = Logging(system, classOf[MixerService])

  val transactions = new ConcurrentLinkedQueue[Transaction]()
//  val transactions = new mutable.ListBuffer[Transaction]
  val addresses = new ConcurrentHashMap[Address, AddressDetail]().asScala.withDefault(a => AddressDetail(a))
  lazy val initial: Map[Address, Amount] = config.getObject("jobcoin.initial").asScala.map { case (k, v) =>
    (k -> BigDecimal(v.unwrapped.toString))
  }.toMap

  // Fund some addresses to make Jobcoin useful
  initialize()

  abstract override def route: Route = {
    pathPrefix("jobcoin") {
      (post & path("transactions")) {
        entity(as[TransactionRequest]) { tx =>
          log.info(s"Received transaction request: $tx")
          complete(postTransaction(tx))
        }
      } ~
      (get & path("transactions")) {
        complete(transactions.asScala)
      } ~
      (get & path("addresses")) {
        complete(addresses.keys)
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
    log.info("Initializing Jobcoin addresses")
    transactions.clear()
    addresses.clear()
    initial.foreach { r => fundAddress(FundingRequest.tupled(r)) }
  }

  def fundAddress(req: FundingRequest): (StatusCode, JsValue) = {
    val tx = Transaction(req)
    val to = addresses(req.address)

    transactions.add(tx)
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

      transactions.add(tx)
      addresses += (req.fromAddress -> (from.copy(balance = newBalance, transactions = (from.transactions :+ tx))))
      addresses += (req.toAddress -> (to.copy(balance = (to.balance + tx.amount), transactions = (to.transactions :+ tx))))

      (OK -> Map("status" -> "OK").toJson)
    }

  }

}

object JobcoinService {

  trait JsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {

    // Instant serialization
    implicit object InstantJsonFormat extends JsonFormat[Instant] {
      override def write(instant: Instant) = JsString(instant.toString)
      override def read(json: JsValue) = json match {
        case JsString(s) => try {
          Instant.parse(s)
        } catch {
          case e: DateTimeParseException => deserializationError("invalid Instant string", e)
        }
        case _ => deserializationError("Instant string expected")
      }
    }

    // Modified form of default BigDecimal JSON format to support to/from JsString instead of JsNumber
    implicit object BigDecimalStringJsonFormat extends JsonFormat[BigDecimal] {
      def write(x: BigDecimal) = {
        require(x ne null)
        JsString(x.toString)
      }
      def read(value: JsValue) = value match {
        case JsString(s) => BigDecimal(s)
        case x => deserializationError("Expected BigDecimal as JsString, but got " + x)
      }
    }

    implicit val successResponseFormat = jsonFormat1(SuccessResponse)
    implicit val errorResponseFormat = jsonFormat1(ErrorResponse)
    implicit val transactionRequestFormat = jsonFormat3(TransactionRequest.apply)
    implicit val transactionFormat = jsonFormat4(Transaction.apply)
    implicit val fundingRequestFormat = jsonFormat2(FundingRequest)

    implicit object AddressDetailWriter extends RootJsonWriter[AddressDetail] {
      override def write(detail: AddressDetail): JsValue = JsObject(
        "balance" -> detail.balance.toJson,
        "transactions" -> detail.transactions.toJson
      )
    }

  }

  object JsonProtocol extends JsonProtocol
}