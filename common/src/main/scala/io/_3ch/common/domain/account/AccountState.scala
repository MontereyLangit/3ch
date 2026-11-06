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
      case _ => credential.isEmpty
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

  /** 所有アカウントとキャップが利用できる場合だけ投稿用の情報を返す */
  def describeForCap(aid: String, hash: String, now: Double): Either[AccountRejection, (AccountDescription, AccountCap)] = {
    if (aid != profile.aid) Left(AccountRejection.Missing)
    else usable(now).flatMap { _ =>
      caps.find(cap => cap.hash == hash && cap.revokedAt.isEmpty).toRight(AccountRejection.CapRevoked).map(cap => (description, cap))
    }
  }

  /** 権限保持者は自分宛に一個まで登録でき、強キャップと他人宛の発行は管理者に限る */
  def bindCap(cap: AccountCap, issuerIsAdmin: Boolean, now: Double): Either[AccountRejection, AccountState] = usable(now).flatMap { _ =>
    if (!cap.valid || cap.issuedAt > now || cap.revokedAt.isDefined) Left(AccountRejection.Invalid)
    else if (caps.exists(c => c.capId == cap.capId || c.hash == cap.hash)) Left(AccountRejection.Exists)
    else if (!issuerIsAdmin && (cap.issuedBy != profile.aid || roles.values.isEmpty || cap.strong || caps.exists(_.revokedAt.isEmpty))) Left(AccountRejection.RolesForbidden)
    else Right(copy(caps = caps :+ cap))
  }

  /** キャップの記録を残して失効させる。KV と索引の削除は呼び出し元で行う */
  def revokeCap(capId: String, now: Double): Either[AccountRejection, AccountState] = usable(now).flatMap { _ =>
    caps.find(_.capId == capId).toRight(AccountRejection.CapRevoked).map { _ =>
      copy(caps = caps.map(cap => if (cap.capId == capId && cap.revokedAt.isEmpty) cap.copy(revokedAt = Some(now)) else cap))
    }
  }

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

  /** 状態が変わったときだけ世代も進め、既存セッションを失効させる */
  def setStatus(status: AccountStatus): Either[AccountRejection, AccountState] =
    if (profile.status == status) Right(this) else bumpGeneration.map(s => s.copy(profile = s.profile.copy(status = status)))

  /** 役割を置き換える。クライアント ID への役割付与は拒否する */
  def setRoles(next: Roles): Either[AccountRejection, AccountState] =
    if (!next.permitted(profile.kind)) Left(AccountRejection.RolesForbidden) else Right(copy(roles = next))

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

  /** 成功投稿だけで期限・投稿数・端末とキーの直近五件を更新する。失効済み ID は復活させない */
  def recordPost(now: Double, post: AccountPost, observation: AccountObservation): Either[AccountRejection, AccountState] = {
    if (!AccountProfile.validTime(now) || !AccountProfile.validTime(post.t)) Left(AccountRejection.Invalid)
    else if (post.result != "posted") Right(this)
    else usable(now).flatMap { _ =>
      bindDevice(observation.deviceHash, observation.keyIpHash, now).map { observed =>
        val recorded = post.tokenId.fold(observed.tokens)(id => DedicatedBrowserTokens.recordPost(observed.tokens, id, post.tokenPost, now))
        observed.copy(profile = observed.profile.afterPost(now), level = level.afterPost, tokens = recorded)
      }
    }
  }
}
object AccountState {
  /** 同意・時刻・初期状態を確認して作成する。パスワード方式は登録準備中にする */
  def create(profile: AccountProfile, now: Double): Either[AccountRejection, AccountState] = {
    val initial = profile.copy(expiresAt = if (profile.kind == AccountKind.Client) Some(profile.createdAt + AccountProfile.clientLifetime) else None)
    if (!initial.valid || !AccountProfile.validTime(now) || profile.createdAt > now || profile.agreedAt > now ||
      profile.emailVerifiedAt.exists(_ > now) || profile.status != AccountStatus.Active || profile.generation != 0 || initial.expired(now)) Left(AccountRejection.Invalid)
    else Right(AccountState(initial, AccountLevel(if (profile.kind == AccountKind.Google) 10 else if (profile.kind == AccountKind.Password && profile.emailVerifiedAt.isDefined) 3 else 1, now),
      readiness = if (profile.kind == AccountKind.Password) AccountReadiness.Pending else AccountReadiness.Ready))
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
