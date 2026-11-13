package io._3ch.bbs.durable

import io._3ch.bbs.durable.codec.{AccountCodec, PasswordCredentialCodec}
import io._3ch.bbs.handler.HandlerTestKit
import io._3ch.bbs.hash.PasswordHasher
import io._3ch.common.domain.account.*
import munit.FunSuite
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class AccountPasswordSuite extends FunSuite {
  private val now = 2000000000d
  private val aid = "abcdefghijklmnop"
  private val ip = "a" * 64
  private val other = "b" * 64
  private val profile = AccountProfile(aid, "user_1234", AccountKind.Password, now, None, now, "v1", loginId = Some("user_1234"))
  private val secret = "correct horse battery"

  /** PBKDF2 の実計算の回数を数える */
  private def counting[A](body: => Future[A]): Future[(A, Int)] = {
    val subtle = HandlerTestKit.global.crypto.subtle
    val original = subtle.deriveBits
    var calls = 0
    subtle.deriveBits = ((algorithm: js.Any, key: js.Any, length: js.Any) => {
      calls += 1
      original.call(subtle, algorithm, key, length)
    }): js.Function3[js.Any, js.Any, js.Any, js.Any]
    body.transform { result =>
      js.special.delete(subtle, "deriveBits")
      result.map(_ -> calls)
    }
  }

  private def prepared(env: DurableTestKit.Env): Future[Unit] = for {
    credential <- PasswordHasher.create(Password.parse(secret).toOption.get, now).map(_.toOption.get)
    created <- env.account(aid).create(AccountCodec.encodeProfile(profile), now, PasswordCredentialCodec.encode(credential)).toFuture
    _ = assertEquals(created.code.asInstanceOf[String], "")
    activated <- env.account(aid).activate(now).toFuture
    _ = assertEquals(activated.code.asInstanceOf[String], "")
  } yield ()

  private def stored(env: DurableTestKit.Env): Future[AccountState] =
    env.accountInstance(aid).state.storage.get(AccountCodec.stateKeys).toFuture.map(values => AccountCodec.decodeState(values).get)

  test("資格情報を伴う作成は確定まで照合を通さず、確定後は正しいパスワードで情報を返す") {
    val env = DurableTestKit.newEnv()
    for {
      credential <- PasswordHasher.create(Password.parse(secret).toOption.get, now).map(_.toOption.get)
      _ <- env.account(aid).create(AccountCodec.encodeProfile(profile), now, PasswordCredentialCodec.encode(credential)).toFuture
      pending <- env.account(aid).verifyPassword(secret, ip, now).toFuture
      _ = assertEquals(pending.code.asInstanceOf[String], AccountRejection.Pending.code)
      _ = assertEquals(pending.uid.asInstanceOf[String], "user_1234")
      wrongWhilePending <- env.account(aid).verifyPassword("wrong password", ip, now).toFuture
      _ = assertEquals(wrongWhilePending.code.asInstanceOf[String], AccountRejection.LoginFailed.code)
      _ = assert(js.isUndefined(wrongWhilePending.uid))
      _ <- env.account(aid).activate(now).toFuture
      verified <- env.account(aid).verifyPassword(secret, ip, now + 1).toFuture
      _ = assertEquals(verified.code.asInstanceOf[String], "")
      _ = assertEquals(verified.uid.asInstanceOf[String], "user_1234")
      again <- env.account(aid).activate(now + 2).toFuture
    } yield assertEquals(again.generation.asInstanceOf[Int], 0)
  }

  test("同時の失敗は直列に数え、十回目で接続元をロックして正しいパスワードも拒否する") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- prepared(env)
      results <- Future.sequence(Vector.fill(12)(env.account(aid).verifyPassword("wrong password", ip, now + 1).toFuture))
      _ = assert(results.forall(_.code.asInstanceOf[String] == AccountRejection.LoginFailed.code))
      state <- stored(env)
      _ = assertEquals(state.credential.get.throttle.sources.get(ip), Some(LoginSource(10, now + 1, Some(now + 1 + 3600))))
      locked <- env.account(aid).verifyPassword(secret, ip, now + 2).toFuture
      _ = assertEquals(locked.code.asInstanceOf[String], AccountRejection.LoginFailed.code)
      elsewhere <- env.account(aid).verifyPassword(secret, other, now + 2).toFuture
      _ = assertEquals(elsewhere.code.asInstanceOf[String], "")
      later <- env.account(aid).verifyPassword(secret, ip, now + 1 + 3600).toFuture
    } yield assertEquals(later.code.asInstanceOf[String], "")
  }

  test("成功はその接続元の失敗を消し、ロックでも存在しない ID でも PBKDF2 を一回計算する") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- prepared(env)
      _ <- Future.traverse(Vector(ip, ip, other))(key => env.account(aid).verifyPassword("wrong password", key, now + 1).toFuture)
      _ <- env.account(aid).verifyPassword(secret, ip, now + 2).toFuture
      state <- stored(env)
      _ = assertEquals(state.credential.get.throttle.sources.keySet, Set(other))
      (dummy, dummyCalls) <- counting(env.account("dummy").verifyPassword(secret, ip, now).toFuture)
      _ = assertEquals(dummy.code.asInstanceOf[String], AccountRejection.LoginFailed.code)
      _ = assertEquals(dummyCalls, 1)
      dummyState <- env.accountInstance("dummy").state.storage.get(AccountCodec.stateKeys).toFuture
      _ = assertEquals(dummyState.size, 0)
      _ <- Future.traverse((1 to 10).toVector)(i => env.account(aid).verifyPassword("wrong password", ip, now + 2 + i).toFuture)
      (_, lockedCalls) <- counting(env.account(aid).verifyPassword(secret, ip, now + 20).toFuture)
    } yield assertEquals(lockedCalls, 1)
  }

  test("パスワードの変更は世代を進め、古いパスワードを通さない") {
    val env = DurableTestKit.newEnv()
    for {
      _ <- prepared(env)
      next <- PasswordHasher.create(Password.parse("another secret value").toOption.get, now + 5).map(_.toOption.get)
      changed <- env.account(aid).setPassword(PasswordCredentialCodec.encode(next), now + 5).toFuture
      _ = assertEquals(changed.generation.asInstanceOf[Int], 1)
      old <- env.account(aid).verifyPassword(secret, ip, now + 6).toFuture
      _ = assertEquals(old.code.asInstanceOf[String], AccountRejection.LoginFailed.code)
      current <- env.account(aid).verifyPassword("another secret value", ip, now + 7).toFuture
      _ = assertEquals(current.code.asInstanceOf[String], "")
      client <- env.account("clientaccount001").create(AccountCodec.encodeProfile(AccountProfile("clientaccount001", "abcd1234", AccountKind.Client, now, None, now, "v1")), now).toFuture
      _ = assertEquals(client.code.asInstanceOf[String], "")
      refused <- env.account("clientaccount001").setPassword(PasswordCredentialCodec.encode(next), now + 5).toFuture
    } yield assertEquals(refused.code.asInstanceOf[String], AccountRejection.Invalid.code)
  }
}
