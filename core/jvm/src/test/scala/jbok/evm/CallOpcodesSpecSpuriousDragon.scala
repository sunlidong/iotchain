package jbok.evm

import cats.effect.IO
import jbok.core.CoreSpec
import jbok.core.ledger.History
import jbok.core.models.UInt256
import jbok.persistent.MemoryKVStore

class CallOpcodesSpecSpuriousDragon extends CoreSpec {
  val evmConfig  = EvmConfig.SpuriousDragonConfigBuilder(None)
  val store      = MemoryKVStore[IO].unsafeRunSync()
  val history    = History(store, chainId)
  val startState = history.getWorldState(noEmptyAccounts = true).unsafeRunSync()
  import evmConfig.feeSchedule._

  val fxt = new CallOpFixture(evmConfig, startState)

  "CALL" when {

    "call depth limit is reached" should {
      val context = fxt.context.copy(env = fxt.env.copy(callDepth = EvmConfig.MaxCallDepth))
      val call    = fxt.CallResult(op = CALL, context = context)

      "not modify world state" in {
        call.world shouldBe fxt.worldWithExtAccount
      }
    }

    "call value is greater than balance" should {

      val call = fxt.CallResult(op = CALL, value = fxt.initialBalance + 1)

      "not modify world state" in {
        call.world shouldBe fxt.worldWithExtAccount
      }
    }

    "external contract terminates abnormally" should {

      val context = fxt.context.copy(world = fxt.worldWithInvalidProgram)
      val call    = fxt.CallResult(op = CALL, context)

      "modify only touched accounts in world state" in {
        call.world shouldBe fxt.worldWithInvalidProgram.touchAccounts(fxt.ownerAddr, fxt.extAddr)
      }
    }

    "calling an empty" should {

      val contextEmptyAccount = fxt.context.copy(world = fxt.worldWithExtEmptyAccount)
      val callEmptyAccount    = fxt.CallResult(op = CALL, contextEmptyAccount)
      val callZeroTransfer    = fxt.CallResult(op = CALL, contextEmptyAccount, value = UInt256.zero)

      "consume correct gas (refund call gas, add new account modifier) when transferring value to Empty Account" in {
        val expectedGas = G_call + G_callvalue + G_newaccount - G_callstipend + fxt.expectedMemCost
        callEmptyAccount.stateOut.gasUsed shouldBe expectedGas
        callEmptyAccount.world.touchedAccounts.size shouldBe 2
      }

      "consume correct gas when transferring no value to Empty Account" in {
        val expectedGas = G_call + fxt.expectedMemCost
        callZeroTransfer.stateOut.gasUsed shouldBe expectedGas
        callZeroTransfer.world.touchedAccounts.size shouldBe 2
      }
    }
  }
}
