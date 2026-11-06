package io._3ch.common.domain.account

/** 専ブラキーの無効化理由 */
enum TokenRevokeReason(val code: String) {
  case Revoked extends TokenRevokeReason("revoked")
  case Superseded extends TokenRevokeReason("superseded")
  case Expired extends TokenRevokeReason("expired")
}
object TokenRevokeReason {
  def parse(code: String): Option[TokenRevokeReason] = TokenRevokeReason.values.find(_.code == code)
}

/** 専ブラの直近の書き込み。平文キーと生 UA は含めない */
final case class TokenPost(t: Double, bbs: String, key: String, title: String, resNum: Int, excerpt: String, network: String)

/** 保存する専ブラキー。hash は平文キーの HMAC、UA は初回使用時に紐付く */
final case class DedicatedBrowserToken(
  tokenId: String,
  hash: String,
  issuedAt: Double,
  expiresAt: Double,
  uaHash: Option[String] = None,
  uaLabel: Option[String] = None,
  boundAt: Option[Double] = None,
  replacedTokenAt: Option[Double] = None,
  lastUsedAt: Option[Double] = None,
  recentPosts: Vector[TokenPost] = Vector.empty,
  revokedAt: Option[Double] = None,
  revokeReason: Option[TokenRevokeReason] = None
) {
  def valid: Boolean = DedicatedBrowserTokens.validId(tokenId) && DedicatedBrowserTokens.validHash(hash) &&
    AccountProfile.validTime(issuedAt) && AccountProfile.validTime(expiresAt) && expiresAt > issuedAt &&
    uaHash.forall(DedicatedBrowserTokens.validHash) &&
    Vector(boundAt, replacedTokenAt, lastUsedAt, revokedAt).flatten.forall(AccountProfile.validTime) &&
    uaHash.isDefined == boundAt.isDefined && revokedAt.isDefined == revokeReason.isDefined && recentPosts.size <= 5 &&
    recentPosts.forall(post => AccountProfile.validTime(post.t) && post.resNum >= 0 && post.excerpt.length <= 40)

  def active(now: Double): Boolean = revokedAt.isEmpty && (uaHash.isDefined || expiresAt > now)
  def revoke(now: Double, reason: TokenRevokeReason): DedicatedBrowserToken = copy(revokedAt = Some(now), revokeReason = Some(reason))
}

/** 専ブラキーの使用結果。拒否でも期限切れの整理を反映する */
final case class TokenUseResult(tokens: Vector[DedicatedBrowserToken], result: Either[AccountRejection, DedicatedBrowserToken])

/** 専ブラキーの初回紐付け・置換・失効を判定する純粋な規則 */
object DedicatedBrowserTokens {
  val unusedLifetime: Double = 86400
  val retainedLifetime: Double = 30d * 86400
  val boundLimit: Int = 5
  /** 無効にしたキーの記録を残す最大数。新しく無効にしたものから残す */
  val revokedLimit: Int = 20

  def validId(id: String): Boolean = id.matches("[a-z0-9]{1,64}")
  def validHash(hash: String): Boolean = hash.matches("[0-9a-f]{64}")

  /** 未使用で二十四時間を過ぎたキーを失効させ、無効化から三十日経った記録と上限を超えた古い記録を除く */
  def retained(tokens: Vector[DedicatedBrowserToken], now: Double): Vector[DedicatedBrowserToken] = {
    val current = tokens.map { token =>
      if (token.revokedAt.isEmpty && token.uaHash.isEmpty && token.expiresAt <= now) token.revoke(token.expiresAt, TokenRevokeReason.Expired)
      else token
    }.filter(_.revokedAt.forall(_ > now - retainedLifetime))
    val kept = current.filter(_.revokedAt.isDefined).sortBy(token => -token.revokedAt.get).take(revokedLimit).map(_.tokenId).toSet
    current.filter(token => token.revokedAt.isEmpty || kept.contains(token.tokenId))
  }

  /** 前の状態で有効だったのに、次の状態で無効または削除になったキーの照合ハッシュ。索引から外すのに使う */
  def released(before: Vector[DedicatedBrowserToken], after: Vector[DedicatedBrowserToken]): Vector[String] = {
    val remaining = after.filter(_.revokedAt.isEmpty).map(_.hash).toSet
    before.filter(_.revokedAt.isEmpty).map(_.hash).filterNot(remaining.contains).distinct
  }

