package io._3ch.bbs.repository

import io._3ch.bbs.application.auth.ports.*
import io._3ch.bbs.durable.codec.{AccountCodec, PasswordCredentialCodec}
import io._3ch.bbs.external.cloudflare.{AccountDoStub, AccountIndexDoStub, WorkerEnv}
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** 本人画面に出す専ブラキーの投稿記録。本文は四十文字以内の抜粋 */
final case class AccountTokenPostView(t: Double, bbs: String, key: String, title: String, resNum: Int, excerpt: String, network: String)
/** 別の UA からキーが使われようとした時刻と専ブラ表示名 */
final case class AccountTokenWarningView(t: Double, uaLabel: String)
/** キーの使用状況と履歴。キー平文・照合ハッシュ・生 UA は含めない */
final case class AccountTokenView(id: String, label: String, issuedAt: Double = 0, expiresAt: Double = 0,
  boundAt: Option[Double] = None, lastUsedAt: Option[Double] = None, revokedAt: Option[Double] = None,
  revokeReason: Option[String] = None, replacedTokenAt: Option[Double] = None,
  recentPosts: Vector[AccountTokenPostView] = Vector.empty, warnings: Vector[AccountTokenWarningView] = Vector.empty)
/** 本人画面に出すキャップと直近の書き込み。照合値とパスワードは含めない */
final case class AccountCapView(id: String, name: String, strong: Boolean, issuedAt: Double, revokedAt: Option[Double] = None,
  recentPosts: Vector[AccountTokenPostView] = Vector.empty)
/** 本人向けのアカウント情報とキー一覧。使用枠には有効な紐付け済みキーだけを数える */
final case class AccountPanel(account: AccountDescription, tokens: Vector[AccountTokenView], activeCount: Int = 0, deviceCount: Int = 0,
  caps: Vector[AccountCapView] = Vector.empty, email: Option[String] = None, pendingEmail: Option[String] = None)

