package mixer

import java.time.Instant

import jobcoin.Jobcoin.Transaction
import jobcoin.JobcoinService.JsonProtocol._
import org.scalatest.{Matchers, WordSpec}
import spray.json._

class JsonProtocolSpec extends WordSpec with Matchers {

  "Protocol" should {

    "parse transaction" in {

      val json =
      """
        |{
        |    "timestamp": "2014-04-23T18:25:43.511Z",
        |    "fromAddress": "BobsAddress",
        |    "toAddress": "AlicesAddress",
        |    "amount": "30.1"
        |}
      """.stripMargin

      val expected = Transaction(Some("BobsAddress"), "AlicesAddress", 30.1, Instant.parse("2014-04-23T18:25:43.511Z"))

      json.parseJson.convertTo[Transaction] shouldBe expected

    }

    "parse transaction array" in {

      val json =
      """
        |[
        |  {
        |    "timestamp": "2014-04-23T18:25:43.511Z",
        |    "fromAddress": "BobsAddress",
        |    "toAddress": "AlicesAddress",
        |    "amount": "30.1"
        |  },
        |  {
        |    "timestamp": "2014-05-23T18:25:43.511Z",
        |    "fromAddress": "BobsAddress",
        |    "toAddress": "CharliesAddress",
        |    "amount": "40.17989357"
        |  }
        |]
      """.stripMargin

      val expected = List(
        Transaction(Some("BobsAddress"), "AlicesAddress", 30.1, Instant.parse("2014-04-23T18:25:43.511Z")),
        Transaction(Some("BobsAddress"), "CharliesAddress", 40.17989357, Instant.parse("2014-05-23T18:25:43.511Z"))
      )

      json.parseJson.convertTo[List[Transaction]] shouldBe expected

    }
  }

}
