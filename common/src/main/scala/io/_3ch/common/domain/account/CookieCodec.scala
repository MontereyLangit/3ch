package io._3ch.common.domain.account

/** 書式を確認した Cookie。claims はまだ署名を検証していない */
final case class SignedSession(payload: String, claims: SessionClaims, signature: String)

/** セッション Cookie の書式。署名の計算と検証は呼び出し側で行う */
object CookieCodec {
  /** セッション Cookie の名前 */
  val Name: String = "__Host-ch3s_sid"

  /** 署名する 8 フィールド。値が書式に合わなければ None */
  def payload(claims: SessionClaims): Option[String] = {
    val bind = claims.binding.fold("")(binding => s"t:${binding.tokenId}:${binding.uaHash}")
    val fields = Seq("1", claims.aid, claims.uid, claims.generation.toString, claims.expiresAt.toString, claims.lv.toString, claims.device, bind)
    Option.when(valid(claims))(fields.mkString("."))
  }

  /** 内容と署名を 9 フィールドの値にする */
  def encode(claims: SessionClaims, signature: String): Option[String] =
    payload(claims).filter(_ => validSignature(signature)).map(_ + "." + signature)

  /** 9 フィールドの値を読む。署名の検証はしない */
  def parse(value: String): Option[SignedSession] = {
    Option(value).filter(_.length <= 512).flatMap { text =>
      text.split("\\.", -1).toList match {
        case "1" :: aid :: uid :: gen :: exp :: lv :: device :: bind :: sig :: Nil =>
          for {
            generation <- unsigned(gen)
            expiresAt <- integer(exp)
            level <- unsigned(lv).filter(_ <= 10).map(_.toInt)
            binding <- parseBinding(bind)
            claims = SessionClaims(aid, uid, generation, expiresAt, level, device, binding)
            if valid(claims) && validSignature(sig)
          } yield SignedSession(text.substring(0, text.lastIndexOf('.')), claims, sig)
        case _ => None
      }
    }
  }

  /** Cookie ヘッダから読む。同名の Cookie が複数ある場合は受け付けない */
  def fromHeader(header: String): Option[SignedSession] = {
    val values = Option(header).toSeq.flatMap(_.split(";", -1)).map(_.trim).collect {
      case cookie if cookie.startsWith(Name + "=") => cookie.substring(Name.length + 1)
    }
    values.toList match {
      case value :: Nil => parse(value)
      case _ => None
    }
  }

  private def valid(claims: SessionClaims): Boolean =
    claims.aid.matches("[a-z0-9]{16}") && claims.uid.matches("[a-z0-9_]{3,16}") &&
      claims.generation >= 0 && claims.lv >= 0 && claims.lv <= 10 &&
      (claims.device.isEmpty || claims.device.matches("[pgu](?:[0-9a-f]{16}|[0-9a-f]{18})")) &&
      claims.binding.forall(binding => binding.tokenId.matches("[a-z0-9]{1,64}") && binding.uaHash.matches("[0-9a-f]{16}"))

  private def validSignature(value: String): Boolean = value.matches("[A-Za-z0-9_-]{21}[AQgw]")

  private def unsigned(value: String): Option[Long] =
    Option.when(value.matches("[0-9]+"))(value).flatMap(_.toLongOption)

  private def integer(value: String): Option[Long] =
    Option.when(value.matches("-?[0-9]+"))(value).flatMap(_.toLongOption)

  private def parseBinding(value: String): Option[Option[SessionBinding]] = value.split(":", -1).toList match {
    case "" :: Nil => Some(None)
    case "t" :: tokenId :: uaHash :: Nil => Some(Some(SessionBinding(tokenId, uaHash)))
    case _ => None
  }
}
