package stream.rate

import scala.concurrent.duration.FiniteDuration

// Represents a quantity per duration of time
case class Rate(quantity: Double, duration: FiniteDuration) {
  require(duration.length > 0, "duration must be positive")

  // Duration to emit the given quantity
  def durationFor(quantity: Double): FiniteDuration = {
    require(quantity > 0 && !quantity.isInfinite)
    (duration * (quantity / this.quantity)).asInstanceOf[FiniteDuration]
  }

  // Quantity emitted during the specified duration
  def quantityFor(duration: FiniteDuration): Double = {
    quantity * (duration / this.duration)
  }
}
