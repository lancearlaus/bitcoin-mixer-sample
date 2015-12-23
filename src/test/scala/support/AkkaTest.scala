package support

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor}

/**
 * Akka testing support class that manages the lifecycle of an ActorSystem.
 */
trait AkkaTest extends BeforeAndAfterAll { suite: Suite =>

  implicit var system: ActorSystem = _
  implicit var executor: ExecutionContextExecutor = _
  var config: Config = _
  var log: LoggingAdapter = _

  protected def systemName = "test-system"

  abstract override protected def beforeAll(): Unit = {
    super.beforeAll()
    system = ActorSystem(systemName)
    executor = system.dispatcher
    config = ConfigFactory.load()
    log = Logging(system, getClass)
  }

  abstract override protected def afterAll(): Unit = {
    log = null
    config = null
    executor = null
    system.terminate()
    Await.result(system.terminate(), Duration.Inf)
    super.afterAll()
  }

}
