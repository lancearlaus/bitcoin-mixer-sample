package mixer

import java.time.Instant

import akka.actor.Cancellable
import akka.agent.Agent
import jobcoin.Jobcoin._

import scala.language.implicitConversions
import scala.math.Ordered._
import scala.util.Random


case class MixRequest(out: Set[Address])

case class MixSpecification(in: Address, mix: Address, out: Set[Address], increment: Amount)

case class MixerReference(cancellable: Cancellable, state: Agent[Mixer]) {
  def cancel() = cancellable.cancel()
}


case class Account(address: Address, balance: Amount = 0, last: Instant = Instant.MIN) {
  def +(tx: Transaction): Account =
    if (tx.timestamp <= last) this
    else if (tx transfer (address -> address)) this
    else if (tx from address) copy(balance = balance - tx.amount, last = tx.timestamp)
    else if (tx to address) copy(balance = balance + tx.amount, last = tx.timestamp)
    else this
}
object Account {
  // Implicit conversions for code readability
  implicit def accountToAddress(account: Account): Address = account.address
  implicit def accountTupleToAddressTuple(t: (Account, Account)): (Address, Address) = (t._1.address, t._2.address)
  implicit def accountMapTupleToAddressMapTuple(t: (Account, Map[Address, _])): (Address, Map[Address, _]) = (t._1.address, t._2)
}

/**
 * Transaction aggregate that tabulates mix-specific account balances.
 *
 * @param in mixer input account
 * @param mix mixer "house" account
 * @param out mixer output addresses
 */
case class MixerBalances private[mixer] (in: Account, mix: Account, out: Map[Address, Account]) {

  // Total balance for this mix, which will equal the cumulative deposits to "in" address
  lazy val totalBalance = in.balance + mix.balance + out.values.foldLeft(BigDecimal(0))(_ + _.balance)

  // Apply mix-specific transactions to update mix balances
  def +(tx: Transaction): MixerBalances = {
    if (tx to in) {
      copy(in = in + tx)
    } else if (tx transfer (in -> mix)) {
      copy(in = in + tx, mix = mix + tx)
    } else if (tx transferOut (mix -> out)) {
      copy(mix = mix + tx, out = out + (tx.toAddress -> (out(tx.toAddress) + tx)))
    } else {
      this
    }
  }

}
object MixerBalances {
  def apply(spec: MixSpecification): MixerBalances =
    new MixerBalances(Account(spec.in), Account(spec.mix), Map(spec.out.map(a => (a, Account(a))).toSeq: _*))
}

/**
 * Transaction aggregate that tabulates mixer state, calculating the necessary transactions required to run the
 * requested mix.
 *
 * Any additional deposit to the mixer input account results in the generation of transaction requests.
 * The mixer maintains the list of transfers (transaction requests) and the list of completed mix transactions, as =
 * observed from the transaction log.
 *
 * Note that transfers should equal transactions once the mix has completed by incrementally transferring all input
 * funds through the mix address and on to the out accounts.
 *
 * @param balances mix-specific account balances
 * @param increment incremental transfer amount
 * @param transfers transaction requests required to execute the mix
 * @param transactions completed mix transactions
 */
case class Mixer private[mixer] (balances: MixerBalances, increment: Amount, transfers: List[TransactionRequest], transactions: List[Transaction]) {

  // Calculate outstanding transfers
  // Note: Using get below is safe since only transfers are added to the transactions list
  lazy val oustanding: List[TransactionRequest] = transfers.diff(transactions.map(tx => TransactionRequest(tx).get))

  // Apply transaction to mix state, potentially generating a new state
  /**
   * Apply a transaction to (potentially) generate a new state and a set of additional transaction requests.
   *
   * A list of transfers will be generated if and only if new funds have been deposited to the input address.
   */
  def +(tx: Transaction): (Mixer, List[TransactionRequest]) = {

    val updated = balances + tx

    if (updated == balances) {
      // No change to the mix
      (this, List.empty)

    } else if (tx to balances.in) {
      // Mix amount increased, generate additional transfers
      val increase = updated.totalBalance - balances.totalBalance
      val remainder = increase.remainder(increment)
      val batch = increase - remainder
      var newTransfers = List.empty[TransactionRequest]

      // Transfer (in -> mix)
      newTransfers = newTransfers :+ TransactionRequest(updated.in -> updated.mix)(batch)

      // Transfer(s) (mix -> out)
      val outAccounts = updated.out.values.toIndexedSeq
      (0 until (batch / increment).intValue) foreach { _ =>
        // Select random out account
        val out = outAccounts(Random.nextInt(outAccounts.size))
        newTransfers = newTransfers :+ TransactionRequest(updated.mix -> out)(increment)
      }

      (copy(balances = updated, transfers = transfers ++ newTransfers), newTransfers)

    } else {
      // Mix account balances changed, transaction must be a mix transfer
      (copy(balances = updated, transactions = transactions :+ tx), List.empty)
    }
  }

  def ++(txs: List[Transaction]): (Mixer, List[TransactionRequest]) = txs.foldLeft((this, List.empty[TransactionRequest])) {
    case ((mix, transfers), tx) => (mix + tx) match {
      case (newMix, newTransfers) => (newMix, transfers ++ newTransfers)
    }
  }

}
object Mixer {
  def apply(spec: MixSpecification): Mixer = new Mixer(MixerBalances(spec), spec.increment, List.empty, List.empty)
}
