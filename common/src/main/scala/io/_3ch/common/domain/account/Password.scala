package io._3ch.common.domain.account

/** パスワードの Unicode・長さ・ユーザー ID の包含による拒否 */
enum PasswordRejection {
  case InvalidUnicode
  case Length
  case ContainsLoginId
}

/** NFC 正規化済みのパスワード。空白と大文字小文字を保ち、ログ表示には平文を出さない */
final class Password private (val value: String) {
  override def equals(other: Any): Boolean = other match {
    case that: Password => value == that.value
    case _ => false
  }
  override def hashCode(): Int = value.hashCode
  override def toString: String = "Password(<redacted>)"
}

object Password {
  private def validUnicode(value: String): Boolean = {
    value.indices.forall { i =>
      val c = value.charAt(i)
      if (c >= '\ud800' && c <= '\udbff') i + 1 < value.length && value.charAt(i + 1) >= '\udc00' && value.charAt(i + 1) <= '\udfff'
      else if (c >= '\udc00' && c <= '\udfff') i > 0 && value.charAt(i - 1) >= '\ud800' && value.charAt(i - 1) <= '\udbff'
      else true
    }
  }

  private def utf8Length(value: String): Int = value.foldLeft(0) { (bytes, c) =>
    bytes + (if (c <= 0x7f) 1 else if (c <= 0x7ff) 2 else if (c >= '\ud800' && c <= '\udfff') 2 else 3)
  }

  /** NFC 後の UTF-8 長を8〜128バイトで検証する。ID の包含は ASCII の大文字小文字を区別しない */
  def create(raw: String, loginId: LoginId): Either[PasswordRejection, Password] = {
    if (raw == null || !validUnicode(raw)) Left(PasswordRejection.InvalidUnicode)
    else {
      val value = AccountText.nfc(raw)
      val length = utf8Length(value)
      if (length < 8 || length > 128) Left(PasswordRejection.Length)
      else if (LoginId.lowercase(value).contains(loginId.value)) Left(PasswordRejection.ContainsLoginId)
      else Right(new Password(value))
    }
  }
}