  /** 重複を拒否し、新しい未使用キーで以前の未使用キーを置き換える */
  def issue(tokens: Vector[DedicatedBrowserToken], tokenId: String, hash: String, now: Double): Either[AccountRejection, Vector[DedicatedBrowserToken]] = {
    val current = retained(tokens, now)
    if (!validId(tokenId) || !validHash(hash) || !AccountProfile.validTime(now)) Left(AccountRejection.Invalid)
    else if (current.exists(t => t.tokenId == tokenId || t.hash == hash)) Left(AccountRejection.Exists)
    else Right(current.map { token =>
      if (token.uaHash.isEmpty && token.active(now)) token.revoke(now, TokenRevokeReason.Superseded) else token
    } :+ DedicatedBrowserToken(tokenId, hash, now, now + unusedLifetime))
  }

  /** 最大五つの UA に紐付ける。同じ UA の別キーは置換し、UA 不一致では元のキーを止めない */
  def use(tokens: Vector[DedicatedBrowserToken], tokenId: String, uaHash: String, uaLabel: String, now: Double): TokenUseResult = {
    val current = retained(tokens, now)
    val result = if (!validHash(uaHash) || !AccountProfile.validTime(now)) Left(AccountRejection.Invalid)
    else current.find(_.tokenId == tokenId) match {
      case None => Left(AccountRejection.TokenUnknown)
      case Some(token) if !token.active(now) => Left(AccountRejection.TokenRevoked)
      case Some(token) if token.uaHash.exists(_ != uaHash) => Left(AccountRejection.TokenUaMismatch)
      case Some(token) if token.uaHash.isDefined => Right(token.copy(lastUsedAt = Some(now)))
      case Some(token) =>
        val active = current.filter(t => t.active(now) && t.uaHash.isDefined)
        val replaced = active.find(_.uaHash.contains(uaHash))
        if (replaced.isEmpty && active.size >= boundLimit) Left(AccountRejection.TokenLimit)
        else Right(token.copy(uaHash = Some(uaHash), uaLabel = Some(uaLabel), boundAt = Some(now), lastUsedAt = Some(now),
          replacedTokenAt = replaced.map(_ => now)))
    }
    val updated = result.fold(_ => current, used => current.map { token =>
      if (token.tokenId == used.tokenId) used
      else if (token.active(now) && token.uaHash.contains(uaHash)) token.revoke(now, TokenRevokeReason.Superseded)
      else token
    })
    TokenUseResult(updated, result)
  }

  /** Cookie の照合用。紐付け済みの有効なキーと完全な UA ハッシュの一致を求める */
  def describe(tokens: Vector[DedicatedBrowserToken], tokenId: String, uaHash: String, now: Double): Either[AccountRejection, DedicatedBrowserToken] = {
    if (!AccountProfile.validTime(now) || !validHash(uaHash)) Left(AccountRejection.Invalid)
    else retained(tokens, now).find(_.tokenId == tokenId) match {
      case None => Left(AccountRejection.TokenUnknown)
      case Some(token) if !token.active(now) || token.uaHash.isEmpty => Left(AccountRejection.TokenRevoked)
      case Some(token) if !token.uaHash.contains(uaHash) => Left(AccountRejection.TokenUaMismatch)
      case Some(token) => Right(token)
    }
  }

  /** キーを手動で無効にする。既に無効なら元の時刻と理由を残す */
  def revoke(tokens: Vector[DedicatedBrowserToken], tokenId: String, now: Double): Either[AccountRejection, Vector[DedicatedBrowserToken]] = {
    val current = retained(tokens, now)
    if (!AccountProfile.validTime(now)) Left(AccountRejection.Invalid)
    else current.find(_.tokenId == tokenId).toRight(AccountRejection.TokenUnknown).map { _ =>
      current.map(t => if (t.tokenId == tokenId && t.revokedAt.isEmpty) t.revoke(now, TokenRevokeReason.Revoked) else t)
    }
  }

  /** 有効な紐付け済みキーの投稿を五件まで残し、抜粋を四十文字以内にする */
  def recordPost(tokens: Vector[DedicatedBrowserToken], tokenId: String, post: TokenPost, now: Double): Vector[DedicatedBrowserToken] =
    retained(tokens, now).map { token =>
      if (token.tokenId == tokenId && token.active(now) && token.uaHash.isDefined)
        token.copy(recentPosts = (post.copy(excerpt = AccountPost.excerptOf(post.excerpt)) +: token.recentPosts).take(5), lastUsedAt = Some(now))
      else token
    }
}
