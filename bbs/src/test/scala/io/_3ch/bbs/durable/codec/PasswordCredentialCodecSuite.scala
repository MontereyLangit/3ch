package io._3ch.bbs.durable.codec

import io._3ch.common.domain.account.*
import munit.FunSuite
import scala.scalajs.js

class PasswordCredentialCodecSuite extends FunSuite {
  private val now = 2000000000d
  private val ip = "a" * 64
  private val credential = PasswordCredential(PasswordAlgorithm.Pbkdf2Sha256, 100000,
    Vector.tabulate(16)(i => (i * 17).toByte), Vector.tabulate(32)(i => (255 - i).toByte), now,
    LoginThrottle(Vector(LoginFailure(ip, now - 1)), Map(ip -> (now + 100))))

  test("資格情報と失敗計数を小文字の16進数で保存して元のバイト列へ戻す") {
    val encoded = PasswordCredentialCodec.encode(credential)
    assertEquals(encoded.salt.asInstanceOf[String], "00112233445566778899aabbccddeeff")
    assert(encoded.hash.asInstanceOf[String].matches("[0-9a-f]{64}"))
    assertEquals(PasswordCredentialCodec.decode(encoded), credential)
    val old = credential.copy(iterations = 10000)
    assertEquals(PasswordCredentialCodec.decode(PasswordCredentialCodec.encode(old)), old)
    assert(PasswordCredentialCodec.decode(PasswordCredentialCodec.encode(old)).needsRehash)
  }

  test("資格情報の必須フィールドを欠損時に補わない") {
    Seq("algo", "iterations", "salt", "hash", "updatedAt", "throttle").foreach { field =>
      Seq[js.Any](js.undefined, null).foreach { missing =>
        val encoded = PasswordCredentialCodec.encode(credential)
        encoded.updateDynamic(field)(missing)
        intercept[Throwable](PasswordCredentialCodec.decode(encoded))
      }
    }
  }

  test("未知の方式・反復数の境界外・壊れたソルトやハッシュ・不正時刻を拒否する") {
    val invalid = Vector[(String, js.Any)]("algo" -> "unknown", "iterations" -> 0, "iterations" -> -1,
      "iterations" -> 1.5, "iterations" -> 100001, "iterations" -> "100000", "salt" -> ("A" * 32),
      "salt" -> ("a" * 30), "hash" -> ("a" * 63), "hash" -> ("g" * 64), "hash" -> js.Array(1, 2),
      "updatedAt" -> -1, "updatedAt" -> 1.5, "updatedAt" -> Double.NaN)
    invalid.foreach { case (field, value) =>
      val encoded = PasswordCredentialCodec.encode(credential)
      encoded.updateDynamic(field)(value)
      intercept[Throwable](PasswordCredentialCodec.decode(encoded))
    }
  }

  test("失敗計数の配列・ロックの辞書と必須フィールドの型を厳密に検証する") {
    Seq("failures", "locks").foreach { field =>
      Seq[js.Any](js.undefined, null, "invalid").foreach { missing =>
        val encoded = PasswordCredentialCodec.encode(credential)
        encoded.throttle.updateDynamic(field)(missing)
        intercept[Throwable](PasswordCredentialCodec.decode(encoded))
      }
    }
    val invalid = Seq[js.Any](
      js.Dynamic.literal(failures = js.Dynamic.literal(), locks = js.Dynamic.literal()),
      js.Dynamic.literal(failures = js.Array(js.Dynamic.literal(keyIpHash = ip)), locks = js.Dynamic.literal()),
      js.Dynamic.literal(failures = js.Array(js.Dynamic.literal(t = now)), locks = js.Dynamic.literal()),
      js.Dynamic.literal(failures = js.Array(js.Dynamic.literal(keyIpHash = "raw-ip", t = now)), locks = js.Dynamic.literal()),
      js.Dynamic.literal(failures = js.Array(), locks = js.Array()),
      js.Dynamic.literal(failures = js.Array(), locks = new js.Date()),
      js.Dynamic.literal(failures = js.Array(), locks = js.Dictionary[js.Any](ip -> "2000000100")),
      js.Dynamic.literal(failures = js.Array(), locks = js.Dictionary[js.Any]("raw-ip" -> now)))
    invalid.foreach { throttle =>
      val encoded = PasswordCredentialCodec.encode(credential)
      encoded.throttle = throttle
      intercept[Throwable](PasswordCredentialCodec.decode(encoded))
    }
  }

  test("空の計数は往復し、不正なモデルは保存用に変換しない") {
    val empty = credential.copy(throttle = LoginThrottle())
    assertEquals(PasswordCredentialCodec.decode(PasswordCredentialCodec.encode(empty)), empty)
    intercept[Throwable](PasswordCredentialCodec.encode(null))
    intercept[Throwable](PasswordCredentialCodec.encode(credential.copy(salt = Vector.empty)))
    intercept[Throwable](PasswordCredentialCodec.encode(credential.copy(throttle = LoginThrottle(locks = Map("raw-ip" -> now)))))
  }
}
