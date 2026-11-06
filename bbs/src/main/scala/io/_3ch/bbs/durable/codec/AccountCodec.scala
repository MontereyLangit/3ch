package io._3ch.bbs.durable.codec

import io._3ch.common.domain.account.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** Account の保存値と RPC の DTO を純粋モデルへ変換する */
object AccountCodec {
  val stateKeys: js.Array[String] = js.Array("profile", "level", "roles", "tokens", "devices", "caps", "activation", "credential",
    pendingKey(PendingKind.Email), pendingKey(PendingKind.Reset))

  /** 保留トークンの保存キー。種別ごとに分けて、メール認証と再設定が上書きし合わないようにする */
  def pendingKey(kind: PendingKind): String = "pending:" + kind.code

  private def invalid(): Nothing = throw js.JavaScriptException(new js.Error("invalid-account-data"))
  private def field(value: js.Dynamic, name: String): js.Any = {
    if (value == null || js.typeOf(value) != "object" || js.Array.isArray(value)) invalid()
    value.selectDynamic(name).asInstanceOf[js.Any]
  }
  private def missing(value: js.Any): Boolean = value == null || js.isUndefined(value)
  private def text(value: js.Any): String = if (js.typeOf(value) == "string") value.asInstanceOf[String] else invalid()
  private def number(value: js.Any): Double = if (js.typeOf(value) == "number" && value.asInstanceOf[Double].isFinite) value.asInstanceOf[Double] else invalid()
  private def int(value: js.Any): Int = {
    val n = number(value)
    if (n >= 0 && n <= Int.MaxValue && n == math.floor(n)) n.toInt else invalid()
  }
  private def requiredTime(value: js.Dynamic, name: String): Double = {
    val n = number(field(value, name))
    if (!AccountProfile.validTime(n)) invalid()
    n
  }
  private def string(value: js.Dynamic, name: String, default: String = ""): String = {
    val v = field(value, name)
    if (missing(v)) default else text(v)
  }
  private def decimal(value: js.Dynamic, name: String, default: Double = 0): Double = {
    val v = field(value, name)
    if (missing(v)) default else number(v)
  }
  private def integer(value: js.Dynamic, name: String, default: Int = 0): Int = {
    val v = field(value, name)
    if (missing(v)) default else int(v)
  }
  private def optionalText(value: js.Dynamic, name: String): Option[String] = Option(field(value, name)).filterNot(js.isUndefined).map(text)
  private def optionalNumber(value: js.Dynamic, name: String): Option[Double] = Option(field(value, name)).filterNot(js.isUndefined).map(number)
  private def bool(value: js.Dynamic, name: String): Boolean = {
    val v = field(value, name)
    if (missing(v)) false else if (js.typeOf(v) == "boolean") v.asInstanceOf[Boolean] else invalid()
  }
  private def array(value: js.Any): Vector[js.Dynamic] =
    if (missing(value)) Vector.empty else if (js.Array.isArray(value)) value.asInstanceOf[js.Array[js.Dynamic]].toVector else invalid()

  def decodeProfile(value: js.Dynamic): AccountProfile = AccountProfile(
    aid = text(field(value, "aid")), uid = text(field(value, "uid")), kind = AccountKind.parse(text(field(value, "kind"))).getOrElse(invalid()),
    createdAt = requiredTime(value, "createdAt"), expiresAt = optionalNumber(value, "expiresAt"), agreedAt = requiredTime(value, "agreedAt"),
    agreementVersion = text(field(value, "agreementVersion")), status = AccountStatus.parse(text(field(value, "status"))).getOrElse(invalid()),
    generation = int(field(value, "generation")), loginId = optionalText(value, "loginId"), email = optionalText(value, "email"),
    googleSubHash = optionalText(value, "googleSubHash"), emailVerifiedAt = optionalNumber(value, "emailVerifiedAt"))

  def encodeProfile(p: AccountProfile): js.Dynamic = js.Dynamic.literal(aid = p.aid, uid = p.uid, kind = p.kind.code, createdAt = p.createdAt,
    expiresAt = p.expiresAt.orUndefined, agreedAt = p.agreedAt, agreementVersion = p.agreementVersion, status = p.status.code, generation = p.generation,
    loginId = p.loginId.orUndefined, email = p.email.orUndefined, googleSubHash = p.googleSubHash.orUndefined, emailVerifiedAt = p.emailVerifiedAt.orUndefined)

