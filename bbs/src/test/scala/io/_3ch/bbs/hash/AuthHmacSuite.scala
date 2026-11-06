package io._3ch.bbs.hash

import io._3ch.bbs.external.web.GlobalCrypto
import munit.FunSuite
import scala.scalajs.js
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AuthHmacSuite extends FunSuite {
  private val secret = "Jefe"
  private val text = "what do ya want for nothing?"
  private val expected = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
  private val signature = "W9zBRr9gdU5qBCQmCJV1xw"

  test("RFC 4231 の HMAC と先頭 128 bit の base64url に一致する") {
    for {
      hex <- AuthHmac.fullHex(secret, text)
      signed <- AuthHmac.sessionSignature(secret, text)
      valid <- AuthHmac.verifySession(secret, text, signature)
      _ = assertEquals(hex, Some(expected))
      _ = assertEquals(signed, Some(signature))
      _ = assert(valid)
    } yield ()
  }

  test("署名の全位置・本文・鍵の変更を検出する") {
    for {
      results <- Future.traverse(signature.indices) { index =>
        val changed = signature.updated(index, if (signature(index) == 'A') 'B' else 'A')
        AuthHmac.verifySession(secret, text, changed)
      }
      otherText <- AuthHmac.verifySession(secret, text + "x", signature)
      otherKey <- AuthHmac.verifySession(secret + "x", text, signature)
      truncated <- AuthHmac.verifySession(secret, text, signature.dropRight(1))
      padded <- AuthHmac.verifySession(secret, text, signature + "==")
      _ = assert(results.forall(!_))
      _ = assert(!otherText && !otherKey && !truncated && !padded)
    } yield ()
  }

  test("索引は種別ごと、UA は専用の接頭辞と前後の空白除去でハッシュする") {
    for {
      token <- AuthHmac.indexKey(secret, "token", "value")
      uid <- AuthHmac.indexKey(secret, "uid", "value")
      direct <- AuthHmac.fullHex(secret, "token:value")
      ua <- AuthHmac.uaHash(secret, "  Monazilla/1.0  ")
      uaDirect <- AuthHmac.fullHex(secret, "token-ua:Monazilla/1.0")
      _ = assertEquals(token, direct)
      _ = assert(token != uid)
      _ = assertEquals(ua, uaDirect)
      _ = assertEquals(ua.map(_.length), Some(64))
    } yield ()
  }

  test("鍵が無ければ署名を作らず、検証にも通さない") {
    for {
      signed <- AuthHmac.sessionSignature("", text)
      hex <- AuthHmac.fullHex(null, text)
      valid <- AuthHmac.verifySession("", text, signature)
      _ = assertEquals(signed, None)
      _ = assertEquals(hex, None)
      _ = assert(!valid)
    } yield ()
  }

  private def brokenImport(synchronous: Boolean): Future[Unit] = {
    val subtle = GlobalCrypto.subtle.asInstanceOf[js.Dynamic]
    val original = subtle.importKey
    subtle.importKey = ((_: js.Any, _: js.Any, _: js.Any, _: js.Any, _: js.Any) => {
      if (synchronous) throw js.JavaScriptException(new js.Error("crypto failed"))
      else js.Promise.reject(new js.Error("crypto failed"))
    }): js.Function5[js.Any, js.Any, js.Any, js.Any, js.Any, js.Promise[js.Any]]
    (for {
      signed <- AuthHmac.sessionSignature(secret, text)
      valid <- AuthHmac.verifySession(secret, text, signature)
      _ = assertEquals(signed, None)
      _ = assert(!valid)
    } yield ()).transform { result =>
      subtle.importKey = original
      result
    }
  }

  test("暗号 API の同期例外でも署名を発行しない") {
    brokenImport(synchronous = true)
  }

  test("暗号 API の Promise 拒否でも署名を発行しない") {
    brokenImport(synchronous = false)
  }

  test("乱数は各 ID と認証キーの長さと文字種に従う") {
    val aids = Vector.fill(32)(AuthHmac.randomAid())
    val uids = Vector.fill(32)(AuthHmac.randomUid())
    val tokens = Vector.fill(32)(AuthHmac.randomBase32(20))
    assert(aids.forall(_.matches("[a-z0-9]{16}")))
    assert(uids.forall(_.matches("[a-z0-9]{8}")))
    assert(tokens.forall(_.matches("[a-z2-7]{20}")))
    assertEquals(aids.distinct.size, aids.size)
    assertEquals(uids.distinct.size, uids.size)
    assertEquals(tokens.distinct.size, tokens.size)
  }
}
