# Jobcoin Mixer Demo

This demo project uses Akka HTTP and related technologies to create an anonymizing mixer for a fictional bitcoin-like currency named Jobcoin.
 
## Getting Started

```bash
  git clone https://github.com/lancearlaus/jobcoin-mixer.git
  cd jobcoin-mixer
  sbt run
```

which will produce the following instructions:

```bash
Get started with the following URLs:
 Query the Jobcoin Service:
   Transaction list    : http://localhost:8080/jobcoin/transactions
   Address list        : http://localhost:8080/jobcoin/addresses
 Query the Mixer Service:
   Mixer list          : http://localhost:8080/mixers
   Mixer state         : http://localhost:8080/mixers/<mixer input address>
               Example : http://localhost:8080/mixers/mixIn0

 Create and fund a new mixer from the command line using curl:
 curl -H "Content-Type: application/json" --data '{ "out": [ "AliceOut1", "AliceOut2" ] }' http://localhost:8080/mixers
 curl -H "Content-Type: application/json" --data '{ "fromAddress": "Alice", "toAddress": "mixIn0", "amount": "10" }' http://localhost:8080/jobcoin/transactions
```

## Implementation Highlights

* The mixer implementation is an event sourced inspired design, treating the Jobcoing transaction list as an append-only log over which
   a set of aggregates are calculated to generate the current mix state and any necessary transactions
* Each mixer instance is implemented using a materialized flow with the following structure
  ```scala
        transactions ~> tail ~> mix ~> unzip.in
                                       unzip.out0 ~> agent
                                       unzip.out1 ~> transfers ~> delay ~> post
  ```
    * transactions - periodically polls the Jobcoin transactions endpoint, emitting the entire log each time
    * tail - tails the stream of transactions, emitting only new transactions since the previous element
    * mix - calculates the mixer state and new transactions required to move funds through the mix
    * agent - updates an Akka Agent with the latest mix state to enable external querying (via HTTP, for example)
    * transfers - flattens batches of transfers (transaction requests) into a stream of requests
    * delay - randomizes transaction timing by applying a random (Poisson) delay before emitting downstream
    * post - posts the generated mix transactions to the Jobcoin transaction endpoint
* Each mixer is a self-running flow that will run until cancelled
* Services are implemented using the stackable trait pattern to easily allow multiple services to be implemented by a single server 

## Navigating the Code

* Main.scala - main class run using `sbt run`. Server that exposes both `JobcoinService` and `MixerService`
* mixer/Mixer.scala - Mixer model implementation that represents and calculates a mix
* mixer/MixerService.scala - Akka HTTP mixer service implementation contains necessary Akka Streams flows
* jobcoin/Jobcoin.scala - Jobcoin model implementation of transactions, etc.
* jobcoin/JobcoinService.scala - Akka HTTP Jobcoin service implementation contains necessary Akka Streams flows
* resources/reference.conf - application configuration (timing, urls, etc.)
* http/* - base traits to ease Akka HTTP service creation
* stream.rate - custom stage implementation for Poisson delay stage