package jbok.core.api

import io.circe.generic.extras.ConfiguredJsonCodec
import jbok.core.models.{Account, Address, SignedTransaction}
import scodec.bits.ByteVector
import jbok.codec.json.implicits._
import jbok.network.rpc.PathName

@ConfiguredJsonCodec
final case class HistoryTransaction(
    txHash: ByteVector,
    nonce: BigInt,
    fromAddress: Address,
    toAddress: Address,
    value: BigInt,
    payload: String,
    v: BigInt,
    r: BigInt,
    s: BigInt,
    gasUsed: BigInt,
    gasPrice: BigInt,
    blockNumber: BigInt,
    blockHash: ByteVector,
    location: Int
)

@PathName("account")
trait AccountAPI[F[_]] {
  def getAccount(address: Address, tag: BlockTag = BlockTag.latest): F[Account]

  def getCode(address: Address, tag: BlockTag = BlockTag.latest): F[ByteVector]

  def getBalance(address: Address, tag: BlockTag = BlockTag.latest): F[BigInt]

  def getStorageAt(address: Address, position: BigInt, tag: BlockTag = BlockTag.latest): F[ByteVector]

  def getTransactions(address: Address, page: Int, size: Int): F[List[HistoryTransaction]]

  def getTransactionsByNumber(number: Int): F[List[HistoryTransaction]]

//  def getTokenTransactions(address: Address, contract: Option[Address]): F[List[SignedTransaction]]

  def getPendingTxs(address: Address): F[List[SignedTransaction]]

  def getEstimatedNonce(address: Address): F[BigInt]
}