  private def decodeLevel(v: js.Dynamic): AccountLevel = AccountLevel(int(field(v, "lv")), requiredTime(v, "lvSince"), int(field(v, "lvPosts")), optionalNumber(v, "lvOverride").map { n =>
    if (n >= 0 && n <= 10 && n == math.floor(n)) n.toInt else invalid()
  })
  private def encodeLevel(v: AccountLevel): js.Dynamic = js.Dynamic.literal(lv = v.lv, lvSince = v.lvSince, lvPosts = v.lvPosts, lvOverride = v.lvOverride.orUndefined)
  private def decodeRoles(value: js.Any): Roles = {
    val values = if (missing(value)) Vector.empty else if (js.Array.isArray(value)) value.asInstanceOf[js.Array[js.Any]].toVector else invalid()
    Roles(values.map(v => AccountRole.parse(text(v)).getOrElse(invalid())).toSet)
  }
  private def encodeRoles(roles: Roles): js.Array[String] = roles.values.toVector.sortBy(_.code).map(_.code).toJSArray

  private def decodeTokenPost(v: js.Dynamic): TokenPost = TokenPost(decimal(v, "t"), string(v, "bbs"), string(v, "key"), string(v, "title"), integer(v, "resNum"), string(v, "excerpt"), string(v, "network"))
  private def encodeTokenPost(v: TokenPost): js.Dynamic = js.Dynamic.literal(t = v.t, bbs = v.bbs, key = v.key, title = v.title, resNum = v.resNum, excerpt = v.excerpt, network = v.network)
  private def decodeToken(v: js.Dynamic): DedicatedBrowserToken = DedicatedBrowserToken(string(v, "tokenId"), string(v, "hash"), decimal(v, "issuedAt"), decimal(v, "expiresAt"),
    optionalText(v, "uaHash"), optionalText(v, "uaLabel"), optionalNumber(v, "boundAt"), optionalNumber(v, "replacedTokenAt"), optionalNumber(v, "lastUsedAt"),
    array(field(v, "recentPosts")).map(decodeTokenPost), optionalNumber(v, "revokedAt"), optionalText(v, "revokeReason").map(s => TokenRevokeReason.parse(s).getOrElse(invalid())))
  private def encodeToken(v: DedicatedBrowserToken): js.Dynamic = js.Dynamic.literal(tokenId = v.tokenId, hash = v.hash, issuedAt = v.issuedAt, expiresAt = v.expiresAt,
    uaHash = v.uaHash.orUndefined, uaLabel = v.uaLabel.orUndefined, boundAt = v.boundAt.orUndefined, replacedTokenAt = v.replacedTokenAt.orUndefined,
    lastUsedAt = v.lastUsedAt.orUndefined, recentPosts = v.recentPosts.map(encodeTokenPost).toJSArray, revokedAt = v.revokedAt.orUndefined, revokeReason = v.revokeReason.map(_.code).orUndefined)
  private def decodeDevice(v: js.Dynamic): AccountDevice = AccountDevice(string(v, "hash"), decimal(v, "firstSeen"), decimal(v, "lastSeen"), string(v, "keyIpHash"))
  private def encodeDevice(v: AccountDevice): js.Dynamic = js.Dynamic.literal(hash = v.hash, firstSeen = v.firstSeen, lastSeen = v.lastSeen, keyIpHash = v.keyIpHash)

  def decodeCap(v: js.Dynamic): AccountCap = AccountCap(string(v, "capId"), string(v, "name"), string(v, "hash"), bool(v, "strong"), string(v, "issuedBy"), decimal(v, "issuedAt"), optionalNumber(v, "revokedAt"))
  def encodeCap(v: AccountCap): js.Dynamic = js.Dynamic.literal(capId = v.capId, name = v.name, hash = v.hash, strong = v.strong, issuedBy = v.issuedBy, issuedAt = v.issuedAt, revokedAt = v.revokedAt.orUndefined)

  private def decodePending(v: js.Dynamic): PendingToken = PendingToken(string(v, "hash"), requiredTime(v, "expiresAt"), optionalText(v, "email"))
  private def encodePending(v: PendingToken): js.Dynamic = js.Dynamic.literal(hash = v.hash, expiresAt = v.expiresAt, email = v.email.orUndefined)

