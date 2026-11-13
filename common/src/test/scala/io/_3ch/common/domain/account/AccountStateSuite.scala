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
    val revoked = bound.revokeCap(cap.capId, now + 1).toOption.get._1
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

  private val hash = "a" * 64
  private def passwordAccount: AccountState = AccountState.create(passwordProfile, now, Some(credential)).flatMap(_.activate(now)).toOption.get

  test("メール認証のトークンは期限内に一度だけ使え、アドレスを確定させて Lv を 3 未満なら 3 にする") {
    val issued = passwordAccount.issuePending(PendingKind.Email, hash, Some("taro@example.com"), now).toOption.get
    assertEquals(issued.pendingFor(PendingKind.Email, hash, now + 86399).map(_.email), Right(Some("taro@example.com")))
    assertEquals(issued.pendingFor(PendingKind.Email, hash, now + 86400), Left(AccountRejection.PendingInvalid))
    assertEquals(issued.pendingFor(PendingKind.Email, "b" * 64, now), Left(AccountRejection.PendingInvalid))
    val (verified, previous) = issued.verifyEmail(hash, now + 10).toOption.get
    assertEquals((verified.profile.email, verified.profile.emailVerifiedAt, previous), (Some("taro@example.com"), Some(now + 10), None))
    assertEquals((verified.level.lv, verified.level.lvSince, verified.level.lvPosts), (3, now + 10, 0))
    assertEquals(verified.verifyEmail(hash, now + 11), Left(AccountRejection.PendingInvalid))
    val higher = passwordAccount.copy(level = AccountLevel(5, now, 7)).issuePending(PendingKind.Email, hash, Some("x@example.com"), now).toOption.get
    val (changed, _) = higher.copy(profile = higher.profile.copy(email = Some("old@example.com"))).verifyEmail(hash, now).toOption.get
    assertEquals(changed.level, AccountLevel(5, now, 7))
    assertEquals(higher.copy(profile = higher.profile.copy(email = Some("old@example.com"))).verifyEmail(hash, now).map(_._2), Right(Some("old@example.com")))
  }

  test("保留トークンは種別ごとに別に持ち、クライアント ID と Google には発行しない") {
    val verified = passwordAccount.copy(profile = passwordAccount.profile.copy(email = Some("t@example.com"), emailVerifiedAt = Some(now)))
    val both = verified.issuePending(PendingKind.Email, hash, Some("n@example.com"), now).flatMap(_.issuePending(PendingKind.Reset, "b" * 64, None, now)).toOption.get
    assert(both.pendingFor(PendingKind.Email, hash, now).isRight)
    assert(both.pendingFor(PendingKind.Reset, "b" * 64, now + 3599).isRight)
    assertEquals(both.pendingFor(PendingKind.Reset, "b" * 64, now + 3600), Left(AccountRejection.PendingInvalid))
    assertEquals(account.issuePending(PendingKind.Email, hash, Some("t@example.com"), now), Left(AccountRejection.KindForbidden))
    assertEquals(passwordAccount.issuePending(PendingKind.Email, hash, None, now), Left(AccountRejection.Invalid))
    assertEquals(passwordAccount.issuePending(PendingKind.Reset, hash, Some("t@example.com"), now), Left(AccountRejection.Invalid))
  }

  test("再設定はメール認証済みで役割の無いアカウントだけで、消費するとパスワードを替えて世代を進める") {
    val verified = passwordAccount.copy(profile = passwordAccount.profile.copy(email = Some("t@example.com"), emailVerifiedAt = Some(now)))
    assertEquals(passwordAccount.issuePending(PendingKind.Reset, hash, None, now), Left(AccountRejection.KindForbidden))
    assertEquals(verified.copy(roles = Roles(Set(AccountRole.Cap))).issuePending(PendingKind.Reset, hash, None, now), Left(AccountRejection.KindForbidden))
    val issued = verified.issuePending(PendingKind.Reset, hash, None, now).toOption.get
    val next = credential.copy(hash = Vector.fill(32)(3.toByte), updatedAt = now + 1)
    val reset = issued.resetPassword(hash, next, now + 1).toOption.get
    assertEquals((reset.credential.map(_.hash), reset.profile.generation, reset.pending.get(PendingKind.Reset)), (Some(next.hash), 1, None))
    assertEquals(issued.copy(roles = Roles(Set(AccountRole.Delete))).resetPassword(hash, next, now + 1), Left(AccountRejection.KindForbidden))
  }

  test("クライアント ID だけを移行でき、Lv と書き込み数を引き継いで期限を外し世代を進める。Google は Lv10") {
    val client = account.copy(level = AccountLevel(4, now - 100, 9))
    val moved = client.convert(AccountConversion.ToPassword(credential), "taro_2026", 0, now).toOption.get
    assertEquals((moved.profile.kind, moved.profile.uid, moved.profile.loginId, moved.profile.expiresAt, moved.profile.generation),
      (AccountKind.Password, "taro_2026", Some("taro_2026"), None, 1))
    assertEquals((moved.profile.aid, moved.level), (client.profile.aid, client.level))
    val google = client.convert(AccountConversion.ToGoogle("c" * 64, Some("t@example.com")), "taro_2026", 0, now).toOption.get
    assertEquals((google.profile.kind, google.level.lv, google.profile.googleSubHash, google.profile.emailVerifiedAt), (AccountKind.Google, 10, Some("c" * 64), Some(now)))
    assertEquals(client.convert(AccountConversion.ToPassword(credential), "taro_2026", 1, now), Left(AccountRejection.Generation))
    assertEquals(moved.convert(AccountConversion.ToGoogle("c" * 64, None), "taro_2026", 1, now), Left(AccountRejection.KindForbidden))
    assertEquals(client.convert(AccountConversion.ToPassword(credential), "x", 0, now), Left(AccountRejection.Invalid))
    assertEquals(client.convert(AccountConversion.ToGoogle("short", None), "taro_2026", 0, now), Left(AccountRejection.Invalid))
  }

  test("退会は deleted にして世代を進め、キャップとキーを失効させて外す値を返す") {
    val cap = AccountCap("cap1", "管理人", "kvkey", strong = false, profile.aid, now)
    val state = passwordAccount.copy(profile = passwordAccount.profile.copy(email = Some("t@example.com")), caps = Vector(cap), roles = Roles(Set(AccountRole.Cap)))
    val (deleted, withdrawal) = state.withdraw(0, now + 1).toOption.get
    assertEquals((deleted.profile.status, deleted.profile.generation), (AccountStatus.Deleted, 1))
    assertEquals(deleted.caps.map(_.revokedAt), Vector(Some(now + 1)))
    assertEquals(withdrawal, Withdrawal(Some("user1234"), Some("t@example.com"), None, Vector(cap.copy(revokedAt = Some(now + 1)))))
    assertEquals(deleted.usable(now + 1), Left(AccountRejection.Deleted))
    assertEquals(deleted.withdraw(1, now + 2), Left(AccountRejection.Deleted))
    assertEquals(state.withdraw(1, now), Left(AccountRejection.Generation))
  }

  private def verifiedAccount: AccountState =
    passwordAccount.copy(profile = passwordAccount.profile.copy(email = Some("t@example.com"), emailVerifiedAt = Some(now)))
  private def withBothPending: AccountState =
    verifiedAccount.issuePending(PendingKind.Email, hash, Some("n@example.com"), now).flatMap(_.issuePending(PendingKind.Reset, "b" * 64, None, now)).toOption.get
  private val next = credential.copy(hash = Vector.fill(32)(3.toByte), updatedAt = now + 1)

  test("パスワードの変更・再設定・メールの確認・移行は保留中のトークンを全て捨てる") {
    val both = withBothPending
    assertEquals(both.setPassword(next, now + 1).map(_.pending), Right(Map.empty))
    assertEquals(both.resetPassword("b" * 64, next, now + 1).map(_.pending), Right(Map.empty))
    assertEquals(both.verifyEmail(hash, now + 1).map(_._1.pending), Right(Map.empty))
    val client = account.copy(pending = Map(PendingKind.Email -> PendingToken(hash, now + 100, Some("t@example.com"))))
    assertEquals(client.convert(AccountConversion.ToPassword(credential), "taro_2026", 0, now).map(_.pending), Right(Map.empty))
  }

  test("保留トークンは発行時の世代と違えば受け付けない") {
    val both = withBothPending
    assertEquals(both.pending.values.map(_.generation).toSet, Set(0))
    val bumped = both.bumpGeneration.toOption.get
    assertEquals(bumped.pendingFor(PendingKind.Email, hash, now), Left(AccountRejection.PendingInvalid))
    assertEquals(bumped.pendingFor(PendingKind.Reset, "b" * 64, now), Left(AccountRejection.PendingInvalid))
    assertEquals(bumped.verifyEmail(hash, now), Left(AccountRejection.PendingInvalid))
    val reissued = bumped.issuePending(PendingKind.Email, hash, Some("n@example.com"), now).toOption.get
    assertEquals(reissued.pendingFor(PendingKind.Email, hash, now).map(_.generation), Right(1))
  }

  test("パスワードの再設定は専ブラキーを全て無効にするが、通常の変更は残す") {
    val token = DedicatedBrowserToken("token1", "a" * 64, now, now + 86400, Some("b" * 64), Some("専ブラ"), Some(now))
    val state = withBothPending.copy(tokens = Vector(token))
    val reset = state.resetPassword("b" * 64, next, now + 1).toOption.get
    assertEquals(reset.tokens.map(t => (t.revokedAt, t.revokeReason)), Vector((Some(now + 1), Some(TokenRevokeReason.Revoked))))
    assertEquals(reset.useToken("token1", "b" * 64, "専ブラ", now + 2)._2.isLeft, true)
    assertEquals(state.setPassword(next, now + 1).map(_.tokens), Right(Vector(token)))
  }

  test("退会は同じセッションの世代なら何度でも同じ結果を返して後始末をやり直せ、ほかの世代は拒否する") {
    val cap = AccountCap("cap1", "管理人", "kvkey", strong = false, profile.aid, now)
    val state = passwordAccount.copy(caps = Vector(cap), roles = Roles(Set(AccountRole.Cap)))
    val (deleted, first) = state.withdraw(0, now + 1).toOption.get
    assertEquals(deleted.withdraw(0, now + 5), Right((deleted, first)))
    assertEquals(deleted.withdraw(2, now + 5), Left(AccountRejection.Deleted))
    assertEquals(deleted.withdraw(0, Double.NaN), Left(AccountRejection.Invalid))
  }

  test("索引の値を今も持つのは退会していないアカウントだけで、変更前の値は持たない") {
    val google = AccountState.create(profile.copy(kind = AccountKind.Google, googleSubHash = Some("c" * 64)), now).flatMap(_.activate(now)).toOption.get
    assert(verifiedAccount.holds(AccountIndexKind.Login, "user1234"))
    assert(!verifiedAccount.holds(AccountIndexKind.Login, "other"))
    assert(verifiedAccount.holds(AccountIndexKind.Email, "t@example.com"))
    assert(!verifiedAccount.holds(AccountIndexKind.Email, "old@example.com"))
    assert(!passwordAccount.holds(AccountIndexKind.Email, "t@example.com"), "未確認のアドレスは持っていない")
    assert(google.holds(AccountIndexKind.Google, "c" * 64) && !google.holds(AccountIndexKind.Google, "d" * 64))
    assert(!verifiedAccount.withdraw(0, now + 1).toOption.get._1.holds(AccountIndexKind.Login, "user1234"))
    assert(!verifiedAccount.holds(AccountIndexKind.Uid, "user1234"))
  }
}
