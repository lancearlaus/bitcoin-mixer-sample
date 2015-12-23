import java.time.Instant

import akka.http.scaladsl.model.{StatusCode, HttpResponse}

import scala.math.Ordered._
import scala.util.Random
import scala.language.implicitConversions

package object mixer {

  type Address = String
  type Amount = BigDecimal


  case class MixSpecification(in: Address, mix: Address, out: Set[Address], increment: Amount)


  case class Transaction(fromAddress: Option[Address], toAddress: Address, amount: Amount, timestamp: Instant) {
    // Directional predicates
    def from(a: Address): Boolean = fromAddress.map(_ == a).getOrElse(false)
    def to(a: Address): Boolean = toAddress == a
    def to(as: Map[Address, _]): Boolean = as.contains(toAddress)
    def transfer(t: (Address, Address)): Boolean = from(t._1) && to(t._2)
    def transferOut(t: (Address, Map[Address, _])): Boolean = from(t._1) && to(t._2)
  }


  case class Transfer(fromAddress: Address, toAddress: Address, amount: Amount) {
    require(fromAddress != toAddress)
    require(amount > 0)
    // Directional predicates
    def from(a: Address): Boolean = fromAddress == a
    def to(a: Address): Boolean = toAddress == a
  }
  object Transfer {
    def apply(fromTo: (Address, Address))(amount: Amount): Transfer = new Transfer(fromTo._1, fromTo._2, amount)
    def apply(tx: Transaction): Option[Transfer] = tx.fromAddress.map(_ => Transfer(tx.fromAddress.get, tx.toAddress, tx.amount))
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


  case class MixBalances private (in: Account, mix: Account, out: Map[Address, Account]) {

    // Total balance for this mix, which will equal the cumulative deposits to "in" address
    lazy val totalBalance = in.balance + mix.balance + out.values.foldLeft(BigDecimal(0))(_ + _.balance)

    // Apply mix-specific transactions to update mix balances
    def +(tx: Transaction): MixBalances = {
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
  object MixBalances {
    def apply(spec: MixSpecification): MixBalances =
      new MixBalances(Account(spec.in), Account(spec.mix), Map(spec.out.map(a => (a, Account(a))).toSeq: _*))
  }

  case class MixState private (balances: MixBalances, increment: Amount, transfers: List[Transfer], transactions: List[Transaction]) {

    // Calculate outstanding transfers
    // Note: Using get below is safe since only transfers are added to the transactions list
    lazy val oustanding: List[Transfer] = transfers.diff(transactions.map(tx => Transfer(tx).get))

    // Apply transaction to mix state, potentially generating a new state
    def +(tx: Transaction): (MixState, List[Transfer]) = {

      val updated = balances + tx

      if (updated == balances) {
        // No change to the mix
        (this, List.empty)

      } else if (tx to balances.in) {
        // Mix amount increased, generate additional transfers
        val increase = updated.totalBalance - balances.totalBalance
        val remainder = increase.remainder(increment)
        val batch = increase - remainder
        var newTransfers = List.empty[Transfer]

        // Transfer (in -> mix)
        newTransfers = newTransfers :+ Transfer(updated.in -> updated.mix)(batch)

        // Transfer(s) (mix -> out)
        val outAccounts = updated.out.values.toIndexedSeq
        (0 until (batch / increment).intValue) foreach { _ =>
          // Select random out account
          val out = outAccounts(Random.nextInt(outAccounts.size))
          newTransfers = newTransfers :+ Transfer(updated.mix -> out)(increment)
        }

        (copy(balances = updated, transfers = transfers ++ newTransfers), newTransfers)

      } else {
        // Mix account balances changed, transaction must be a mix transfer
        (copy(balances = updated, transactions = transactions :+ tx), List.empty)
      }
    }

    def ++(txs: List[Transaction]): (MixState, List[Transfer]) = txs.foldLeft((this, List.empty[Transfer])) {
      case ((mix, transfers), tx) => (mix + tx) match {
        case (newMix, newTransfers) => (newMix, transfers ++ newTransfers)
      }
    }

  }
  object MixState {
    def apply(spec: MixSpecification): MixState = new MixState(MixBalances(spec), spec.increment, List.empty, List.empty)
  }

//  case class TransferState(state: MixState, successful: Seq[Transfer], failed: Seq[(Transfer, StatusCode)], inProgress: Seq[Transfer], queued: Seq[Transfer]) {
//
//    def +(newState: MixState): TransferState = {
//      // Queue any new transfers
//      val delta = newState.transfers.diff(state.transfers)
//      val queued = this.queued ++ delta
//    }
//
//  }

  // Accumulates transfers for an account and releases them when funds become available
  // Transfers are eagerly released
  case class AccountTransfers private (account: Account, available: Amount, pending: Seq[Transfer]) {

    def +(tx: Transaction): (AccountTransfers, Seq[Transfer]) = {
      val updated = account + tx

      if (updated != account) {
        if (tx to account) {
          // Funds deposited, attempt to release transfer(s)
          val (available, pending, released) = this.pending
            .foldLeft((this.available + tx.amount, this.pending, Seq.empty[Transfer])) {
              case ((available, pending, released), transfer) =>
                if (transfer.amount <= available) (available - transfer.amount, pending, released :+ transfer)
                else (available, pending :+ transfer, released)
            }
          (copy(account = updated, available = available, pending = pending), released)
        } else {
          (copy(account = updated), Seq.empty)
        }
      } else {
        // No update to the account
        (this, Seq.empty)
      }
    }

    def +(transfer: Transfer): (AccountTransfers, Seq[Transfer]) = {
      if (transfer from account) {
        // Release new transfer if funds available (eager release)
        if (transfer.amount <= available) {
          (copy(available = available - transfer.amount), Seq(transfer))
        } else {
          (copy(pending = pending :+ transfer), Seq.empty)
        }
      } else {
        // Transfer not from account
        (this, Seq.empty)
      }
    }

  }
  object AccountTransfers {
    def apply(address: Address): AccountTransfers = new AccountTransfers(Account(address), 0, Seq.empty)
  }

}