  def decodeState(values: js.Map[String, js.Any]): Option[AccountState] = values.get("profile").filterNot(missing).map { profile =>
    val decodedProfile = decodeProfile(profile.asInstanceOf[js.Dynamic])
    val activation: js.Any = values.get("activation").getOrElse(js.undefined)
    // activation が無い既存クライアント ID だけは、これまでどおり利用可能として読む
    val readiness = if (js.isUndefined(activation) && decodedProfile.kind == AccountKind.Client) AccountReadiness.Ready
    else AccountReadiness.parse(text(activation)).getOrElse(invalid())
    val credential = values.get("credential").filterNot(missing).map(PasswordCredentialCodec.decode)
    val state = AccountState(decodedProfile, decodeLevel(values.get("level").getOrElse(invalid()).asInstanceOf[js.Dynamic]),
      decodeRoles(values.get("roles").orNull), array(values.get("tokens").orNull).map(decodeToken), array(values.get("devices").orNull).map(decodeDevice), array(values.get("caps").orNull).map(decodeCap),
      credential, readiness, PendingKind.values.flatMap(kind => values.get(pendingKey(kind)).filterNot(missing).map(v => kind -> decodePending(v.asInstanceOf[js.Dynamic]))).toMap)
    if (!state.valid) invalid()
    state
  }
  def encodeState(state: AccountState): js.Dictionary[js.Any] = js.Dictionary[js.Any]("profile" -> encodeProfile(state.profile), "level" -> encodeLevel(state.level),
    "roles" -> encodeRoles(state.roles), "tokens" -> state.tokens.map(encodeToken).toJSArray, "devices" -> state.devices.map(encodeDevice).toJSArray, "caps" -> state.caps.map(encodeCap).toJSArray,
    "activation" -> state.readiness.code, "credential" -> state.credential.fold[js.Any](null)(PasswordCredentialCodec.encode),
    pendingKey(PendingKind.Email) -> state.pending.get(PendingKind.Email).fold[js.Any](null)(encodePending),
    pendingKey(PendingKind.Reset) -> state.pending.get(PendingKind.Reset).fold[js.Any](null)(encodePending))

  def describe(value: Either[AccountRejection, AccountDescription]): js.Dynamic = value.fold(error => js.Dynamic.literal(code = error.code), description)
  def description(v: AccountDescription): js.Dynamic = js.Dynamic.literal(code = "", aid = v.aid, uid = v.uid, kind = v.kind.code, generation = v.generation,
    expiresAt = v.expiresAt.orUndefined, lv = v.lv, roles = encodeRoles(v.roles), agreedAt = v.agreedAt, agreementVersion = v.agreementVersion)

  /** 本人確認後の応答。登録準備中なら確定の再開に使う uid だけを返す */
  def signIn(value: Either[AccountRejection, AccountDescription], uid: String): js.Dynamic = value match {
    case Left(AccountRejection.Pending) => js.Dynamic.literal(code = AccountRejection.Pending.code, uid = uid)
    case other => describe(other)
  }

  /** 保留トークンの発行結果。再設定では送り先のアドレスを返す */
  def pending(state: AccountState, kind: PendingKind): js.Dynamic =
    js.Dynamic.literal(code = "", email = (if (kind == PendingKind.Reset) state.profile.email else None).orUndefined)

  /** ログインの失敗。delay はアカウント全体の失敗数から決めた応答の遅延秒数 */
  def loginFailure(delay: Int): js.Dynamic = js.Dynamic.literal(code = AccountRejection.LoginFailed.code, delay = delay)

  def tokenDescription(value: AccountDescription, tokenId: String): js.Dynamic = {
    val result = description(value)
    result.tokenId = tokenId
    result
  }
  def capDescription(value: AccountDescription, capId: String): js.Dynamic = {
    val result = description(value)
    result.capId = capId
    result
  }

  /** 失効させたキャップの照合値と、再発行なら新しいキャップの表示名と強さ */
  def capRevocation(revoked: AccountCap, issued: Option[AccountCap]): js.Dynamic = js.Dynamic.literal(code = "", hash = revoked.hash,
    name = issued.map(_.name).orUndefined, strong = issued.map(_.strong).orUndefined)

