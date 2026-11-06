package io._3ch.common.domain.account

/** 認証後に HTTP 層へ返す情報。資格情報とキーのハッシュは含めない */
final case class AccountDescription(aid: String, uid: String, kind: AccountKind, generation: Int, expiresAt: Option[Double],
  lv: Int, roles: Roles, agreedAt: Double, agreementVersion: String)

/** アカウントに結び付いた端末の初回・最終観測 */
final case class AccountDevice(hash: String, firstSeen: Double, lastSeen: Double, keyIpHash: String) {
  def valid: Boolean = hash.matches("[pgu](?:[0-9a-f]{18}|[0-9a-f]{16})") && DedicatedBrowserTokens.validHash(keyIpHash) &&
    AccountProfile.validTime(firstSeen) && AccountProfile.validTime(lastSeen) && lastSeen >= firstSeen
}

/** 書き込み・認証キー照合の経路 */
enum AccountVia(val code: String) {
  case Web extends AccountVia("web")
  case Token extends AccountVia("token")
  case Cap extends AccountVia("cap")
}
object AccountVia {
  def parse(code: String): Option[AccountVia] = AccountVia.values.find(_.code == code)
}

/** アカウントに結び付く書き込み結果。本文の抜粋は保存前に長さを制限する */
final case class AccountPost(
  t: Double,
  via: AccountVia = AccountVia.Web,
  tokenId: Option[String] = None,
  capId: Option[String] = None,
  result: String = "posted",
  bbs: String = "",
  key: String = "",
  title: String = "",
  resNum: Int = 0,
  excerpt: String = "",
  asn: String = "",
  asName: String = "",
  country: String = "",
  region: String = "",
  uaLabel: String = "",
  deviceHash: Option[String] = None,
  id: String = "",
  shikibetsu: String = "",
  network: String = ""
) {
  def shortened: AccountPost = copy(excerpt = AccountPost.excerptOf(excerpt))
  def tokenPost: TokenPost = TokenPost(t, bbs, key, title, resNum, AccountPost.excerptOf(excerpt), network)
}
object AccountPost {
  val excerptLength: Int = 40

  /** 本文の改行タグを空白にして四十文字以内に切る。HTML の文字参照の途中では切らない */
  def excerptOf(message: String): String = {
    val text = message.replace("<br>", " ")
    if (text.length <= excerptLength) text
    else {
      val cut = text.take(excerptLength)
      val amp = cut.lastIndexOf('&')
      if (amp >= 0 && cut.indexOf(';', amp) < 0 && text.indexOf(';', amp) > 0 && text.indexOf(';', amp) - amp <= 10) cut.take(amp) else cut
    }
  }
}

/** 専ブラキーの照合で拒否した記録。ログ本体とは別に新しい順で上限まで残し、漏れたキーの試行で書き込み記録を押し出さない */
object AccountRejectionLog {
  val limit: Int = 20

  def append(entries: Vector[AccountPost], entry: AccountPost): Vector[AccountPost] = (entry.shortened +: entries).take(limit)
}

/** 書き込み時に得た端末の情報。ハッシュの生成はアプリケーション層で行う */
final case class AccountObservation(deviceHash: String = "", keyIpHash: String = "")

/** パスワード照合の結果。失敗にはアカウント全体の失敗数による応答の遅延を付ける */
enum PasswordAttempt {
  case Accepted
  case Failed(delaySeconds: Int)
}

