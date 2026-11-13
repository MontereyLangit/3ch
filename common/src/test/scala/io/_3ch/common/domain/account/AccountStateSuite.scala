package io._3ch.common.domain.account

import munit.FunSuite

class AccountStateSuite extends FunSuite {
  private val now = 2000000000d
  private val profile = AccountProfile("abcdefghijklmnop", "user1234", AccountKind.Client, now, None, now, "v1")
  private val passwordProfile = profile.copy(kind = AccountKind.Password, loginId = Some(profile.uid))
  private val credential = PasswordCredential(PasswordAlgorithm.Pbkdf2Sha256, 100000, Vector.fill(16)(1.toByte), Vector.fill(32)(2.toByte), now)
  private def account: AccountState = AccountState.create(profile, now).toOption.get

  test("クライアント ID は発行から一年で失効し成功した投稿だけが期限を延ばす") {
    val state = account
    val expiry = now + AccountProfile.clientLifetime
    assertEquals(state.profile.expiresAt, Some(expiry))
    assert(state.usable(expiry - 1).isRight)
    assertEquals(state.usable(expiry), Left(AccountRejection.Expired))
    val posted = state.recordPost(now + 100, AccountPost(now + 100), AccountObservation()).toOption.get
    assertEquals(posted.profile.expiresAt, Some(expiry + 100))
    assertEquals(posted.level.lvPosts, 1)
    assertEquals(state.recordPost(now + 100, AccountPost(now + 100, result = "rejected"), AccountObservation()), Right(state))
    assertEquals(state.recordPost(expiry, AccountPost(expiry), AccountObservation()), Left(AccountRejection.Expired))
  }

  test("初期 Lv は登録方式とメール認証で決まりクライアントには役割を付けない") {
    assertEquals(account.level.effective, 1)
    assertEquals(AccountState.create(profile.copy(kind = AccountKind.Google), now).toOption.get.level.effective, 10)
    assertEquals(AccountState.create(passwordProfile.copy(emailVerifiedAt = Some(now)), now).toOption.get.level.effective, 3)
    assertEquals(account.setRoles(Roles(Set(AccountRole.Admin))), Left(AccountRejection.RolesForbidden))
    assertEquals(AccountState.create(profile.copy(uid = "short"), now), Left(AccountRejection.Invalid))
    assertEquals(AccountState.create(profile.copy(agreedAt = now + 1), now), Left(AccountRejection.Invalid))
  }

  test("世代更新と状態変更は古いセッションを無効にする") {
    val state = account
    val advanced = state.bumpGeneration.toOption.get
    assertEquals(advanced.describe(profile.aid, 0, now), Left(AccountRejection.Generation))
    assert(advanced.describe(profile.aid, 1, now).isRight)
    Seq(AccountStatus.Frozen -> AccountRejection.Frozen, AccountStatus.Banned -> AccountRejection.Banned,
      AccountStatus.Deleted -> AccountRejection.Deleted).foreach { case (status, rejection) =>
      val stopped = state.setStatus(status).toOption.get
      assertEquals(stopped.profile.generation, 1)
      assertEquals(stopped.describe(profile.aid, 1, now), Left(rejection))
    }
    assertEquals(state.copy(profile = profile.copy(generation = Int.MaxValue)).bumpGeneration, Left(AccountRejection.Invalid))
    assertEquals(state.bumpGeneration(Double.NaN), Left(AccountRejection.Invalid))
  }

  test("同意版を更新してから投稿できる状態を返す") {
    assert(account.agreementRequired("v2"))
    val agreed = account.agree("v2", now + 1).toOption.get
    assert(!agreed.agreementRequired("v2"))
    assertEquals(agreed.description.agreedAt, now + 1)
    assertEquals(account.agree("", now), Left(AccountRejection.Invalid))
  }

  test("端末を十件まで持ち再観測では初回時刻を保つ") {
    def hash(i: Int): String = "p" + f"$i%018x"
    val state = (0 until 11).foldLeft(account) { (state, i) =>
      state.bindDevice(hash(i), "a" * 64, now + i).toOption.get
    }
    assertEquals(state.devices.size, 10)
    assert(!state.devices.exists(_.hash == hash(0)))
    val observed = state.bindDevice(hash(1), "b" * 64, now + 20).toOption.get.devices.head
    assertEquals((observed.firstSeen, observed.lastSeen, observed.keyIpHash), (now + 1, now + 20, "b" * 64))
  }

