package io._3ch.bbs.hash

import io._3ch.bbs.external.web.GlobalCrypto
import io._3ch.common.domain.account.{LoginThrottle, Password, PasswordAlgorithm, PasswordCredential}
import munit.FunSuite
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

class PasswordHasherSuite extends FunSuite {
  private val now = 2000000000d
  private val salt = Vector.tabulate(16)(_.toByte)
  private val password = Password.parse("password").toOption.get
  private def hex(value: String): Vector[Byte] = value.grouped(2).map(Integer.parseInt(_, 16).toByte).toVector
  private val legacyHash = hex("e1b08f92be8174d9f442d95d89aa4ccdc311231a4d70d0b854d1548de8fabdfd")
  private val credential = PasswordCredential(PasswordAlgorithm.Pbkdf2Sha256, 1, salt, legacyHash, now)
  private val subtle = GlobalCrypto.subtle.asInstanceOf[js.Dynamic]

  private def withMethod[A](target: js.Dynamic, name: String, replacement: js.Any)(body: => Future[A]): Future[A] = {
    val previous = target.selectDynamic(name)
    target.updateDynamic(name)(replacement)
    val pending = try body catch {
      case error: Throwable => target.updateDynamic(name)(previous); throw error
    }
    pending.transform { result => target.updateDynamic(name)(previous); result }
  }

  private def assertUnavailable(): Future[Unit] = for {
    created <- PasswordHasher.create(password, now)
    verified <- PasswordHasher.verify(password, credential)
  } yield {
    assertEquals(created, Left(PasswordHashFailure.Unavailable))
    assertEquals(verified, Left(PasswordHashFailure.Unavailable))
  }

  test("独立した hashlib の固定値で旧反復数と100000回の照合を確かめる") {
    // Python hashlib.pbkdf2_hmac("sha256", password, bytes(range(16)), iterations, 32)
    val cases = Seq(
      ("password", 1, "e1b08f92be8174d9f442d95d89aa4ccdc311231a4d70d0b854d1548de8fabdfd"),
      ("password", 2, "a0ffd34292e4e7d519ee0f25124e0c5b9481d88be40f4662eb6cb4b9e43f9f41"),
      ("password", 100000, "a29fea0fed85c5b8610c2e5697ea41b5587139e58a388e0c7b7ced30d4e6d8df"),
      ("Café🔒password", 100000, "06ba98c27be7a21143bb2d1abcde599f91418483637c090d217b4cb11478c046")
    )
    Future.traverse(cases) { case (plain, iterations, expected) =>
      PasswordHasher.verify(Password.parse(plain).toOption.get, credential.copy(iterations = iterations, hash = hex(expected)))
        .map(result => assertEquals(result, Right(true)))
    }
  }

  test("不一致のパスワードとハッシュの全32位置の変更を拒否する") {
    for {
      wrong <- PasswordHasher.verify(Password.parse("wrong-password").toOption.get, credential)
      changed <- Future.traverse(credential.hash.indices) { index =>
        val mutated = credential.copy(hash = credential.hash.updated(index, (credential.hash(index) ^ 1).toByte))
        PasswordHasher.verify(password, mutated)
      }
    } yield {
      assertEquals(wrong, Right(false))
      assert(changed.forall(_ == Right(false)))
    }
  }

  test("NFCで等価な照合入力は同じ固定値に一致する") {
    val stored = credential.copy(iterations = 100000, hash = hex("06ba98c27be7a21143bb2d1abcde599f91418483637c090d217b4cb11478c046"))
    PasswordHasher.verify(Password.parse("Cafe\u0301🔒password").toOption.get, stored).map(result => assertEquals(result, Right(true)))
  }

  test("作成ごとに異なる16バイトの乱数ソルトと現在の資格情報を作る") {
    for {
      first <- PasswordHasher.create(password, now)
      second <- PasswordHasher.create(password, now + 1)
      a = first.toOption.get
      b = second.toOption.get
      verified <- PasswordHasher.verify(password, a)
    } yield {
      assert(a.valid && b.valid)
      assertEquals(a.algo, PasswordAlgorithm.Pbkdf2Sha256)
      assertEquals(a.iterations, 100000)
      assertEquals(a.updatedAt, now)
      assertEquals(a.salt.size, 16)
      assertEquals(a.hash.size, 32)
      assertEquals(a.throttle, LoginThrottle())
      assert(a.salt != b.salt)
      assert(a.hash != b.hash)
      assertEquals(verified, Right(true))
      assertEquals(a.toString, "PasswordCredential(pbkdf2-sha256,100000,<redacted>)")
      assert(!a.toString.contains(password.value))
      assert(!a.toString.contains(a.salt.mkString))
      assert(!a.toString.contains(a.hash.mkString))
    }
  }

