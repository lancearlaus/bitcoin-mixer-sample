package mixer

import java.time.Instant
import java.time.format.DateTimeParseException

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

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

  // Modified form of default BigDecimal JSON format to support to/from String
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
  implicit val addressRequestFormat = jsonFormat2(FundingRequest)
  implicit val transactionFormat = jsonFormat4(Transaction.apply)
  implicit val transactionRequestFormat = jsonFormat3(TransactionRequest.apply)
  implicit val mixSpecificationFormat = jsonFormat4(MixSpecification)
  implicit val mixRequestFormat = jsonFormat1(MixRequest)
  implicit val accountFormat = jsonFormat3(Account.apply)
  implicit val mixBalancesFormat = jsonFormat(MixBalances.apply, "in", "mix", "out")
  implicit val mixStateFormat = jsonFormat(MixState.apply, "balances", "increment", "transfers", "transactions")

  implicit object AddressDetailWriter extends RootJsonWriter[AddressDetail] {
    override def write(detail: AddressDetail): JsValue = JsObject(
      "balance" -> detail.balance.toJson,
      "transactions" -> detail.transactions.toJson
    )
  }

}

object JsonProtocol extends JsonProtocol
