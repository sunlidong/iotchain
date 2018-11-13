package jbok.core.ledger

import cats.Foldable
import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import jbok.core.config.Configs.BlockChainConfig
import jbok.core.consensus.{Consensus, ConsensusResult}
import jbok.core.models.UInt256._
import jbok.core.models._
import jbok.core.validators.{BlockValidator, CommonHeaderValidator, TransactionValidator}
import jbok.evm._
import scodec.bits.ByteVector

case class BlockResult[F[_]](worldState: WorldState[F], gasUsed: BigInt = 0, receipts: List[Receipt] = Nil)
case class TxResult[F[_]](
    world: WorldState[F],
    gasUsed: BigInt,
    logs: List[TxLogEntry],
    vmReturnData: ByteVector,
    vmError: Option[ProgramError]
)

sealed trait BlockImportResult
object BlockImportResult {
  case class Succeed(imported: List[Block], totalDifficulties: List[BigInt]) extends BlockImportResult
  case class Failed(error: Throwable)                                        extends BlockImportResult
  case object Pooled                                                         extends BlockImportResult
}

class BlockExecutor[F[_]](
    val config: BlockChainConfig,
    val consensus: Consensus[F],
    val commonHeaderValidator: CommonHeaderValidator[F],
    val commonBlockValidator: BlockValidator[F],
    val txValidator: TransactionValidator[F],
    val vm: VM
)(implicit F: ConcurrentEffect[F]) {
  private[this] val log = org.log4s.getLogger("BlockExecutor")

  val history   = consensus.history
  val blockPool = consensus.blockPool

  def simulateTransaction(stx: SignedTransaction, blockHeader: BlockHeader): F[TxResult[F]] = {
    val stateRoot = blockHeader.stateRoot

    val gasPrice      = UInt256(stx.gasPrice)
    val gasLimit      = stx.gasLimit
    val vmConfig      = EvmConfig.forBlock(blockHeader.number, config)
    val senderAddress = getSenderAddress(stx, blockHeader.number)

    for {
      _       <- txValidator.validateSimulateTx(stx)
      world1  <- history.getWorldState(config.accountStartNonce, Some(stateRoot))
      world2  <- updateSenderAccountBeforeExecution(senderAddress, stx, world1)
      context <- prepareProgramContext(stx, blockHeader, world2, vmConfig)
      result  <- runVM(stx, context, vmConfig)
      totalGasToRefund = calcTotalGasToRefund(stx, result)
    } yield {
      log.info(
        s"""SimulateTransaction(${stx.hash.toHex.take(7)}) execution end with ${result.error} error.
           |result.returnData: ${result.returnData.toHex}
           |gas refund: ${totalGasToRefund}""".stripMargin
      )

      TxResult(result.world, gasLimit - totalGasToRefund, result.logs, result.returnData, result.error)
    }
  }

  def binarySearchGasEstimation(stx: SignedTransaction, blockHeader: BlockHeader): F[BigInt] = {
    val lowLimit  = EvmConfig.forBlock(blockHeader.number, config).feeSchedule.G_transaction
    val highLimit = stx.gasLimit

    if (highLimit < lowLimit)
      F.pure(highLimit)
    else {
      binaryChop(lowLimit, highLimit)(gasLimit =>
        simulateTransaction(stx.copy(gasLimit = gasLimit), blockHeader).map(_.vmError))
    }
  }

  // FIXME
  def importBlocks(blocks: List[Block], imported: List[Block] = Nil): F[List[Block]] =
    blocks match {
      case Nil =>
        F.pure(imported)

      case block :: tail =>
        importBlock(block).flatMap {
          case BlockImportResult.Succeed(_, _) =>
            importBlocks(tail, block :: imported)

          case _ =>
            importBlocks(tail, imported)
        }
    }

  def importBlock(block: Block): F[BlockImportResult] =
    for {
      parent          <- history.getBestBlock
      currentTd       <- history.getTotalDifficultyByHash(parent.header.hash).map(_.get)
      consensusResult <- consensus.run(parent, block)
      importResult <- consensusResult match {
        case ConsensusResult.BlockInvalid(e) =>
          log.info(s"import block failed")
          F.pure(BlockImportResult.Failed(e))
        case ConsensusResult.ImportToTop =>
          log.info(s"should import to top")
          importBlockToTop(block, parent.header.number, currentTd)
        case ConsensusResult.Pooled =>
          log.info(s"should import to pool")
          blockPool.addBlock(block, parent.header.number).map(_ => BlockImportResult.Pooled)
      }
    } yield importResult

  def importBlockToTop(block: Block, bestBlockNumber: BigInt, currentTd: BigInt): F[BlockImportResult] =
    for {
      topBlockHash <- blockPool.addBlock(block, bestBlockNumber).map(_.get.hash)
      topBlocks    <- blockPool.getBranch(topBlockHash, dequeue = true)
      _ = log.info(s"execute top blocks: ${topBlocks.map(_.tag)}")
      result <- executeBlocks(topBlocks, currentTd).attempt.map {
        case Left(e) =>
          BlockImportResult.Failed(e)

        case Right(importedBlocks) =>
          val totalDifficulties = importedBlocks
            .foldLeft(List(currentTd)) { (tds, b) =>
              (tds.head + b.header.difficulty) :: tds
            }
            .reverse
            .tail

          BlockImportResult.Succeed(importedBlocks, totalDifficulties)
      }
    } yield result

  def payReward(block: Block, world: WorldState[F]): F[WorldState[F]] = {
    def getAccountToPay(address: Address, ws: WorldState[F]): F[Account] =
      ws.getAccountOpt(address)
        .getOrElse(Account.empty(config.accountStartNonce))

    val minerAddress = Address(block.header.beneficiary)

    for {
      minerAccount <- getAccountToPay(minerAddress, world)
      minerReward  <- consensus.calcBlockMinerReward(block.header.number, block.body.uncleNodesList.size)
      afterMinerReward = world.putAccount(minerAddress, minerAccount.increaseBalance(UInt256(minerReward)))
      _                = log.debug(s"block(${block.header.number}) reward of $minerReward paid to miner $minerAddress")
      world <- Foldable[List].foldLeftM(block.body.uncleNodesList, afterMinerReward) { (ws, ommer) =>
        val ommerAddress = Address(ommer.beneficiary)
        for {
          account     <- getAccountToPay(ommerAddress, ws)
          ommerReward <- consensus.calcOmmerMinerReward(block.header.number, ommer.number)
          _ = log.debug(s"block(${block.header.number}) reward of $ommerReward paid to ommer $ommerAddress")
        } yield ws.putAccount(ommerAddress, account.increaseBalance(UInt256(ommerReward)))
      }
    } yield world
  }

  def preExecuteValidate(block: Block): F[Unit] =
    for {
      parentHeader <- commonHeaderValidator.validate(block.header)
      _            <- commonBlockValidator.validateHeaderAndBody(block)
      _            <- consensus.semanticValidate(parentHeader, block)
    } yield ()

  def executeBlock(block: Block, alreadyValidated: Boolean = false): F[List[Receipt]] =
    for {
      (result, _) <- executeBlockTransactions(block)
      _ = log.debug(s"execute block result ${result.worldState.stateRootHash}")
      worldToPersist <- payReward(block, result.worldState)
      _ = log.debug(s"to persist ${worldToPersist.stateRootHash}")
      worldPersisted <- worldToPersist.persisted
      _ = log.debug(s"persisted ${worldPersisted.stateRootHash}")
      _ <- commonBlockValidator.postExecuteValidate(
        block.header,
        worldPersisted.stateRootHash,
        result.receipts,
        result.gasUsed
      )
    } yield result.receipts

  def executeBlockTransactions(block: Block,
                               shortCircuit: Boolean = true): F[(BlockResult[F], List[SignedTransaction])] =
    for {
      parentStateRoot <- history.getBlockHeaderByHash(block.header.parentHash).map(_.map(_.stateRoot))
      world <- history.getWorldState(
        config.accountStartNonce,
        parentStateRoot,
        false
      )
      result <- executeTransactions(block.body.transactionList, block.header, world, shortCircuit = shortCircuit)
    } yield result

  def executeBlocks(blocks: List[Block], parentTd: BigInt): F[List[Block]] =
    blocks match {
      case block :: tail =>
        executeBlock(block, alreadyValidated = true).attempt.flatMap {
          case Right(receipts) =>
            log.info(s"${block.tag} execution succeed")
            val td = parentTd + block.header.difficulty
            for {
              _ <- history.putBlockAndReceipts(block, receipts, td, asBestBlock = true)
              _ = log.info(s"${block.tag} saved as the best block")
              executedBlocks <- executeBlocks(tail, td)
            } yield block :: executedBlocks

          case Left(error) =>
            log.error(error)(s"${block.tag} execution failed")
            F.raiseError(error)
        }

      case Nil =>
        F.pure(Nil)
    }

  def executeTransactions(
      stxs: List[SignedTransaction],
      header: BlockHeader,
      world: WorldState[F],
      accGas: BigInt = 0,
      accReceipts: List[Receipt] = Nil,
      executed: List[SignedTransaction] = Nil,
      shortCircuit: Boolean = true
  ): F[(BlockResult[F], List[SignedTransaction])] = stxs match {
    case Nil =>
      F.pure(BlockResult(worldState = world, gasUsed = accGas, receipts = accReceipts), executed)

    case stx :: tail =>
      val senderAddress = getSenderAddress(stx, header.number)
      log.info(s"execute tx from ${senderAddress} to ${stx.receivingAddress}")
      for {
        (senderAccount, worldForTx) <- world
          .getAccountOpt(senderAddress)
          .map(a => (a, world))
          .getOrElse((Account.empty(UInt256.Zero), world.putAccount(senderAddress, Account.empty(UInt256.Zero))))

        upfrontCost = calculateUpfrontCost(stx)
        _ = log.info(
          s"stx: ${stx}, senderAccount: ${senderAccount}, header: ${header}, upfrontCost: ${upfrontCost}, accGas ${accGas}")
        _ <- txValidator.validate(stx, senderAccount, header, upfrontCost, accGas)
        result <- executeTransaction(stx, header, worldForTx).attempt.flatMap {
          case Left(e) =>
            if (shortCircuit) {
              F.raiseError[(BlockResult[F], List[SignedTransaction])](e)
            } else {
              executeTransactions(
                tail,
                header,
                worldForTx,
                accGas,
                accReceipts,
                executed
              )
            }

          case Right(txResult) =>
            val stateRootHash = txResult.world.stateRootHash
            val receipt = Receipt(
              postTransactionStateHash = stateRootHash,
              cumulativeGasUsed = accGas + txResult.gasUsed,
              logsBloomFilter = BloomFilter.create(txResult.logs),
              logs = txResult.logs
            )
            executeTransactions(
              tail,
              header,
              txResult.world,
              accGas + txResult.gasUsed,
              accReceipts :+ receipt,
              executed :+ stx
            )
        }

      } yield result
  }

  def executeTransaction(
      stx: SignedTransaction,
      header: BlockHeader,
      world: WorldState[F]
  ): F[TxResult[F]] = {
    log.info(s"Transaction(${stx.hash.toHex.take(7)}) execution start")
    val gasPrice      = UInt256(stx.gasPrice)
    val gasLimit      = stx.gasLimit
    val vmConfig      = EvmConfig.forBlock(header.number, config)
    val senderAddress = getSenderAddress(stx, header.number)

    for {
      checkpointWorldState <- updateSenderAccountBeforeExecution(senderAddress, stx, world)
      context              <- prepareProgramContext(stx, header, checkpointWorldState, vmConfig)
      result               <- runVM(stx, context, vmConfig)
      resultWithErrorHandling = if (result.error.isDefined) {
        //Rollback to the world before transfer was done if an error happened
        result.copy(world = checkpointWorldState, addressesToDelete = Set.empty, logs = Nil)
      } else {
        result
      }

      totalGasToRefund         = calcTotalGasToRefund(stx, resultWithErrorHandling)
      executionGasToPayToMiner = gasLimit - totalGasToRefund
      refundGasFn              = pay(senderAddress, (totalGasToRefund * gasPrice).toUInt256) _
      payMinerForGasFn         = pay(Address(header.beneficiary), (executionGasToPayToMiner * gasPrice).toUInt256) _
      deleteAccountsFn         = deleteAccounts(resultWithErrorHandling.addressesToDelete) _
      deleteTouchedAccountsFn  = deleteEmptyTouchedAccounts _
      worldAfterPayments <- refundGasFn(resultWithErrorHandling.world) >>= payMinerForGasFn
      world2             <- (deleteAccountsFn(worldAfterPayments) >>= deleteTouchedAccountsFn).flatMap(_.persisted)
    } yield {
      log.info(
        s"""Transaction(${stx.hash.toHex.take(7)}) execution end with ${result.error} error.
           |returndata: ${result.returnData.toHex}
           |gas refund: ${totalGasToRefund}, gas paid to miner: ${executionGasToPayToMiner}""".stripMargin
      )

      TxResult(world2, executionGasToPayToMiner, resultWithErrorHandling.logs, result.returnData, result.error)
    }
  }

  ////////////////////////////////
  ////////////////////////////////

  private def updateSenderAccountBeforeExecution(
      senderAddress: Address,
      stx: SignedTransaction,
      world: WorldState[F]
  ): F[WorldState[F]] =
    world.getAccount(senderAddress).map { account =>
      world.putAccount(senderAddress, account.increaseBalance(-calculateUpfrontGas(stx)).increaseNonce())
    }

  private def prepareProgramContext(
      stx: SignedTransaction,
      blockHeader: BlockHeader,
      world: WorldState[F],
      config: EvmConfig
  ): F[ProgramContext[F]] = {
    val senderAddress = getSenderAddress(stx, blockHeader.number)
    if (stx.isContractInit) {
      for {
        address <- world.createAddress(senderAddress)
        _ = log.debug(s"contract address: ${address}")
        conflict <- world.nonEmptyCodeOrNonceAccount(address)
        code = if (conflict) ByteVector(INVALID.code) else stx.payload
        world1 <- world
          .initialiseAccount(address)
          .flatMap(_.transfer(senderAddress, address, UInt256(stx.value)))
      } yield ProgramContext(stx, address, Program(code), blockHeader, world1, config)
    } else {
      for {
        world1 <- world.transfer(senderAddress, stx.receivingAddress, UInt256(stx.value))
        code   <- world1.getCode(stx.receivingAddress)
      } yield ProgramContext(stx, stx.receivingAddress, Program(code), blockHeader, world1, config)
    }
  }

  private def runVM(stx: SignedTransaction, context: ProgramContext[F], config: EvmConfig): F[ProgramResult[F]] =
    for {
      result <- vm.run(context)
    } yield {
      if (stx.isContractInit && result.error.isEmpty)
        saveNewContract(context.env.ownerAddr, result, config)
      else
        result
    }

  private def saveNewContract(address: Address, result: ProgramResult[F], config: EvmConfig): ProgramResult[F] = {
    val contractCode    = result.returnData
    val codeDepositCost = config.calcCodeDepositCost(contractCode)

    val maxCodeSizeExceeded = config.maxCodeSize.exists(codeSizeLimit => contractCode.size > codeSizeLimit)
    val codeStoreOutOfGas   = result.gasRemaining < codeDepositCost

    log.debug(
      s"codeDepositCost: ${codeDepositCost}, maxCodeSizeExceeded: ${maxCodeSizeExceeded}, codeStoreOutOfGas: ${codeStoreOutOfGas}")
    if (maxCodeSizeExceeded || codeStoreOutOfGas) {
      // Code size too big or code storage causes out-of-gas with exceptionalFailedCodeDeposit enabled
      log.debug("putcode outofgas")
      result.copy(error = Some(OutOfGas))
    } else {
      // Code storage succeeded
      log.debug(s"address putcode: ${address}")
      result.copy(gasRemaining = result.gasRemaining - codeDepositCost,
                  world = result.world.putCode(address, result.returnData))
    }
  }

  private def pay(address: Address, value: UInt256)(world: WorldState[F]): F[WorldState[F]] =
    F.ifM(world.isZeroValueTransferToNonExistentAccount(address, value))(
      ifTrue = world.pure[F],
      ifFalse = for {
        account <- world.getAccountOpt(address).getOrElse(Account.empty(config.accountStartNonce))
      } yield world.putAccount(address, account.increaseBalance(value)).touchAccounts(address)
    )

  private def getSenderAddress(stx: SignedTransaction, number: BigInt): Address = {
    val addrOpt =
      if (number >= config.eip155BlockNumber)
        stx.senderAddress(Some(config.chainId))
      else
        stx.senderAddress(None)
    addrOpt.getOrElse(Address.empty)
  }

  private def calculateUpfrontCost(stx: SignedTransaction): UInt256 =
    UInt256(calculateUpfrontGas(stx) + stx.value)

  private def calculateUpfrontGas(stx: SignedTransaction): UInt256 =
    UInt256(stx.gasLimit * stx.gasPrice)

  private def calcTotalGasToRefund(stx: SignedTransaction, result: ProgramResult[F]): BigInt =
    if (result.error.isEmpty || result.error.contains(RevertOp)) {
      val gasUsed = stx.gasLimit - result.gasRemaining
      // remaining gas plus some allowance
      result.gasRemaining + (gasUsed / 2).min(result.gasRefund)
    } else {
      0
    }

  private def deleteAccounts(addressesToDelete: Set[Address])(worldStateProxy: WorldState[F]): F[WorldState[F]] =
    addressesToDelete.foldLeft(worldStateProxy) { case (world, address) => world.delAccount(address) }.pure[F]

  private def deleteEmptyTouchedAccounts(world: WorldState[F]): F[WorldState[F]] = {
    def deleteEmptyAccount(world: WorldState[F], address: Address): F[WorldState[F]] =
      Sync[F].ifM(world.getAccountOpt(address).exists(_.isEmpty(config.accountStartNonce)))(
        ifTrue = world.delAccount(address).pure[F],
        ifFalse = world.pure[F]
      )

    Foldable[List]
      .foldLeftM(world.touchedAccounts.toList, world)((world, address) => deleteEmptyAccount(world, address))
      .map(_.clearTouchedAccounts)
  }

  private def binaryChop[Error](min: BigInt, max: BigInt)(f: BigInt => F[Option[Error]]): F[BigInt] = {
    assert(min <= max)

    if (min == max)
      F.pure(max)
    else {
      val mid           = min + (max - min) / 2
      val possibleError = f(mid)
      F.ifM(possibleError.map(_.isEmpty))(ifTrue = binaryChop(min, mid)(f), ifFalse = binaryChop(mid + 1, max)(f))
    }
  }
}

object BlockExecutor {
  def apply[F[_]: ConcurrentEffect](
      config: BlockChainConfig,
      consensus: Consensus[F]
  ): BlockExecutor[F] =
    new BlockExecutor[F](
      config,
      consensus,
      new CommonHeaderValidator[F](consensus.history),
      new BlockValidator[F](),
      new TransactionValidator[F](config),
      new VM
    )
}
