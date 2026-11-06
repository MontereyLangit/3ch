package io._3ch.common.domain.account

import munit.FunSuite

class PasswordLoginSuite extends FunSuite {
  private val now = 2000000000d
  private val profile = AccountProfile("abcdefghijklmnop", "user1234", AccountKind.Password, now, None, now, "v1", loginId = Some("user1234"))
  private val credential = PasswordCredential(PasswordAlgorithm.Pbkdf2Sha256, 100000, Vector.fill(16)(1.toByte), Vector.fill(32)(2.toByte), now)
  private val ip = "a" * 64
  private val other = "b" * 64
  private def ready: AccountState = AccountState.create(profile, now, Some(credential)).flatMap(_.activate(now)).toOption.get

  private def fail(state: AccountState, keyIp: String, times: Int): AccountState =
    (1 to times).foldLeft(state)((s, i) => s.afterPasswordAttempt(keyIp, matched = false, now + i).toOption.get._1)

  test("資格情報と一緒に作成し、確定するまで認証に使わない") {
    val pending = AccountState.create(profile, now, Some(credential)).toOption.get
    assertEquals(pending.readiness, AccountReadiness.Pending)
    assertEquals(pending.usable(now), Left(AccountRejection.Pending))
    assertEquals(ready.usable(now), Right(()))
    assertEquals(ready.activate(now + 1), Right(ready))
    assertEquals(AccountState.create(profile, now).toOption.get.activate(now), Left(AccountRejection.Invalid))
    assertEquals(AccountState.create(profile, now, Some(credential.copy(updatedAt = now + 1))), Left(AccountRejection.Invalid))
    assertEquals(AccountState.create(profile.copy(kind = AccountKind.Client, loginId = None, uid = "abcd1234"), now, Some(credential)), Left(AccountRejection.Invalid))
  }

  test("一致すればその接続元の失敗を消し、不一致はアカウント全体の五回目から遅延を返す") {
    val failed = fail(ready, ip, 4)
    val (fifth, attempt) = failed.afterPasswordAttempt(ip, matched = false, now + 5).toOption.get
    assertEquals(attempt, PasswordAttempt.Failed(1))
    val (success, accepted) = fifth.afterPasswordAttempt(ip, matched = true, now + 6).toOption.get
    assertEquals(accepted, PasswordAttempt.Accepted)
    assertEquals(success.credential.get.throttle.failures, Vector.empty)
    assertEquals(success.credential.get.hash, credential.hash)
  }

  test("十回目の失敗で接続元をロックし、ロック中は正しいパスワードでも失敗にする") {
    val locked = fail(ready, ip, 10)
    assertEquals(locked.credential.get.throttle.locks.get(ip), Some(now + 10 + 3600))
    val (after, attempt) = locked.afterPasswordAttempt(ip, matched = true, now + 11).toOption.get
    assertEquals(attempt, PasswordAttempt.Failed(6))
    assertEquals(after, locked)
    val (elsewhere, accepted) = locked.afterPasswordAttempt(other, matched = true, now + 11).toOption.get
    assertEquals(accepted, PasswordAttempt.Accepted)
    assertEquals(elsewhere.credential.get.throttle.locks.keySet, Set(ip))
  }

  test("パスワード方式以外や不正な接続元では照合しない") {
    val client = AccountState.create(profile.copy(kind = AccountKind.Client, loginId = None, uid = "abcd1234"), now).toOption.get
    assertEquals(client.passwordCheck(ip, now), Left(AccountRejection.Invalid))
    assertEquals(ready.passwordCheck("short", now), Left(AccountRejection.Invalid))
    assertEquals(ready.passwordCheck(ip, now + 0.5), Left(AccountRejection.Invalid))
  }

  test("パスワードの変更は世代を進めて失敗計数を消し、再計算は計数を保つ") {
    val failed = fail(ready, ip, 3)
    val next = credential.copy(salt = Vector.fill(16)(3.toByte), updatedAt = now + 10)
    val changed = failed.setPassword(next, now + 10).toOption.get
    assertEquals(changed.profile.generation, 1)
    assertEquals(changed.credential.get.salt, next.salt)
    assertEquals(changed.credential.get.throttle, LoginThrottle())
    assertEquals(failed.setPassword(next, now + 9), Left(AccountRejection.Invalid))
    val rehashed = failed.rehashed(next).toOption.get
    assertEquals(rehashed.credential.get.throttle, failed.credential.get.throttle)
    assertEquals(failed.rehashed(next.copy(iterations = 1000)), Left(AccountRejection.Invalid))
  }

  test("Google は sub の HMAC を持って確定し、一致したときだけ情報を返す") {
    val google = profile.copy(kind = AccountKind.Google, loginId = None, googleSubHash = Some("c" * 64), email = Some("user@example.com"), emailVerifiedAt = Some(now))
    val pending = AccountState.create(google, now).toOption.get
    assertEquals(pending.describeGoogle("c" * 64, now), Left(AccountRejection.Pending))
    val active = pending.activate(now).toOption.get
    assertEquals(active.level.effective, 10)
    assertEquals(active.describeGoogle("c" * 64, now).map(_.uid), Right("user1234"))
    assertEquals(active.describeGoogle("d" * 64, now), Left(AccountRejection.Invalid))
    assertEquals(AccountState.create(google.copy(googleSubHash = None), now).toOption.get.activate(now), Left(AccountRejection.Invalid))
    assertEquals(ready.describeGoogle("c" * 64, now), Left(AccountRejection.Invalid))
  }

  test("存在しない ID の照合に使う固定の資格情報は保存できる形を保つ") {
    assert(PasswordCredential.placeholder.valid)
    assert(!PasswordCredential.placeholder.needsRehash)
  }
}