/** アカウントの状態遷移。現在時刻は呼び出し元から UNIX 秒で受ける */
final case class AccountState(
  profile: AccountProfile,
  level: AccountLevel,
  roles: Roles = Roles(),
  tokens: Vector[DedicatedBrowserToken] = Vector.empty,
  devices: Vector[AccountDevice] = Vector.empty,
  caps: Vector[AccountCap] = Vector.empty,
  credential: Option[PasswordCredential] = None,
  readiness: AccountReadiness = AccountReadiness.Ready
) {
  def valid: Boolean = profile.valid && level.valid && roles.permitted(profile.kind) &&
    tokens.forall(_.valid) && tokens.map(_.tokenId).distinct.size == tokens.size && tokens.map(_.hash).distinct.size == tokens.size &&
    devices.size <= 10 && devices.forall(_.valid) && devices.map(_.hash).distinct.size == devices.size &&
    caps.forall(_.valid) && caps.map(_.capId).distinct.size == caps.size && caps.map(_.hash).distinct.size == caps.size &&
    readiness != null && credential != null && (profile.kind match {
      case AccountKind.Password => credential.forall(value => value != null && value.valid) &&
        (readiness == AccountReadiness.Pending || credential.isDefined)
      case AccountKind.Google => credential.isEmpty &&
        (readiness == AccountReadiness.Pending || profile.googleSubHash.exists(DedicatedBrowserTokens.validHash))
      case AccountKind.Client => credential.isEmpty
    })

  def description: AccountDescription = AccountDescription(profile.aid, profile.uid, profile.kind, profile.generation,
    profile.expiresAt, level.effective, roles, profile.agreedAt, profile.agreementVersion)

  /** 不正な状態・登録準備中・凍結・停止・退会とクライアント ID の期限切れを拒否する */
  def usable(now: Double): Either[AccountRejection, Unit] = {
    if (!AccountProfile.validTime(now) || !valid) Left(AccountRejection.Invalid)
    else if (readiness == AccountReadiness.Pending) Left(AccountRejection.Pending)
    else profile.status match {
      case AccountStatus.Frozen => Left(AccountRejection.Frozen)
      case AccountStatus.Banned => Left(AccountRejection.Banned)
      case AccountStatus.Deleted => Left(AccountRejection.Deleted)
      case AccountStatus.Active if profile.expired(now) => Left(AccountRejection.Expired)
      case AccountStatus.Active => Right(())
    }
  }

  /** 投稿時の世代を照合する。キー由来なら有効性と完全な UA ハッシュも確認する */
  def describe(aid: String, generation: Int, now: Double, tokenId: Option[String] = None, uaHash: Option[String] = None): Either[AccountRejection, AccountDescription] = {
    if (aid != profile.aid || generation != profile.generation) Left(AccountRejection.Generation)
    else for {
      _ <- usable(now)
      _ <- tokenId.fold[Either[AccountRejection, Unit]](Right(()))(id =>
        DedicatedBrowserTokens.describe(tokens, id, uaHash.getOrElse(""), now).map(_ => ()))
    } yield description
  }

  /** 所有アカウントとキャップが利用できる場合だけ投稿用の情報を返す。自分で発行したキャップは役割を全て外すと使えない */
  def describeForCap(aid: String, hash: String, now: Double): Either[AccountRejection, (AccountDescription, AccountCap)] = {
    if (aid != profile.aid) Left(AccountRejection.Missing)
    else usable(now).flatMap { _ =>
      caps.find(cap => cap.hash == hash && cap.active && (cap.issuedBy != profile.aid || roles.values.nonEmpty))
        .toRight(AccountRejection.CapRevoked).map(cap => (description, cap))
    }
  }

  /** 権限保持者は自分宛に一個まで登録でき、強キャップと他人宛の発行は管理者に限る。クライアント ID には登録しない */
  def bindCap(cap: AccountCap, issuerIsAdmin: Boolean, now: Double): Either[AccountRejection, AccountState] = usable(now).flatMap { _ =>
    if (!cap.valid || cap.issuedAt > now || cap.revokedAt.isDefined || CapName.parse(cap.name) != Right(cap.name)) Left(AccountRejection.Invalid)
    else if (profile.kind == AccountKind.Client) Left(AccountRejection.RolesForbidden)
    else if (caps.exists(c => c.capId == cap.capId || c.hash == cap.hash)) Left(AccountRejection.Exists)
    else if (!issuerIsAdmin && (cap.issuedBy != profile.aid || roles.values.isEmpty || cap.strong || caps.exists(_.active))) Left(AccountRejection.RolesForbidden)
    else Right(copy(caps = caps :+ cap))
  }

  /** キャップの記録を残して失効させ、失効させたキャップを返す。KV と索引の削除は呼び出し元で行う */
  def revokeCap(capId: String, now: Double): Either[AccountRejection, (AccountState, AccountCap)] = usable(now).flatMap { _ =>
    caps.find(_.capId == capId).toRight(AccountRejection.CapRevoked).map { found =>
      val revoked = if (found.active) found.copy(revokedAt = Some(now)) else found
      val updated = caps.map(cap => if (cap.capId == capId) revoked else cap)
      val kept = updated.filterNot(_.active).sortBy(cap => -cap.revokedAt.getOrElse(0d)).take(AccountState.revokedCapLimit).map(_.capId).toSet
      (copy(caps = updated.filter(cap => cap.active || kept.contains(cap.capId))), revoked)
    }
  }

  /** 有効なキャップを失効させ、同じ表示名・強さで照合値だけ新しいキャップを同じ保存で登録する。失効させたものと新しいものを返す */
  def reissueCap(capId: String, nextId: String, nextHash: String, issuedBy: String, issuerIsAdmin: Boolean,
    now: Double): Either[AccountRejection, (AccountState, AccountCap, AccountCap)] =
    caps.find(cap => cap.capId == capId && cap.active).toRight(AccountRejection.CapRevoked).flatMap { previous =>
      val next = AccountCap(nextId, previous.name, nextHash, previous.strong, issuedBy, now)
      revokeCap(capId, now).flatMap { case (revoked, old) => revoked.bindCap(next, issuerIsAdmin, now).map(state => (state, old, next)) }
    }

  /** 古い順の書き込み記録から、キャップごとの直近の書き込みを新しい順に返す */
  def capPosts(log: Vector[AccountPost], limit: Int = 5): Map[String, Vector[AccountPost]] =
    log.filter(post => post.via == AccountVia.Cap && post.result == "posted").groupBy(_.capId.getOrElse(""))
      .collect { case (id, posts) if caps.exists(_.capId == id) => id -> posts.reverse.take(limit) }

  def agreementRequired(version: String): Boolean = version.isEmpty || profile.agreementVersion != version

  /** 利用可能なアカウントの同意版と時刻を更新する。空の版は受け付けない */
  def agree(version: String, now: Double): Either[AccountRejection, AccountState] = usable(now).flatMap { _ =>
    if (version.isEmpty) Left(AccountRejection.Invalid)
    else Right(copy(profile = profile.copy(agreementVersion = version, agreedAt = now)))
  }

  /** 全端末の既存セッションを失効させる。世代の上限では更新を拒否する */
  def bumpGeneration: Either[AccountRejection, AccountState] =
    if (profile.generation == Int.MaxValue) Left(AccountRejection.Invalid)
    else Right(copy(profile = profile.copy(generation = profile.generation + 1)))

  /** RPC から受けた UNIX 秒を確認して世代を進める */
  def bumpGeneration(now: Double): Either[AccountRejection, AccountState] =
    if (!AccountProfile.validTime(now)) Left(AccountRejection.Invalid) else bumpGeneration

  /** 管理者が Lv を固定する。None で固定を外して投稿による昇格に戻す */
  def setLevelOverride(value: Option[Int], now: Double): Either[AccountRejection, AccountState] =
    if (!AccountProfile.validTime(now) || !valid) Left(AccountRejection.Invalid)
    else level.overridden(value).toRight(AccountRejection.Invalid).map(next => copy(level = next))

  /** 状態が変わったときだけ世代も進め、既存セッションを失効させる */
  def setStatus(status: AccountStatus): Either[AccountRejection, AccountState] =
    if (profile.status == status) Right(this) else bumpGeneration.map(s => s.copy(profile = s.profile.copy(status = status)))

  /** 役割を置き換える。クライアント ID への役割付与は拒否する */
  def setRoles(next: Roles): Either[AccountRejection, AccountState] =
    if (!next.permitted(profile.kind)) Left(AccountRejection.RolesForbidden) else Right(copy(roles = next))

  /** actor の権限で役割を置き換える。変えられるのは admin だけで、上位の役割を持つ相手は操作できない */
  def assignRoles(next: Roles, actor: Roles, now: Double): Either[AccountRejection, AccountState] =
    if (!AccountProfile.validTime(now) || !valid) Left(AccountRejection.Invalid)
    else if (!actor.admin || !actor.manages(roles)) Left(AccountRejection.Forbidden)
    else setRoles(next)

  /** 初回の管理者として admin を足す。role:admin 索引で一人に絞ってから呼ぶ */
  def grantFirstAdmin(now: Double): Either[AccountRejection, AccountState] =
    usable(now).flatMap(_ => setRoles(Roles(roles.values + AccountRole.Admin)))

  /** 利用できる admin か。初回の管理者の確保で、有効な管理者を数えるのに使う */
  def activeAdmin(now: Double): Boolean = roles.admin && usable(now).isRight

  /** 初回観測を保って端末を更新し、最終観測が新しい十件を残す。空の端末は記録しない */
  def bindDevice(hash: String, keyIpHash: String, now: Double): Either[AccountRejection, AccountState] = usable(now).flatMap { _ =>
    if (hash.isEmpty) Right(this)
    else if (!hash.matches("[pgu](?:[0-9a-f]{18}|[0-9a-f]{16})") || !DedicatedBrowserTokens.validHash(keyIpHash)) Left(AccountRejection.Invalid)
    else {
      val first = devices.find(_.hash == hash).fold(now)(_.firstSeen)
      Right(copy(devices = (AccountDevice(hash, first, now, keyIpHash) +: devices.filterNot(_.hash == hash)).sortBy(d => -d.lastSeen).take(10)))
    }
  }

  /** 未使用キーを一個発行し、前の未使用キーを無効にする。平文は保持しない */
  def issueToken(tokenId: String, hash: String, now: Double): Either[AccountRejection, AccountState] =
    usable(now).flatMap(_ => DedicatedBrowserTokens.issue(tokens, tokenId, hash, now).map(ts => copy(tokens = ts)))

  /** 初回の UA 紐付けと照合を行う。拒否時も期限切れの整理結果を返す */
  def useToken(tokenId: String, uaHash: String, uaLabel: String, now: Double): (AccountState, Either[AccountRejection, DedicatedBrowserToken]) = usable(now) match {
    case Left(rejection) => (this, Left(rejection))
    case Right(_) =>
      val used = DedicatedBrowserTokens.use(tokens, tokenId, uaHash, uaLabel, now)
      (copy(tokens = used.tokens), used.result)
  }

  /** キーを無効にし、そのキーから発行した Cookie の次回照合を拒否させる */
  def revokeToken(tokenId: String, now: Double): Either[AccountRejection, AccountState] =
    usable(now).flatMap(_ => DedicatedBrowserTokens.revoke(tokens, tokenId, now).map(ts => copy(tokens = ts)))

  /** 照合に使う資格情報と、試行前の接続元のロック状態。パスワード方式以外は拒否する */
  def passwordCheck(keyIpHash: String, now: Double): Either[AccountRejection, (PasswordCredential, LoginThrottleDecision)] = {
    if (!AccountProfile.validTime(now) || !valid || profile.kind != AccountKind.Password) Left(AccountRejection.Invalid)
    else credential.toRight(AccountRejection.Invalid).flatMap { value =>
      value.throttle.check(keyIpHash, now).left.map(_ => AccountRejection.Invalid).map(value -> _)
    }
  }

  /** 照合の結果を失敗計数へ反映する。ロック中の接続元は一致しても失敗にする */
  def afterPasswordAttempt(keyIpHash: String, matched: Boolean, now: Double): Either[AccountRejection, (AccountState, PasswordAttempt)] =
    passwordCheck(keyIpHash, now).flatMap { case (value, before) =>
      val updated = if (matched && !before.locked) value.throttle.recordSuccess(keyIpHash, now) else value.throttle.recordFailure(keyIpHash, now)
      updated.left.map(_ => AccountRejection.Invalid).map { update =>
        val next = copy(credential = Some(value.copy(throttle = update.state)))
        if (matched && !before.locked) (next, PasswordAttempt.Accepted) else (next, PasswordAttempt.Failed(update.decision.delaySeconds))
      }
    }

  /** ログイン成功時の再計算で、失敗計数を保ったまま現在の方式の資格情報へ置き換える */
  def rehashed(next: PasswordCredential): Either[AccountRejection, AccountState] = credential match {
    case Some(value) if profile.kind == AccountKind.Password && next != null && next.valid && !next.needsRehash =>
      Right(copy(credential = Some(next.copy(throttle = value.throttle))))
    case _ => Left(AccountRejection.Invalid)
  }

  /** パスワードを置き換えて世代を進め、全端末のセッションを失効させる。失敗計数は消す */
  def setPassword(next: PasswordCredential, now: Double): Either[AccountRejection, AccountState] = usable(now).flatMap { _ =>
    if (profile.kind != AccountKind.Password || next == null || !next.valid || next.updatedAt > now) Left(AccountRejection.Invalid)
    else bumpGeneration.map(_.copy(credential = Some(next.copy(throttle = LoginThrottle()))))
  }

  /** 索引の確定後に利用を開始する。済みなら同じ状態を返し、資格情報などが欠けていれば拒否する */
  def activate(now: Double): Either[AccountRejection, AccountState] = {
    val next = copy(readiness = AccountReadiness.Ready)
    if (!AccountProfile.validTime(now) || !valid) Left(AccountRejection.Invalid)
    else if (readiness == AccountReadiness.Ready) Right(this)
    else if (!next.valid) Left(AccountRejection.Invalid)
    else Right(next)
  }

  /** Google の sub の HMAC が一致する利用可能なアカウントの情報を返す */
  def describeGoogle(subHash: String, now: Double): Either[AccountRejection, AccountDescription] =
    if (profile.kind != AccountKind.Google || !profile.googleSubHash.contains(subHash)) Left(AccountRejection.Invalid)
    else usable(now).map(_ => description)

  /** 成功投稿だけで期限・投稿数と Lv・端末とキーの直近五件を更新する。失効済み ID は復活させない */
  def recordPost(now: Double, post: AccountPost, observation: AccountObservation): Either[AccountRejection, AccountState] = {
    if (!AccountProfile.validTime(now) || !AccountProfile.validTime(post.t)) Left(AccountRejection.Invalid)
    else if (post.result != "posted") Right(this)
    else usable(now).flatMap { _ =>
      bindDevice(observation.deviceHash, observation.keyIpHash, now).map { observed =>
        val recorded = post.tokenId.fold(observed.tokens)(id => DedicatedBrowserTokens.recordPost(observed.tokens, id, post.tokenPost, now))
        observed.copy(profile = observed.profile.afterPost(now), level = level.afterPost(profile.kind, now), tokens = recorded)
      }
    }
  }
}
object AccountState {
  /** 失効させたキャップの記録を残す件数 */
  val revokedCapLimit: Int = 20

