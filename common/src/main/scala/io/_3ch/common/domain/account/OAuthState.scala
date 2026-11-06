package io._3ch.common.domain.account

/** Google 認可の目的。Link はクライアント ID からの移行、Delete は退会の再認証 */
enum OAuthPurpose(val code: String) {
  case SignIn extends OAuthPurpose("s")
  case Link extends OAuthPurpose("l")
  case Delete extends OAuthPurpose("d")
}

/** Google 認可の往復で保持する値。uid は新規登録・移行で使うユーザー ID、aid は移行・退会を始めたアカウント */
final case class OAuthState(state: String, nonce: String, verifier: String, device: String, returnPath: String,
  popup: Boolean, uid: Option[String], agreed: Boolean, expiresAt: Long, purpose: OAuthPurpose = OAuthPurpose.SignIn, aid: Option[String] = None) {
  def isCurrent(nowSeconds: Double): Boolean = nowSeconds.isFinite && expiresAt.toDouble > nowSeconds
}

/** 書式を確認した認可の Cookie。state はまだ署名を検証していない */
final case class SignedOAuthState(payload: String, state: OAuthState, signature: String)

/** Google 認可用 Cookie の書式。署名の計算と検証は呼び出し側で行う */
object OAuthStateCodec {
  val Name: String = "ch3s_oauth"
  /** 認可の往復に許す時間（秒） */
  val lifetime: Long = 600

  private val random = "[A-Za-z0-9_-]{43,128}"

  /** 署名する内容。値が書式に合わなければ None */
  def payload(value: OAuthState): Option[String] = Option.when(valid(value)) {
    Seq("2", value.state, value.nonce, value.verifier, value.device, value.returnPath, if (value.popup) "1" else "0",
      value.uid.getOrElse(""), if (value.agreed) "1" else "0", value.expiresAt.toString, value.purpose.code, value.aid.getOrElse("")).mkString("|")
  }

  def encode(value: OAuthState, signature: String): Option[String] =
    payload(value).filter(_ => signature.matches("[A-Za-z0-9_-]{21}[AQgw]")).map(_ + "|" + signature)

  /** Cookie の値を読む。署名の検証はしない */
  def parse(text: String): Option[SignedOAuthState] = Option(text).filter(_.length <= 1024).flatMap { raw =>
    raw.split("\\|", -1).toList match {
      case "2" :: state :: nonce :: verifier :: device :: returnPath :: popup :: uid :: agreed :: exp :: purpose :: aid :: sig :: Nil
        if Set("0", "1").contains(popup) && Set("0", "1").contains(agreed) && exp.matches("[0-9]{1,12}") && sig.matches("[A-Za-z0-9_-]{21}[AQgw]") &&
          OAuthPurpose.values.exists(_.code == purpose) =>
        val value = OAuthState(state, nonce, verifier, device, returnPath, popup == "1", Option.when(uid.nonEmpty)(uid), agreed == "1", exp.toLong,
          OAuthPurpose.values.find(_.code == purpose).get, Option.when(aid.nonEmpty)(aid))
        Option.when(valid(value))(SignedOAuthState(raw.substring(0, raw.lastIndexOf('|')), value, sig))
      case _ => None
    }
  }

  /** Cookie ヘッダから読む。同名の Cookie が複数ある場合は受け付けない */
  def fromHeader(header: String): Option[SignedOAuthState] = {
    val values = Option(header).toSeq.flatMap(_.split(";", -1)).map(_.trim).collect {
      case cookie if cookie.startsWith(Name + "=") => cookie.substring(Name.length + 1)
    }
    values.toList match {
      case value :: Nil => parse(value)
      case _ => None
    }
  }

  private def valid(value: OAuthState): Boolean =
    value.state.matches(random) && value.nonce.matches(random) && value.verifier.matches(random) &&
      (value.device.isEmpty || value.device.matches("[pgu](?:[0-9a-f]{16}|[0-9a-f]{18})")) &&
      value.returnPath.matches("/[A-Za-z0-9/_\\.\\-?=&%]*") && !value.returnPath.startsWith("//") && value.uid.forall(_.matches("[a-z0-9_]{3,16}")) && value.expiresAt >= 0 &&
      value.purpose != null && value.aid.forall(_.matches("[a-z0-9]{16}")) && (value.purpose == OAuthPurpose.SignIn) == value.aid.isEmpty
}
