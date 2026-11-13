package io._3ch.common.domain.account

/** 登録方式。クライアント ID だけが書き込みから一年で失効する */
enum AccountKind(val code: String) {
  case Client extends AccountKind("client")
  case Password extends AccountKind("password")
  case Google extends AccountKind("google")
}
object AccountKind {
  def parse(code: String): Option[AccountKind] = AccountKind.values.find(_.code == code)
}

/** アカウントの利用状態 */
enum AccountStatus(val code: String) {
  case Active extends AccountStatus("active")
  case Frozen extends AccountStatus("frozen")
  case Banned extends AccountStatus("banned")
  case Deleted extends AccountStatus("deleted")
}
object AccountStatus {
  def parse(code: String): Option[AccountStatus] = AccountStatus.values.find(_.code == code)
}

/** アカウントに付与する役割 */
enum AccountRole(val code: String) {
  case Admin extends AccountRole("admin")
  case Delete extends AccountRole("delete")
  case Regulate extends AccountRole("regulate")
  case Cap extends AccountRole("cap")
}
object AccountRole {
  def parse(code: String): Option[AccountRole] = AccountRole.values.find(_.code == code)
}

/** アカウントの役割。クライアント ID には付与しない */
final case class Roles(values: Set[AccountRole] = Set.empty) {
  def permitted(kind: AccountKind): Boolean = kind != AccountKind.Client || values.isEmpty
}

/** 公開 ID と認証方式、同意・状態・世代。資格情報は別に保存する */
final case class AccountProfile(
  aid: String,
  uid: String,
  kind: AccountKind,
  createdAt: Double,
  expiresAt: Option[Double],
  agreedAt: Double,
  agreementVersion: String,
  status: AccountStatus = AccountStatus.Active,
  generation: Int = 0,
  loginId: Option[String] = None,
  email: Option[String] = None,
  googleSubHash: Option[String] = None,
  emailVerifiedAt: Option[Double] = None
) {
  def valid: Boolean = aid.matches("[a-z0-9]{16}") && uid.matches("[a-z0-9_]{3,16}") &&
    AccountProfile.validTime(createdAt) && AccountProfile.validTime(agreedAt) && agreementVersion.nonEmpty && generation >= 0 &&
    expiresAt.forall(AccountProfile.validTime) && emailVerifiedAt.forall(AccountProfile.validTime) &&
    (kind != AccountKind.Client || (uid.matches("[a-z0-9]{8}") && expiresAt.exists(_ > createdAt))) &&
    (kind != AccountKind.Password || loginId.exists(id => id == uid && LoginId.parse(id).exists(_.value == id)))

  def expired(now: Double): Boolean = kind == AccountKind.Client && expiresAt.forall(_ <= now)

  /** クライアント ID の期限を成功投稿から365日後へ延ばす。失効の確認は呼び出し元で行う */
  def afterPost(now: Double): AccountProfile = if (kind == AccountKind.Client) copy(expiresAt = Some(now + AccountProfile.clientLifetime)) else this
}
object AccountProfile {
  val clientLifetime: Double = 365d * 86400
  def validTime(now: Double): Boolean = now.isFinite && now >= 0 && now == math.floor(now)
}

/** アカウントに保持する Lv と、その段階の開始時刻・書き込み数 */
final case class AccountLevel(lv: Int, lvSince: Double, lvPosts: Int = 0, lvOverride: Option[Int] = None) {
  def valid: Boolean = lv >= 0 && lv <= 10 && AccountProfile.validTime(lvSince) && lvPosts >= 0 && lvOverride.forall(n => n >= 0 && n <= 10)
  def effective: Int = lvOverride.getOrElse(lv)

  /** 成功投稿を数え、経過時間と投稿数が揃えば一段だけ上げて両方を数え直す。固定中は上げない */
  def afterPost(kind: AccountKind, now: Double): AccountLevel = {
    val counted = copy(lvPosts = if (lvPosts == Int.MaxValue) Int.MaxValue else lvPosts + 1)
    val reached = lvOverride.isEmpty && Phoebe.seconds(kind, lv).zip(Phoebe.posts(kind, lv)).exists { case (seconds, posts) =>
      now - lvSince >= seconds && counted.lvPosts >= posts
    }
    if (reached) AccountLevel(lv + 1, now) else counted
  }

  /** 管理者による固定値を設定する。None で固定を外す */
  def overridden(value: Option[Int]): Option[AccountLevel] =
    Option.when(value.forall(n => n >= 0 && n <= Phoebe.MaxLevel))(copy(lvOverride = value))
}

/** 認証基盤の拒否理由。表示文言は HTTP 層で組み立てる */
enum AccountRejection(val code: String) {
  case Invalid extends AccountRejection("auth:invalid")
  case Exists extends AccountRejection("auth:account:exists")
  case Missing extends AccountRejection("auth:account:missing")
  case Pending extends AccountRejection("auth:account:pending")
  case Generation extends AccountRejection("auth:session:invalid")
  case Expired extends AccountRejection("auth:session:expired")
  case Frozen extends AccountRejection("auth:frozen")
  case Banned extends AccountRejection("auth:banned")
  case Deleted extends AccountRejection("auth:deleted")
  case Agreement extends AccountRejection("auth:agreement:required")
  case RolesForbidden extends AccountRejection("auth:roles:forbidden")
  case TokenUnknown extends AccountRejection("auth:token:unknown")
  case TokenRevoked extends AccountRejection("auth:token:revoked")
  case TokenUaMismatch extends AccountRejection("auth:token:ua-mismatch")
  case TokenLimit extends AccountRejection("auth:token:limit")
  case CapRevoked extends AccountRejection("auth:cap:revoked")
  case IndexConflict extends AccountRejection("auth:index:conflict")
  case IndexOwnership extends AccountRejection("auth:index:ownership")
  case IndexPermanent extends AccountRejection("auth:index:permanent")
  case LoginFailed extends AccountRejection("auth:login:failed")
}
