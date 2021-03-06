package jbok.evm

import jbok.common.gen
import jbok.core.models.UInt256
import jbok.core.models.UInt256._
import jbok.core.{CoreSpec, StatelessGen}
import scodec.bits.ByteVector

class UInt256Spec extends CoreSpec {

  val Modulus: BigInt        = UInt256.MaxValue.toBigInt + 1
  val MaxSignedValue: BigInt = Modulus / 2 - 1

  val specialNumbers =
    Seq(BigInt(-1), BigInt(0), BigInt(1), MaxValue.toBigInt, -MaxValue.toBigInt, -MaxValue.toBigInt + 1)

  val pairs: Seq[(BigInt, BigInt)] = specialNumbers
    .combinations(2)
    .map { case Seq(n1, n2) => n1 -> n2 }
    .toSeq

  val specialCases = Table(("n1", "n2"), pairs: _*)

  def toSignedBigInt(n: BigInt): BigInt = if (n > MaxSignedValue) n - Modulus else n

  def toUnsignedBigInt(n: BigInt): BigInt = if (n < 0) n + Modulus else n

  /** For each operation (op) tests check a following property:
    *For two BigInts (n1, n2):
    *UInt256(n1) op UInt256(n2) == UInt256(n1 op n2)
    */
  "&" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) & UInt256(n2)) == UInt256(n1 & n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) & UInt256(n2)) == UInt256(n1 & n2))
    }
  }

  "|" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) | UInt256(n2)) == UInt256(n1 | n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) | UInt256(n2)) == UInt256(n1 | n2))
    }
  }

  "^" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) ^ UInt256(n2)) == UInt256(n1 ^ n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) ^ UInt256(n2)) == UInt256(n1 ^ n2))
    }
  }

  "~" in {
    forAll { n: BigInt =>
      assert(~UInt256(n) == UInt256(~n))
    }
    forAll(Table("n", specialNumbers: _*)) { n: BigInt =>
      assert(~UInt256(n) == UInt256(~n))
    }
  }

  "negation" in {
    forAll { n: BigInt =>
      assert(-UInt256(n) == UInt256(-n))
    }
    forAll(Table("n", specialNumbers: _*)) { n: BigInt =>
      assert(-UInt256(n) == UInt256(-n))
    }
    assert(-UInt256(1) == UInt256(-1))
    assert(-UInt256(-1) == UInt256(1))
    assert(-UInt256.zero == UInt256.zero)
  }

  "+" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) + UInt256(n2) == UInt256(n1 + n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) + UInt256(n2) == UInt256(n1 + n2))
    }
  }

  "-" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) - UInt256(n2) == UInt256(n1 - n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) - UInt256(n2) == UInt256(n1 - n2))
    }
  }

  "*" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) * UInt256(n2) == UInt256(n1 * n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) * UInt256(n2) == UInt256(n1 * n2))
    }
  }

  "/" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) * UInt256(n2) == UInt256(n1 * n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) * UInt256(n2) == UInt256(n1 * n2))
    }
  }

  "div" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      whenever(n2 != 0) {
        assert((UInt256(n1) div UInt256(n2)) == UInt256(n1 / n2))
      }
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      whenever(n1 > 0 && n2 > 0) {
        assert((UInt256(n1) div UInt256(n2)) == UInt256(n1 / n2))
      }
    }
    assert((UInt256(1) div zero) == zero)
  }

  "sdiv" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      whenever(n2 != 0) {
        val expected: BigInt = toUnsignedBigInt(toSignedBigInt(n1) / toSignedBigInt(n2))
        assert((UInt256(n1) sdiv UInt256(n2)) == UInt256(expected))
      }
    }
    assert((UInt256(-1) sdiv UInt256(-MaxValue.toBigInt)) == UInt256(-1))
    assert((UInt256(-1) sdiv zero) == zero)
  }

  "mod" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      whenever(n2 != 0) {
        assert((UInt256(n1) mod UInt256(n2)) == UInt256(n1 mod n2))
      }
    }
    assert((UInt256(-1) mod UInt256(MaxValue.toBigInt)) == zero)
    assert((UInt256(1) mod zero) == zero)
  }

  "smod" in {
    assert((UInt256(Modulus - 1) smod UInt256(3)) == UInt256(Modulus - 1))
    assert((UInt256(-1) smod UInt256(MaxValue.toBigInt)) == zero)
    assert((UInt256(1) smod zero) == zero)
  }

  "addmod" in {
    forAll { (n1: BigInt, n2: BigInt, n3: BigInt) =>
      whenever(n3 != 0) {
        assert(UInt256(n1).addmod(UInt256(n2), UInt256(n3)) == UInt256((n1 + n2) mod n3))
      }
    }
    assert(UInt256(42).addmod(UInt256(42), zero) == zero)
  }

  "mulmod" in {
    forAll { (n1: BigInt, n2: BigInt, n3: BigInt) =>
      whenever(n3 != 0) {
        assert(UInt256(n1).mulmod(UInt256(n2), UInt256(n3)) == UInt256((n1 * n2) mod n3))
      }
    }
    assert(UInt256(42).mulmod(UInt256(42), zero) == zero)
  }

  "**" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) ** UInt256(n2) == UInt256(n1.modPow(n2, Modulus)))
    }
  }

  "signExtend" in {
    val testData = Table[UInt256, UInt256, UInt256](
      ("value", "extension", "result"),
      (42, 3, 42),
      (42, 1, 42),
      (42, -1, 42),
      (42, 0, 42),
      (42, UInt256.size, 42),
      (42, UInt256.size + 1, 42),
      (-42, UInt256.size, -42),
      (-42, UInt256.size + 1, -42),
      (-42, -11, -42),
      (-1, 1, -1),
      (-1, 1, -1),
      (0x1a81ff, 1, UInt256(Array.fill[Byte](30)(-1) ++ Array(0x81, 0xff).map(_.toByte))),
      (0x1a81ff, 2, 0x1a81ff),
      (0x1a81ff, 10, 0x1a81ff)
    )

    forAll(testData) { (uint, extension, result) =>
      assert(uint.signExtend(extension) == result)
    }
  }

  "slt" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) slt UInt256(n2)) == (toSignedBigInt(n1) < toSignedBigInt(n2)))
    }

    val testData = Table[UInt256, UInt256, Boolean](("a", "b", "result"), (-1, 1, true), (1, -1, false), (1, 0, false))

    forAll(testData) { (a, b, result) =>
      assert((a slt b) == result)
    }
  }

  "sgt" in {
    forAll { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) sgt UInt256(n2)) == (toSignedBigInt(n1) > toSignedBigInt(n2)))
    }

    val testData =
      Table[UInt256, UInt256, Boolean](("a", "b", "result"), (-1, 1, false), (1, -1, true), (0, 1, false), (1, 0, true))

    forAll(testData) { (a, b, result) =>
      assert((a sgt b) == result)
    }
  }

  "getByte" in {
    val v1 = UInt256(ByteVector((100 to 131).map(_.toByte).toArray))

    val testData = Table[UInt256, UInt256, UInt256](
      ("value", "idx", "result"),
      (42, -1, zero),
      (42, UInt256.size, zero),
      (42, UInt256.size + 1, zero),
      (1, 287, zero),
      (v1, 0, 100),
      (v1, 1, 101),
      (v1, 30, 130),
      (v1, 31, 131),
      (UInt256(Array.fill[Byte](32)(-50)), 8, 256 - 50)
    )

    forAll(testData) { (a, b, result) =>
      assert(a.getByte(b) == result)
    }
  }

  "<<" in {
    val v1 = UInt256(Byte.MinValue +: Array.fill[Byte](31)(0))
    val v2 = UInt256(Byte.MaxValue +: Array.fill[Byte](31)(255.toByte))
    val testData = Table[UInt256, UInt256, UInt256](
      ("value", "idx", "result"),
      (1, 0, 1),
      (1, 1, 2),
      (1, 255, v1),
      (1, 256, 0),
      (UInt256.MaxValue, 0, UInt256.MaxValue),
      (UInt256.MaxValue, 1, UInt256.MaxValue - 1),
      (1, 257, 0),
      (UInt256.MaxValue, 255, v1),
      (UInt256.MaxValue, 256, 0),
      (0, 1, 0),
      (v2, 1, UInt256.MaxValue - 1)
    )

    forAll(testData) { (a, b, result) =>
      assert((a << b) == result)
    }
  }

  ">>" in {
    val v1 = UInt256(Byte.MinValue +: Array.fill[Byte](31)(0))
    val v2 = UInt256(64.toByte +: Array.fill[Byte](31)(0))
    val v3 = UInt256(Byte.MaxValue +: Array.fill[Byte](31)(255.toByte))
    val testData = Table[UInt256, UInt256, UInt256](
      ("value", "idx", "result"),
      (1, 0, 1),
      (1, 1, 0),
      (0, 1, 0),
      (v1, 255, UInt256.MaxValue),
      (v1, 256, UInt256.MaxValue),
      (v1, 257, UInt256.MaxValue),
      (UInt256.MaxValue, 0, UInt256.MaxValue),
      (UInt256.MaxValue, 1, UInt256.MaxValue),
      (UInt256.MaxValue, 255, UInt256.MaxValue),
      (UInt256.MaxValue, 256, UInt256.MaxValue),
      (0, 1, 0),
      (v2, 254, 1),
      (v3, 248, 127),
      (v3, 254, 1),
      (v3, 255, 0),
      (v3, 256, 0)
    )

    forAll(testData) { (a, b, result) =>
      assert((a >> b) == result)
    }
  }

  ">>>" in {
    val v1 = UInt256(Byte.MinValue +: Array.fill[Byte](31)(0))
    val v2 = UInt256(64.toByte +: Array.fill[Byte](31)(0))
    val v3 = UInt256(Byte.MaxValue +: Array.fill[Byte](31)(255.toByte))
    val testData = Table[UInt256, UInt256, UInt256](
      ("value", "idx", "result"),
      (1, 0, 1),
      (1, 1, 0),
      (v1, 1, v2),
      (v1, 255, 1),
      (v1, 256, 0),
      (v1, 257, 0),
      (UInt256.MaxValue, 0, UInt256.MaxValue),
      (UInt256.MaxValue, 1, v3),
      (UInt256.MaxValue, 255, 1),
      (UInt256.MaxValue, 256, 0),
      (0, 1, 0)
    )

    forAll(testData) { (a, b, result) =>
      assert((a >>> b) == result)
    }
  }

  "intValue" in {
    assert(specialNumbers.map(UInt256(_).toInt) == Seq(Int.MaxValue, 0, 1, Int.MaxValue, 1, 2))
  }

  "comparison" in {
    type CFUI = (UInt256, UInt256) => Boolean
    type CFBI = (BigInt, BigInt) => Boolean
    case class Cmp(uint: CFUI, bi: CFBI)

    val cmpFuncUInt256 = Seq[CFUI](_ > _, _ >= _, _ < _, _ <= _)
    val cmpFuncBigInt  = Seq[CFBI](_ > _, _ >= _, _ < _, _ <= _)
    val comparators    = cmpFuncUInt256.zip(cmpFuncBigInt).map(Cmp.tupled)

    forAll(Table("comparators", comparators: _*)) { cmp =>
      forAll { (a: UInt256, b: UInt256) =>
        assert(cmp.uint(a, b) == cmp.bi(a.toBigInt, b.toBigInt))
      }

      forAll(specialCases) { (x, y) =>
        val (a, b) = (UInt256(x), UInt256(y))
        assert(cmp.uint(a, b) == cmp.bi(a.toBigInt, b.toBigInt))
      }
    }
  }

  "Passing too long ByteVector should throw an exception" in {
    assertThrows[IllegalArgumentException] {
      UInt256(ByteVector(Array.fill(UInt256.size + 1)(1.toByte)))
    }
  }

  "UInt256 converted to a byte array should always have length 32 bytes" in {
    forAll { n: BigInt =>
      assert(UInt256(n).bytes.size == 32)
    }
    // regression
    assert(UInt256(BigInt("115792089237316195423570985008687907853269984665640564039457584007913129639935")).bytes.size == 32)
  }

  "2-way bytes conversion" in {
    forAll { x: UInt256 =>
      val y = UInt256(x.bytes)
      assert(x === y)
    }

    forAll(gen.boundedByteVector(0, 32)) { xs =>
      val ys = UInt256(xs).bytes
      assert(xs.dropWhile(_ == 0) === ys.dropWhile(_ == 0))
    }
  }

  "byteSize" in {
    val table = Table[BigInt, Int](("x", "expected"), (0, 0), (1, 1), (255, 1), (256, 2), (65535, 2), (65536, 3), (BigInt(2).pow(256) - 1, 32), (BigInt(2).pow(256), 0))
    forAll(table) { (x, expected) =>
      assert(UInt256(x).byteSize === expected)
    }

    forAll(StatelessGen.uint256(min = UInt256(1))) { x =>
      val byteSize = 1 + math.floor(math.log(x.toBigInt.doubleValue()) / math.log(256)).toInt
      assert(x.byteSize === byteSize)
    }
  }
}
