import http.HttpServer
import jobcoin.JobcoinService
import mixer._

import scala.util.Success

object Main extends App
  with HttpServer
  with MixerService
  with JobcoinService
{
  bindingFuture.andThen { case Success(binding) =>
    val host = binding.localAddress.getHostName
    val port = binding.localAddress.getPort
    println(
      s"""
         | Get started with the following URLs:
         | Query the Jobcoin Service:
         |   Transaction list    : http://$host:$port/jobcoin/transactions
         |   Address list        : http://$host:$port/jobcoin/addresses
         | Query the Mixer Service:
         |   Mixer list          : http://$host:$port/mixers
         |   Mixer state         : http://$host:$port/mixers/<mixer input address>
         |               Example : http://$host:$port/mixers/mixIn0
         |
         | Create and fund a new mixer from the command line using curl:
         | curl -H "Content-Type: application/json" --data '{ "out": [ "AliceOut1", "AliceOut2" ] }' http://$host:$port/mixers
         | curl -H "Content-Type: application/json" --data '{ "fromAddress": "Alice", "toAddress": "mixIn0", "amount": "10" }' http://$host:$port/jobcoin/transactions
         |
         | Continue refreshing the following URL to observe balances change as the mixer runs
         | http://$host:$port/mixers/mixIn0
      """.stripMargin.trim)
  }

}




