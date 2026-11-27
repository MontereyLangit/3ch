package io._3ch.common.domain.account

/** 所有者に結び付いたキャップ。hash には従来の KV 照合値をそのまま入れる */
final case class AccountCap(capId: String, name: String, hash: String, strong: Boolean, issuedBy: String, issuedAt: Double, revokedAt: Option[Double] = None) {
  def valid: Boolean = DedicatedBrowserTokens.validId(capId) && name.nonEmpty && hash.nonEmpty &&
    issuedBy.matches("[a-z0-9]{16}") && AccountProfile.validTime(issuedAt) &&
    revokedAt.forall(t => AccountProfile.validTime(t) && t >= issuedAt)

  def active: Boolean = revokedAt.isEmpty

  /** KV の CAP に書く表示名を返す。強キャップは末尾に ★ を付ける */
  def storedName: String = if (strong) name + "★" else name
}

/** キャップの表示名の誤り */
enum CapNameRejection {
  case Length, Character
}

/** キャップの表示名。名前欄に「名前＠表示名 ★」の形で出るため、HTML とキャップ・トリップの印に使う文字は含められない */
object CapName {
  val MaxLength: Int = 16
  private val Forbidden = "<>&\"'#＠★☆◆◇"

  /** 前後の空白を除いた長さを 1〜16 文字に限り、記号と制御文字は拒否する */
  def parse(raw: String): Either[CapNameRejection, String] = {
    val name = Option(raw).getOrElse("").trim
    if (name.isEmpty || name.codePointCount(0, name.length) > MaxLength) Left(CapNameRejection.Length)
    else if (name.exists(c => Forbidden.contains(c) || c.isControl || Character.getType(c) == Character.FORMAT || c == ' ' || c == ' '))
      Left(CapNameRejection.Character)
    else Right(name)
  }
}