  def decodePost(v: js.Dynamic): AccountPost = AccountPost(decimal(v, "t"), AccountVia.parse(string(v, "via", "web")).getOrElse(invalid()),
    optionalText(v, "tokenId"), optionalText(v, "capId"), string(v, "result", "posted"), string(v, "bbs"), string(v, "key"), string(v, "title"), integer(v, "resNum"),
    string(v, "excerpt"), string(v, "asn"), string(v, "asName"), string(v, "country"), string(v, "region"), string(v, "uaLabel"), optionalText(v, "deviceHash"), string(v, "id"), string(v, "shikibetsu"), string(v, "network"))
  def encodePost(v: AccountPost): js.Dynamic = js.Dynamic.literal(t = v.t, via = v.via.code, tokenId = v.tokenId.orUndefined, capId = v.capId.orUndefined, result = v.result,
    bbs = v.bbs, key = v.key, title = v.title, resNum = v.resNum, excerpt = v.excerpt, asn = v.asn, asName = v.asName, country = v.country, region = v.region,
    uaLabel = v.uaLabel, deviceHash = v.deviceHash.orUndefined, id = v.id, shikibetsu = v.shikibetsu, network = v.network)
  def decodeObservation(v: js.Dynamic): AccountObservation = AccountObservation(string(v, "deviceHash"), string(v, "keyIpHash"))
  def decodeLogPosition(value: js.Any): AccountLogPosition = if (missing(value)) AccountLogPosition() else {
    val n = number(value)
    if (n < 0 || n != math.floor(n) || n >= 9007199254740991d) invalid()
    AccountLogPosition(n.toLong)
  }
  def decodeLogPage(value: js.Any): AccountLogPage = if (missing(value)) AccountLogPage(-1, Vector.empty) else {
    val v = value.asInstanceOf[js.Dynamic]
    AccountLogPage(decodeLogPosition(field(v, "number")).next, array(field(v, "entries")).map(decodePost))
  }
  /** 専ブラキーの照合で拒否した記録。保存キーは rejections */
  val rejectionsKey: String = "rejections"
  def decodeRejections(value: js.Any): Vector[AccountPost] = if (missing(value)) Vector.empty else array(value).map(decodePost)
  def encodeRejections(entries: Vector[AccountPost]): js.Array[js.Dynamic] = entries.map(encodePost).toJSArray
  def encodeLogPage(page: AccountLogPage): js.Dynamic = js.Dynamic.literal(number = page.number.toDouble, entries = page.entries.map(encodePost).toJSArray)

  /** 本人向けのキー一覧から照合用ハッシュを除く */
  def inspect(state: AccountState, logs: Vector[AccountPost], rejections: Vector[AccountPost], now: Double): js.Dynamic = {
    val tokens = DedicatedBrowserTokens.retained(state.tokens, now).map { token =>
      js.Dynamic.literal(tokenId = token.tokenId, issuedAt = token.issuedAt, expiresAt = token.expiresAt, uaLabel = token.uaLabel.orUndefined,
        boundAt = token.boundAt.orUndefined, replacedTokenAt = token.replacedTokenAt.orUndefined, lastUsedAt = token.lastUsedAt.orUndefined,
        recentPosts = token.recentPosts.map(encodeTokenPost).toJSArray, revokedAt = token.revokedAt.orUndefined, revokeReason = token.revokeReason.map(_.code).orUndefined)
    }
    js.Dynamic.literal(code = "", account = description(state.description), tokens = tokens.toJSArray, deviceCount = state.devices.size,
      email = state.profile.email.orUndefined,
      pendingEmail = state.pending.get(PendingKind.Email).filter(_.expiresAt > now).flatMap(_.email).orUndefined,
      caps = {
        val posts = state.capPosts(logs)
        state.caps.map(c => js.Dynamic.literal(capId = c.capId, name = c.name, strong = c.strong, issuedAt = c.issuedAt, revokedAt = c.revokedAt.orUndefined, recentPosts = posts.getOrElse(c.capId, Vector.empty).map(p => encodeTokenPost(p.tokenPost)).toJSArray)).toJSArray
      },
      log = logs.map(encodePost).toJSArray, rejections = encodeRejections(rejections))
  }
}
