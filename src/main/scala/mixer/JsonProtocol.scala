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

  implicit val transactionFormat = jsonFormat4(Transaction)
  implicit val transferFormat = jsonFormat3(Transfer.apply)

}

object JsonProtocol extends JsonProtocol
