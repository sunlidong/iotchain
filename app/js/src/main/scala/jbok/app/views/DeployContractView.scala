package jbok.app.views

import cats.implicits._
import com.thoughtworks.binding
import com.thoughtworks.binding.Binding
import com.thoughtworks.binding.Binding.{Constants, Var, Vars}
import jbok.app.{AppState, ContractAddress}
import jbok.app.api.{BlockParam, TransactionRequest}
import jbok.core.models.{Account, Address, UInt256}
import org.scalajs.dom.raw.{HTMLInputElement, HTMLSelectElement}
import org.scalajs.dom.{Element, _}
import scodec.bits.ByteVector

import scala.util.matching.Regex

case class DeployContractView(state: AppState) {
  val nodeAccounts = Vars.empty[Address]

  val currentId                     = state.currentId.value
  val client                        = currentId.flatMap(state.clients.value.get(_))
  val account: Var[Option[Account]] = Var(None)
  val from: Var[String]             = Var("")
  val fromSyntax: Var[Boolean]      = Var(true)
  val value: Var[String]            = Var("")
  val valueSyntax: Var[Boolean]     = Var(true)
  val gasLimit: Var[String]         = Var("1500000")
  val gasLimitSyntax: Var[Boolean]  = Var(true)
  val gasPrice: Var[String]         = Var("1")
  val gasPriceSyntax: Var[Boolean]  = Var(true)
  val nonce: Var[String]            = Var("")
  val nonceSyntax: Var[Boolean]     = Var(true)
  val data: Var[String]             = Var("")
  val dataSyntax: Var[Boolean]      = Var(true)
  val passphase: Var[String]        = Var("")

  private def fetch() = {
    val p = for {
      accounts <- client.traverse(_.admin.listAccounts)
      _ = accounts.map(nodeAccounts.value ++= _)
    } yield ()
    p.unsafeToFuture()
  }

  fetch()

  def submit() =
    if (from.value.nonEmpty && fromSyntax.value && valueSyntax.value && gasLimitSyntax.value && gasPriceSyntax.value && nonceSyntax.value && dataSyntax.value && client.nonEmpty) {
      val fromSubmit     = Address(ByteVector.fromValidHex(from.value))
      val toSubmit       = None
      val valueSubmit    = if (value.value.isEmpty) None else Some(BigInt(value.value))
      val gasLimitSubmit = if (gasLimit.value.isEmpty) None else Some(BigInt(gasLimit.value))
      val gasPriceSubmit = if (gasPrice.value.isEmpty) None else Some(BigInt(gasPrice.value))
      val nonceSubmit    = if (nonce.value.isEmpty) None else Some(BigInt(nonce.value))
      val dataSubmit     = if (data.value.isEmpty) None else Some(ByteVector.fromValidHex(data.value))
      val txRequest =
        TransactionRequest(fromSubmit, toSubmit, valueSubmit, gasLimitSubmit, gasPriceSubmit, nonceSubmit, dataSubmit)
      val password = if (passphase.value.isEmpty) Some("") else Some(passphase.value)

      val p = for {
        hash <- client.get.admin.sendTransaction(txRequest, password)
        stx  <- client.get.public.getTransactionByHash(hash)
        _       = stx.map(state.stxs.value(currentId.get).value += _)
        _       = state.receipts.value(currentId.get).value += (hash -> Var(None))
        address = ContractAddress.getContractAddress(fromSubmit, UInt256(nonceSubmit.get))
//        _       = if (!state.contractInfo.value.toSet.contains(address)) state.contractInfo.value += address
      } yield ()
      p.unsafeToFuture()

    } else {
      println("some error")
    }

  private def updateAccount(address: String) = {
    val p = for {
      a <- client.traverse(_.public.getAccount(Address(ByteVector.fromValidHex(address)), BlockParam.Latest))
      _ = account.value = a
    } yield ()
    p.unsafeToFuture()
  }

  private val fromOnInputHandler = { event: Event =>
    event.currentTarget match {
      case input: HTMLInputElement =>
        from.value = input.value.trim.toLowerCase
        fromSyntax.value = if (InputValidator.isValidAddress(from.value)) true else false
        if (fromSyntax.value) {
          updateAccount(from.value)
        } else {
          account.value = None
        }
      case _ =>
    }
  }

  private val valueOnInputHandler = { event: Event =>
    event.currentTarget match {
      case input: HTMLInputElement =>
        value.value = input.value.trim
        valueSyntax.value = if (InputValidator.isValidValue(value.value, account.value)) true else false
      case _ =>
    }
  }

  private val gasLimitOnInputHandler = { event: Event =>
    event.currentTarget match {
      case input: HTMLInputElement =>
        gasLimit.value = input.value.trim
        gasLimitSyntax.value = if (InputValidator.isValidNumber(gasLimit.value)) true else false
      case _ =>
    }
  }

  private val gasPriceOnInputHandler = { event: Event =>
    event.currentTarget match {
      case input: HTMLInputElement =>
        gasPrice.value = input.value.trim
        gasPriceSyntax.value = if (InputValidator.isValidNumber(gasPrice.value)) true else false
      case _ =>
    }
  }

  private val nonceOnInputHandler = { event: Event =>
    event.currentTarget match {
      case input: HTMLInputElement =>
        nonce.value = input.value.trim
        nonceSyntax.value = if (InputValidator.isValidNumber(nonce.value)) true else false
      case _ =>
    }
  }

  private val dataOnInputHandler = { event: Event =>
    event.currentTarget match {
      case input: HTMLInputElement =>
        data.value = input.value.trim
        dataSyntax.value = if (InputValidator.isValidData(data.value)) true else false
      case _ =>
    }
  }

  private val passphaseOnInputHandler = { event: Event =>
    event.currentTarget match {
      case input: HTMLInputElement => passphase.value = input.value.trim
      case _                       =>
    }
  }

  val otherAddressDisable: Var[Boolean] = Var(false)
  private val onChangeHandler = { event: Event =>
    event.currentTarget match {
      case select: HTMLSelectElement =>
        val v = select.options(select.selectedIndex).value
        otherAddressDisable.value = if (v == "other") {
          from.value = ""
          account.value = None
          fromSyntax.value = false
          false
        } else {
          from.value = v.substring(2)
          updateAccount(from.value)
          fromSyntax.value = true
          true
        }
      case _ =>
    }
  }

  @binding.dom
  def render: Binding[Element] =
    <div>
      {
      val accountList = nodeAccounts.bind

      <div>
        <label for="account-to-send">Choose a account:</label>
        <select id="1" class="autocomplete" onchange={onChangeHandler}>
          {for(account<-Constants(accountList: _*)) yield {
          <option value={account.toString}>{account.toString}</option>
        }}
          <option value="other" selected={true}>other address</option>
        </select>
        <input type="text" placeholder="" name="other" oninput={fromOnInputHandler} value={from.bind} class={if(fromSyntax.bind) "valid" else "invalid"} disabled={otherAddressDisable.bind}/>
      </div>
      }

     <div>
        <label for="value">
          <b>
            {
            account.bind match {
              case Some(a) => s"value (max balance: ${a.balance.toString})"
              case _ => "value"
            }
            }
          </b>
        </label>
        <input type="text" placeholder="" name="value" oninput={valueOnInputHandler} value={value.bind} disabled={true} class={if(valueSyntax.bind) "valid" else "invalid"} />
      </div>

      <div>
        <label for="gasLimit">
          <b>
            gasLimit
          </b>
        </label>
        <input type="text" placeholder="" name="gasLimit" oninput={gasLimitOnInputHandler} value={gasLimit.bind} disabled={true} class={if(gasLimitSyntax.bind) "valid" else "invalid"} />
      </div>

      <div>
        <label for="gasPrice">
          <b>
            gasPrice
          </b>
        </label>
        <input type="text" placeholder="" name="gasPrice" oninput={gasPriceOnInputHandler} value={gasPrice.bind} disabled={true} class={if(gasPriceSyntax.bind) "valid" else "invalid"} />
      </div>

      <div>
        <label for="nonce">
          <b>
            nonce
          </b>
        </label>
        <input type="text" placeholder= {
      account.bind match{
        case Some(a) =>
          nonce.value = a.nonce.toString
          nonce.value
        case _ => "" }
      } name="nonce" oninput={nonceOnInputHandler} value={nonce.bind} class={if(nonceSyntax.bind) "valid" else "invalid"} />
      </div>

      <div>
        <label for="data">
          <b>
            Contract Byte Code
          </b>
        </label>
        <input type="text" placeholder="" name="data" oninput={dataOnInputHandler} value={data.bind} class={if(dataSyntax.bind) "valid" else "invalid"} />
      </div>

      <div>
        <label for="passphase">
          <b>
            passphase
          </b>
        </label>
        <input type="password" placeholder="" name="passphase" oninput={passphaseOnInputHandler} value={passphase.bind} class="valid" />
      </div>

    </div>
}
