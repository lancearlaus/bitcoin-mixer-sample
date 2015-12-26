package stream.rate

import akka.actor.{ActorSystem, Cancellable}
import akka.stream.{Attributes, Outlet, Inlet, FlowShape}
import akka.stream.stage._

import scala.concurrent.duration.FiniteDuration

/**
 * Emits elements with intervals specified by a FiniteDuration iterator.
 *
 * The duration between elements is determined by the supplied iterator.
 * The purpose is to enable variable timing between elements to simulate real-world events that
 * may follow a statistical distribution (Poisson, for example).
 *
 * The scheduling algorithm is intentionally simple and does not attempt to perform adjustments to
 * match actual with desired element timing/rate.
 * Scheduling is done using the Akka scheduler, so any caveats about timing regarding that scheduler, notably
 * its granularity, are applicable here. The actual emission rate is likely to be different (less) than desired.
 *
 * @param delays desired inter-event timings
 */
case class DelayStage[A](delays: Iterator[FiniteDuration]) extends GraphStage[FlowShape[A, A]] {

  val in = Inlet[A]("DelayStage.in")
  val out = Outlet[A]("DelayStage.out")
  val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) {

      var queued = Option.empty[A]
      var ticked = false

      override def preStart() = scheduleTick()

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          if (ticked) {
            push(out, grab(in))
            scheduleTick()
          } else {
            queued = Some(grab(in))
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull() = pull(in)
      })

      override protected def onTimer(timerKey: Any): Unit = {
        if (queued.isDefined) {
          push(out, queued.get)
          queued = None
          scheduleTick()
        } else {
          ticked = true
        }
      }

      private def scheduleTick(): Unit = {
        ticked = false
        if (delays.hasNext) {
          scheduleOnce("DelayStageTimer", delays.next())
        } else {
          completeStage()
        }
      }
    }
}