/** アカウント RPC の値をアプリケーションの型に変換する */
final class AccountRepository(env: WorkerEnv) extends AccountStore with AccountManagementStore with AccountSignInStore with AccountRoleStore with AccountCapStore
  with AccountEmailStore with AccountConversionStore {
  private def account(aid: String): AccountDoStub = env.ACCOUNT_DO.get(env.ACCOUNT_DO.idFromName(aid))
  private def index(kind: String): AccountIndexDoStub = env.ACCOUNT_INDEX_DO.get(env.ACCOUNT_INDEX_DO.idFromName(kind))
  private def raw(value: js.Promise[?]): Future[js.Any] = value.asInstanceOf[js.Promise[js.Any]].toFuture
  private def invalid(): Nothing = throw AccountStoreException(AccountRejection.Invalid)
  private def record(value: js.Any): js.Dynamic = {
    if (value == null || js.typeOf(value) != "object" || js.Array.isArray(value)) invalid()
    value.asInstanceOf[js.Dynamic]
  }
  private def string(value: js.Any): String = if (js.typeOf(value) == "string") value.asInstanceOf[String] else invalid()
  private def text(v: js.Dynamic, name: String): String = string(record(v).selectDynamic(name).asInstanceOf[js.Any])
  private def number(value: js.Any): Double = {
    if (js.typeOf(value) != "number") invalid()
    val n = value.asInstanceOf[Double]
    if (!AccountProfile.validTime(n)) invalid()
    n
  }
  private def integer(value: js.Any, maximum: Int = Int.MaxValue): Int = {
    val n = number(value)
    if (n > maximum) invalid()
    n.toInt
  }
  private def time(v: js.Dynamic, name: String): Double = number(record(v).selectDynamic(name).asInstanceOf[js.Any])
  private def optionalTime(v: js.Dynamic, name: String): Option[Double] = {
    val raw = record(v).selectDynamic(name).asInstanceOf[js.Any]
    if (js.isUndefined(raw)) None else Some(number(raw))
  }
  private def optionalText(v: js.Dynamic, name: String): Option[String] = {
    val raw = record(v).selectDynamic(name).asInstanceOf[js.Any]
    if (js.isUndefined(raw)) None else Some(string(raw))
  }
  private def array(v: js.Dynamic, name: String): Vector[js.Any] = {
    val raw = record(v).selectDynamic(name).asInstanceOf[js.Any]
    if (!js.Array.isArray(raw)) invalid()
    raw.asInstanceOf[js.Array[js.Any]].toVector
  }
  private def aid(value: String): String = if (value.matches("[a-z0-9]{16}")) value else invalid()
  private def identifier(value: String): String = if (DedicatedBrowserTokens.validId(value)) value else invalid()
  private def rejection(code: String): AccountRejection = AccountRejection.values.find(_.code == code).getOrElse(AccountRejection.Invalid)
  private def decode(v: js.Dynamic, expectedAid: String): Either[AccountRejection, AccountDescription] = {
    val code = text(v, "code")
    if (code.nonEmpty) Left(rejection(code))
    else {
      val accountAid = aid(text(v, "aid"))
      val kind = AccountKind.parse(text(v, "kind")).getOrElse(invalid())
      val uid = text(v, "uid")
      val expires = optionalTime(v, "expiresAt")
      val roles = Roles(array(v, "roles").map(value => AccountRole.parse(string(value)).getOrElse(invalid())).toSet)
      val version = text(v, "agreementVersion")
      if (accountAid != expectedAid || !uid.matches("[a-z0-9_]{3,16}") || version.isEmpty || !roles.permitted(kind) ||
        (kind == AccountKind.Client && (!uid.matches("[a-z0-9]{8}") || expires.isEmpty)) ||
        (kind != AccountKind.Client && expires.isDefined)) invalid()
      Right(AccountDescription(accountAid, uid, kind, integer(v.selectDynamic("generation").asInstanceOf[js.Any]), expires,
        integer(v.selectDynamic("lv").asInstanceOf[js.Any], 10), roles, time(v, "agreedAt"), version))
    }
  }
  private def checked(code: js.Any): Unit = {
    val value = string(code)
    if (value.nonEmpty) throw AccountStoreException(rejection(value))
  }
  def describe(aid: String, generation: Int, now: Double, token: Option[TokenProof]): Future[Either[AccountRejection, AccountDescription]] =
    account(aid).describeForPosting(aid, generation, now, token.map(_.tokenId).orUndefined, token.map(_.uaHash).orUndefined).toFuture.map(decode(_, aid))
  def token(hash: String): Future[Either[AccountRejection, Option[TokenReference]]] = index("token").lookup(hash).toFuture.map { v =>
    val code = text(v, "code")
    if (code == "auth:index:missing") Right(None) else if (code.nonEmpty) Left(rejection(code))
    else Right(Some(TokenReference(aid(text(v, "aid")), identifier(text(v, "tokenId")))))
  }
  def useToken(reference: TokenReference, uaHash: String, uaLabel: String, now: Double): Future[Either[AccountRejection, TokenAuthentication]] =
    account(reference.aid).useToken(reference.tokenId, uaHash, uaLabel, now).toFuture.map(v => decode(v, reference.aid).map { description =>
      if (identifier(text(v, "tokenId")) != reference.tokenId) invalid()
      TokenAuthentication(description, reference.tokenId)
    })
  def capOwner(hash: String): Future[Either[AccountRejection, Option[String]]] = index("cap").lookup(hash).toFuture.map { v =>
    val code = text(v, "code")
    if (code == "auth:index:missing") Right(None) else if (code.nonEmpty) Left(rejection(code)) else Right(Some(aid(text(v, "aid"))))
  }
  def describeCap(aid: String, capKey: String, now: Double): Future[Either[AccountRejection, CapAuthentication]] =
    account(aid).describeForCap(aid, capKey, now).toFuture.map(v => decode(v, aid).map(CapAuthentication(_, identifier(text(v, "capId")))))
  def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String] = None): Future[Boolean] =
    raw(index(kind).register(hash, aid, now, tokenId.orUndefined)).map { value =>
      val code = string(value)
      if (code == AccountRejection.IndexConflict.code) false else { checked(code); true }
    }
  def confirm(kind: String, hash: String, aid: String, now: Double): Future[Unit] = raw(index(kind).confirm(hash, aid, now)).map(checked)
  def remove(kind: String, hash: String, aid: String): Future[Unit] = raw(index(kind).remove(hash, aid)).map(checked)
  def create(profile: AccountProfile, now: Double): Future[AccountDescription] = create(profile, now, None)
  def create(profile: AccountProfile, now: Double, credential: Option[PasswordCredential]): Future[AccountDescription] =
    account(profile.aid).create(AccountCodec.encodeProfile(profile), now, credential.map(PasswordCredentialCodec.encode).orUndefined).toFuture.map(v =>
      decode(v, profile.aid).fold(reason => throw AccountStoreException(reason), identity))
  def lookup(kind: String, hash: String): Future[Option[String]] = index(kind).lookup(hash).toFuture.map { v =>
    val code = text(v, "code")
    if (code == "auth:index:missing") None else { checked(code); Some(aid(text(v, "aid"))) }
  }
  def activate(aid: String, now: Double): Future[AccountDescription] = account(aid).activate(now).toFuture.map(v =>
    decode(v, aid).fold(reason => throw AccountStoreException(reason), identity))
  private def signIn(v: js.Dynamic, expectedAid: String): SignInResult = text(v, "code") match {
    case code if code == AccountRejection.LoginFailed.code => SignInResult.Failed(integer(record(v).selectDynamic("delay").asInstanceOf[js.Any], 8))
    case code if code == AccountRejection.Pending.code =>
      val uid = text(v, "uid")
      if (!uid.matches("[a-z0-9_]{3,16}")) invalid()
      SignInResult.Unfinished(uid)
    case _ => decode(v, expectedAid).fold(SignInResult.Refused(_), SignInResult.Verified(_))
  }
  def verifyPassword(aid: String, password: String, keyIpHash: String, now: Double): Future[SignInResult] =
    account(aid).verifyPassword(password, keyIpHash, now).toFuture.map(signIn(_, aid))
  def describeGoogle(aid: String, subHash: String, now: Double): Future[SignInResult] =
    account(aid).describeGoogle(subHash, now).toFuture.map(signIn(_, aid))
  private def described(aid: String)(v: js.Dynamic): AccountDescription = decode(v, aid).fold(reason => throw AccountStoreException(reason), identity)
  def issuePending(aid: String, kind: PendingKind, hash: String, email: Option[String], now: Double): Future[Option[String]] =
    account(aid).issuePending(kind.code, hash, email.orUndefined, now).toFuture.map { v =>
      checked(text(v, "code"))
      optionalText(v, "email")
    }
  def checkPending(aid: String, kind: PendingKind, hash: String, now: Double): Future[PendingCheck] =
    account(aid).checkPending(kind.code, hash, now).toFuture.map { v =>
      checked(text(v, "code"))
      val uid = text(v, "uid")
      if (!uid.matches("[a-z0-9_]{3,16}")) invalid()
      PendingCheck(uid, optionalText(v, "email"))
    }
  def verifyEmail(aid: String, hash: String, now: Double): Future[EmailVerified] =
    account(aid).verifyEmail(hash, now).toFuture.map(v => EmailVerified(described(aid)(v), optionalText(v, "previousEmail")))
  def resetPassword(aid: String, hash: String, credential: PasswordCredential, now: Double): Future[AccountDescription] =
    account(aid).resetPassword(hash, PasswordCredentialCodec.encode(credential), now).toFuture.map(described(aid))
  def convert(aid: String, target: AccountConversion, uid: String, generation: Int, now: Double): Future[AccountDescription] = {
    val stub = account(aid)
    val result = target match {
      case AccountConversion.ToPassword(credential) =>
        stub.convert(AccountKind.Password.code, uid, generation, now, PasswordCredentialCodec.encode(credential), js.undefined, js.undefined)
      case AccountConversion.ToGoogle(subHash, email) => stub.convert(AccountKind.Google.code, uid, generation, now, js.undefined, subHash, email.orUndefined)
    }
    result.toFuture.map(described(aid))
  }
  def setPassword(aid: String, credential: PasswordCredential, now: Double): Future[AccountDescription] =
    account(aid).setPassword(PasswordCredentialCodec.encode(credential), now).toFuture.map(described(aid))
  def bindDevice(aid: String, device: String, keyIp: String, now: Double): Future[Unit] = raw(account(aid).bindDevice(device, keyIp, now)).map(checked)
  def logout(aid: String, generation: Int, now: Double): Future[Unit] = raw(account(aid).bumpGeneration(now)).map { next =>
    if (integer(next) <= generation) throw AccountStoreException(AccountRejection.Generation)
  }
  def agree(aid: String, version: String, now: Double): Future[Unit] = raw(account(aid).agree(version, now)).map(checked)
  def issueToken(aid: String, tokenId: String, hash: String, now: Double): Future[AccountDescription] = account(aid).issueToken(tokenId, hash, now).toFuture.map(v =>
    decode(v, aid).map { description =>
      if (identifier(text(v, "tokenId")) != tokenId) invalid()
      description
    }.fold(reason => throw AccountStoreException(reason), identity))
  def revokeToken(aid: String, tokenId: String, now: Double): Future[Unit] = raw(account(aid).revokeToken(tokenId, now)).map(checked)
  /** Lv を固定する。None で固定を外す */
  def setLevel(aid: String, lvOverride: Option[Int], now: Double): Future[Unit] =
    raw(account(aid).setLevel(lvOverride.orUndefined, now)).map(checked)
  def bindCap(aid: String, cap: AccountCap, issuerIsAdmin: Boolean, now: Double): Future[Unit] =
    raw(account(aid).bindCap(AccountCodec.encodeCap(cap), issuerIsAdmin, now)).map(checked)
  def revokeCap(aid: String, capId: String, now: Double): Future[String] = account(aid).revokeCap(capId, now).toFuture.map { v =>
    checked(text(v, "code"))
    text(v, "hash")
  }
  def reissueCap(aid: String, capId: String, nextId: String, nextHash: String, issuedBy: String, issuerIsAdmin: Boolean, now: Double): Future[CapReissue] =
    account(aid).reissueCap(capId, nextId, nextHash, issuedBy, issuerIsAdmin, now).toFuture.map { v =>
      checked(text(v, "code"))
      val strong = record(v).selectDynamic("strong").asInstanceOf[js.Any]
      if (js.typeOf(strong) != "boolean") invalid()
      CapReissue(text(v, "hash"), text(v, "name") + (if (strong.asInstanceOf[Boolean]) "★" else ""))
    }
  private def codes(roles: Roles): js.Array[String] = roles.values.toVector.map(_.code).sorted.toJSArray
  def setRoles(aid: String, roles: Roles, actor: Roles, now: Double): Future[Unit] = raw(account(aid).setRoles(codes(roles), codes(actor), now)).map(checked)
  def grantFirstAdmin(aid: String, now: Double): Future[Unit] = raw(account(aid).grantFirstAdmin(now)).map(checked)
  def claimFirstAdmin(hash: String, aid: String, now: Double, breakGlass: Boolean): Future[Unit] =
    raw(index(AccountIndexKind.Admin.code).claimFirstAdmin(hash, aid, now, breakGlass)).map(checked)
  def recordPost(aid: String, post: AccountPost, observation: AccountObservation): Future[AccountDescription] =
    account(aid).recordPost(post.t, AccountCodec.encodePost(post), js.Dynamic.literal(deviceHash = observation.deviceHash, keyIpHash = observation.keyIpHash)).toFuture.map(v =>
      decode(v, aid).fold(reason => throw AccountStoreException(reason), identity))
  def inspect(aid: String): Future[AccountPanel] = account(aid).inspect().toFuture.map { value =>
    val code = text(value, "code")
    checked(code)
    val description = decode(record(value.selectDynamic("account")), aid).fold(reason => throw AccountStoreException(reason), identity)
    val rejections = array(value, "rejections").map(record)
    def recentPosts(owner: js.Dynamic): Vector[AccountTokenPostView] = {
      val posts = array(owner, "recentPosts").map { raw =>
        val post = record(raw)
        val excerpt = text(post, "excerpt")
        if (excerpt.length > 40) invalid()
        AccountTokenPostView(time(post, "t"), text(post, "bbs"), text(post, "key"), text(post, "title"),
          integer(post.selectDynamic("resNum").asInstanceOf[js.Any]), excerpt, text(post, "network"))
      }
      if (posts.size > 5) invalid()
      posts
    }
    val tokens = array(value, "tokens").map { raw =>
      val token = record(raw)
      val id = identifier(text(token, "tokenId"))
      val reason = optionalText(token, "revokeReason")
      if (reason.exists(code => TokenRevokeReason.parse(code).isEmpty)) invalid()
      val posts = recentPosts(token)
      val warnings = rejections.filter(log => optionalText(log, "tokenId").contains(id) && text(log, "result") == AccountRejection.TokenUaMismatch.code)
        .map(log => AccountTokenWarningView(time(log, "t"), text(log, "uaLabel"))).sortBy(w => -w.t)
      val view = AccountTokenView(id, optionalText(token, "uaLabel").getOrElse(""), time(token, "issuedAt"), time(token, "expiresAt"),
        optionalTime(token, "boundAt"), optionalTime(token, "lastUsedAt"), optionalTime(token, "revokedAt"), reason,
        optionalTime(token, "replacedTokenAt"), posts, warnings)
      if (view.expiresAt <= view.issuedAt || view.revokedAt.isDefined != view.revokeReason.isDefined ||
        Vector(view.boundAt, view.lastUsedAt, view.revokedAt, view.replacedTokenAt).flatten.exists(_ < view.issuedAt) ||
        (view.replacedTokenAt.isDefined && view.boundAt.isEmpty)) invalid()
      view
    }
    if (tokens.map(_.id).distinct.size != tokens.size) invalid()
    val activeCount = tokens.count(token => token.boundAt.isDefined && token.revokedAt.isEmpty)
    if (activeCount > DedicatedBrowserTokens.boundLimit) invalid()
    val caps = array(value, "caps").map { raw =>
      val cap = record(raw)
      def flag(name: String): Boolean = {
        val v = cap.selectDynamic(name).asInstanceOf[js.Any]
        if (js.typeOf(v) != "boolean") invalid()
        v.asInstanceOf[Boolean]
      }
      AccountCapView(identifier(text(cap, "capId")), text(cap, "name"), flag("strong"), time(cap, "issuedAt"), optionalTime(cap, "revokedAt"),
        recentPosts(cap))
    }
    AccountPanel(description, tokens, activeCount, integer(value.selectDynamic("deviceCount").asInstanceOf[js.Any], 10), caps,
      optionalText(value, "email"), optionalText(value, "pendingEmail"))
  }
  def rate(key: String, kind: String, now: Double): Future[AuthRateCounts] =
    env.AUTH_RATE_DO.get(env.AUTH_RATE_DO.idFromName(key)).hit(kind, now).toFuture.map { v =>
      checked(text(v, "code"))
      val counts = AuthRateCounts(integer(v.selectDynamic("hour").asInstanceOf[js.Any]), integer(v.selectDynamic("day").asInstanceOf[js.Any]),
        integer(v.selectDynamic("distinctDevices24h").asInstanceOf[js.Any]))
      if (counts.hour > counts.day) invalid()
      counts
    }
}
