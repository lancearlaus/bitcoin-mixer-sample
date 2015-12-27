import http.HttpServer
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

    // curl -H "Content-Type: application/json" --data '{ "out": [ "AliceOut1", "AliceOut2" ] }' http://localhost:8080/mixers
    // url -H "Content-Type: application/json" --data '{ "fromAddress": "Alice"; "toAddress": "mixIn0"; "amount": "10" }' http://localhost:8080/api/transactions

    println(
      s"""
         | Get started with the following URLs:
         | Stock Price Service:
         |   Yahoo with default SMA        : http://$host:$port/stock/price/daily/yhoo
         |   Yahoo 2 years w/ SMA(200)     : http://$host:$port/stock/price/daily/yhoo?period=2y&calculated=sma(200)
         |   Facebook 1 year raw history   : http://$host:$port/stock/price/daily/fb?period=1y&raw=true
         | Bitcoin Trades Service:
         |   Hourly OHLCV (Bitstamp USD)   : http://$host:$port/bitcoin/price/hourly/bitstamp/USD
         |   Daily  OHLCV (itBit USD)      : http://$host:$port/bitcoin/price/daily/itbit/USD
         |   Recent trades (itBit USD)     : http://$host:$port/bitcoin/trades/itbit/USD
         |   Trades raw response           : http://$host:$port/bitcoin/trades/bitstamp/USD?raw=true
         | Bitcoin Random Trades Websocket Service:
         |   Periodic random trades        : ws://$host:$port/bitcoin/random/trades
      """.stripMargin.trim)
  }

}