  test("不正な資格情報・時刻・null入力は暗号APIを呼ぶ前に拒否する") {
    var imports = 0
    val rejected = ((_: js.Any, _: js.Any, _: js.Any, _: js.Any, _: js.Any) => {
      imports += 1
      js.Promise.reject(new js.Error("unexpected import"))
    }): js.Function5[js.Any, js.Any, js.Any, js.Any, js.Any, js.Promise[js.Any]]
    withMethod(subtle, "importKey", rejected) {
      val invalid = Seq(null, credential.copy(algo = null), credential.copy(iterations = 0), credential.copy(iterations = 100001),
        credential.copy(salt = Vector.empty), credential.copy(salt = Vector.fill(17)(0.toByte)), credential.copy(salt = null),
        credential.copy(hash = Vector.empty), credential.copy(hash = Vector.fill(31)(0.toByte)), credential.copy(hash = Vector.fill(33)(0.toByte)),
        credential.copy(hash = null), credential.copy(updatedAt = -1), credential.copy(updatedAt = 0.5), credential.copy(updatedAt = Double.NaN),
        credential.copy(throttle = null), credential.copy(throttle = LoginThrottle(locks = Map("raw-ip" -> now))))
      for {
        results <- Future.traverse(invalid)(PasswordHasher.verify(password, _))
        times <- Future.traverse(Seq(-1d, 0.5, Double.NaN, Double.PositiveInfinity))(PasswordHasher.create(password, _))
        noPassword <- PasswordHasher.create(null, now)
        noVerificationPassword <- PasswordHasher.verify(null, credential)
      } yield {
        assert(results.forall(_ == Left(PasswordHashFailure.Invalid)))
        assert(times.forall(_ == Left(PasswordHashFailure.Invalid)))
        assertEquals(noPassword, Left(PasswordHashFailure.Invalid))
        assertEquals(noVerificationPassword, Left(PasswordHashFailure.Invalid))
        assertEquals(imports, 0)
      }
    }
  }

  for (method <- Seq("importKey", "deriveBits")) {
    test(s"$method が無ければ利用不能を返す") {
      withMethod(subtle, method, js.undefined)(assertUnavailable())
    }
    test(s"$method の同期例外でも利用不能を返す") {
      val failed = ((_: js.Any, _: js.Any, _: js.Any, _: js.Any, _: js.Any) => {
        throw js.JavaScriptException(new js.Error("crypto failed"))
      }): js.Function5[js.Any, js.Any, js.Any, js.Any, js.Any, js.Promise[js.Any]]
      withMethod(subtle, method, failed)(assertUnavailable())
    }
    test(s"$method の Promise 拒否でも利用不能を返す") {
      val failed = ((_: js.Any, _: js.Any, _: js.Any, _: js.Any, _: js.Any) => js.Promise.reject(new js.Error("crypto failed"))): js.Function5[js.Any, js.Any, js.Any, js.Any, js.Any, js.Promise[js.Any]]
      withMethod(subtle, method, failed)(assertUnavailable())
    }
  }

  test("乱数APIの欠落と同期例外でも資格情報を発行しない") {
    val crypto = GlobalCrypto.asInstanceOf[js.Dynamic]
    val failed = ((_: js.Any) => { throw js.JavaScriptException(new js.Error("random failed")) }): js.Function1[js.Any, js.Any]
    for {
      missing <- withMethod(crypto, "getRandomValues", js.undefined)(PasswordHasher.create(password, now))
      throwing <- withMethod(crypto, "getRandomValues", failed)(PasswordHasher.create(password, now))
    } yield {
      assertEquals(missing, Left(PasswordHashFailure.Unavailable))
      assertEquals(throwing, Left(PasswordHashFailure.Unavailable))
    }
  }

  test("暗号APIが32バイト以外を返したら照合・発行を通さない") {
    val invalid = ((_: js.Any, _: js.Any, _: js.Any) => js.Promise.resolve(new Uint8Array(31).buffer)): js.Function3[js.Any, js.Any, js.Any, js.Promise[js.Any]]
    withMethod(subtle, "deriveBits", invalid)(assertUnavailable())
  }
}