  test("キャップは役割保持者の一個か管理者発行に限る") {
    val state = AccountState.create(passwordProfile, now).toOption.get.copy(credential = Some(credential), readiness = AccountReadiness.Ready)
      .setRoles(Roles(Set(AccountRole.Cap))).toOption.get
    val cap = AccountCap("cap1", "表示名", "legacy-hash", false, profile.aid, now)
    val bound = state.bindCap(cap, false, now).toOption.get
    assertEquals(bound.describeForCap(profile.aid, cap.hash, now).toOption.get._2, cap)
    assertEquals(bound.bindCap(cap.copy(capId = "cap2", hash = "other"), false, now), Left(AccountRejection.RolesForbidden))
    assertEquals(state.bindCap(cap.copy(strong = true), false, now), Left(AccountRejection.RolesForbidden))
    assert(state.bindCap(cap.copy(strong = true), true, now).isRight)
    val revoked = bound.revokeCap(cap.capId, now + 1).toOption.get
    assertEquals(revoked.describeForCap(profile.aid, cap.hash, now + 1), Left(AccountRejection.CapRevoked))
  }

  test("ログは六十四件ごとにページを替え十六ページで古い枠を再利用する") {
    val position = AccountLogPosition(1024)
    val old = AccountLogPage(0, Vector(AccountPost(now, excerpt = "古い記録")))
    val (next, page) = position.append(old, AccountPost(now + 1, excerpt = "あ" * 50))
    assertEquals(position.pageSlot, 0)
    assertEquals(next.next, 1025L)
    assertEquals(page.number, 16L)
    assertEquals(page.entries.map(_.excerpt), Vector("あ" * 40))
  }

  test("パスワード方式は準備中で作成し資格情報を置いただけでは認証を許可しない") {
    val pending = AccountState.create(passwordProfile, now).toOption.get
    assertEquals(pending.profile.status, AccountStatus.Active)
    assertEquals(pending.readiness, AccountReadiness.Pending)
    assertEquals(pending.credential, None)
    assert(pending.valid)
    assertEquals(pending.usable(now), Left(AccountRejection.Pending))
    val prepared = pending.copy(credential = Some(credential))
    assert(prepared.valid)
    assertEquals(prepared.usable(now), Left(AccountRejection.Pending))
    assertEquals(account.readiness, AccountReadiness.Ready)
  }

  test("準備中は投稿用 Cookie・キャップ・専ブラキーと投稿記録を受け付けない") {
    val cap = AccountCap("cap1", "表示名", "legacy-hash", false, profile.aid, now)
    val token = DedicatedBrowserToken("token1", "a" * 64, now, now + 86400, Some("b" * 64), Some("専ブラ"), Some(now))
    val pending = AccountState.create(passwordProfile, now).toOption.get.copy(credential = Some(credential), caps = Vector(cap), tokens = Vector(token))
    assertEquals(pending.describe(profile.aid, 0, now), Left(AccountRejection.Pending))
    assertEquals(pending.describe(profile.aid, 0, now, Some(token.tokenId), token.uaHash), Left(AccountRejection.Pending))
    assertEquals(pending.describeForCap(profile.aid, cap.hash, now), Left(AccountRejection.Pending))
    assertEquals(pending.issueToken("token2", "c" * 64, now), Left(AccountRejection.Pending))
    assertEquals(pending.useToken("token1", "b" * 64, "専ブラ", now), (pending, Left(AccountRejection.Pending)))
    assertEquals(pending.revokeToken("token1", now), Left(AccountRejection.Pending))
    assertEquals(pending.bindCap(cap.copy(capId = "cap2"), true, now), Left(AccountRejection.Pending))
    assertEquals(pending.revokeCap(cap.capId, now), Left(AccountRejection.Pending))
    assertEquals(pending.bindDevice("p" + "a" * 18, "b" * 64, now), Left(AccountRejection.Pending))
    assertEquals(pending.agree("v2", now), Left(AccountRejection.Pending))
    assertEquals(pending.recordPost(now, AccountPost(now), AccountObservation()), Left(AccountRejection.Pending))
  }

  test("利用可能なパスワード方式は正しい資格情報と同じ正規化済み UID・ログイン ID を必須にする") {
    val pending = AccountState.create(passwordProfile, now).toOption.get
    val ready = pending.copy(credential = Some(credential), readiness = AccountReadiness.Ready)
    assert(ready.valid)
    assert(ready.describe(profile.aid, 0, now).isRight)
    Seq(ready.copy(credential = None), ready.copy(credential = Some(credential.copy(iterations = 0))),
      ready.copy(profile = passwordProfile.copy(loginId = None)), ready.copy(profile = passwordProfile.copy(loginId = Some("otherid"))),
      ready.copy(profile = passwordProfile.copy(loginId = Some("USER1234"))), ready.copy(credential = null), ready.copy(readiness = null),
      ready.copy(credential = Some(null))).foreach { state =>
      assert(!state.valid)
      assertEquals(state.usable(now), Left(AccountRejection.Invalid))
    }
    assert(!account.copy(credential = Some(credential)).valid)
    assert(!AccountState.create(profile.copy(kind = AccountKind.Google), now).toOption.get.copy(credential = Some(credential)).valid)
    assertEquals(AccountState.create(profile.copy(kind = AccountKind.Password), now), Left(AccountRejection.Invalid))
  }
}
