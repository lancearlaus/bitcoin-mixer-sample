package jobcoin

import java.time.Instant

object Jobcoin {

  type Address = String
  type Amount = BigDecimal

  sealed trait ServiceResponse
  case class SuccessResponse(status: String = "OK") extends ServiceResponse
  case class ErrorResponse(error: String) extends ServiceResponse

  case class TransactionRequest(fromAddress: Address, toAddress: Address, amount: Amount) {
    require(fromAddress != toAddress)
    require(amount > 0)
    // Directional predicates
    def from(a: Address): Boolean = fromAddress == a
    def to(a: Address): Boolean = toAddress == a
  }
  object TransactionRequest {
    def apply(fromTo: (Address, Address))(amount: Amount): TransactionRequest = new TransactionRequest(fromTo._1, fromTo._2, amount)
    def apply(tx: Transaction): Option[TransactionRequest] = tx.fromAddress.map(_ => TransactionRequest(tx.fromAddress.get, tx.toAddress, tx.amount))
  }


  case class Transaction(fromAddress: Option[Address], toAddress: Address, amount: Amount, timestamp: Instant) {
    // Directional predicates
    def from(a: Address): Boolean = fromAddress.map(_ == a).getOrElse(false)
    def to(a: Address): Boolean = toAddress == a
    def to(as: Map[Address, _]): Boolean = as.contains(toAddress)
    def transfer(t: (Address, Address)): Boolean = from(t._1) && to(t._2)
    def transferOut(t: (Address, Map[Address, _])): Boolean = from(t._1) && to(t._2)
  }
  object Transaction {
    def apply(req: FundingRequest): Transaction =
      new Transaction(None, req.address, req.amount, Instant.now())
    def apply(req: TransactionRequest): Transaction =
      new Transaction(Some(req.fromAddress), req.toAddress, req.amount, Instant.now())
  }


  case class FundingRequest(address: Address, amount: Amount)

  case class AddressDetail (address: Address, balance: Amount = 0, transactions: List[Transaction] = List.empty) {
    def +(tx: Transaction): AddressDetail =
      if (tx transfer (address -> address)) this
      else if (tx from address) copy(balance = balance - tx.amount, transactions = transactions :+ tx)
      else if (tx to address) copy(balance = balance + tx.amount, transactions = transactions :+ tx)
      else this
  }

}