  /** 同意・時刻・初期状態を確認して作成する。パスワード方式と Google は索引の確定まで登録準備中にする */
  def create(profile: AccountProfile, now: Double, credential: Option[PasswordCredential] = None): Either[AccountRejection, AccountState] = {
    val initial = profile.copy(expiresAt = if (profile.kind == AccountKind.Client) Some(profile.createdAt + AccountProfile.clientLifetime) else None)
    val state = AccountState(initial, AccountLevel(Phoebe.initial(profile.kind, profile.emailVerifiedAt.isDefined), now),
      credential = credential, readiness = if (profile.kind == AccountKind.Client) AccountReadiness.Ready else AccountReadiness.Pending)
    if (!initial.valid || !AccountProfile.validTime(now) || profile.createdAt > now || profile.agreedAt > now ||
      profile.emailVerifiedAt.exists(_ > now) || profile.status != AccountStatus.Active || profile.generation != 0 || initial.expired(now) ||
      credential == null || credential.exists(c => c == null || c.updatedAt > now) || !state.valid) Left(AccountRejection.Invalid)
    else Right(state)
  }
}

/** アカウントログのページ位置。64 件ごとにページを替え、16 ページで循環する */
final case class AccountLogPosition(next: Long = 0L) {
  def pageNumber: Long = next / 64
  def pageSlot: Int = (pageNumber % 16).toInt
  /** 枠を再利用する際は前のページを捨て、本文の抜粋を四十文字に制限する */
  def append(page: AccountLogPage, entry: AccountPost): (AccountLogPosition, AccountLogPage) = {
    val existing = if (page.number == pageNumber) page.entries.take((next % 64).toInt) else Vector.empty
    (AccountLogPosition(next + 1), AccountLogPage(pageNumber, existing :+ entry.shortened))
  }
}
/** 保存するログページ。number で循環枠に残った古いページを見分ける */
final case class AccountLogPage(number: Long, entries: Vector[AccountPost])
