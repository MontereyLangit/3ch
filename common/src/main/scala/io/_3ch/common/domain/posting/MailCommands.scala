package io._3ch.common.domain.posting

/**
  * メール欄の認証コマンド。token は最初の !# の後ろ（空白の手前まで）、capPassword は !# を除いた欄を CapHandle と同じく読んだもの
  * plain は !#・キャップのパスワード部分・!auth を除いた残りで、sage の判定と画面に使う
  */
final case class MailCommands(plain: String, token: Option[String], capPassword: Option[String], auth: Boolean)

object MailCommands {
  private val TokenMark = "!#"
  private val AuthMark = "!auth"

  /** 空のメール欄 */
  val empty: MailCommands = MailCommands("", None, None, auth = false)

  /** メール欄を読む。キャップのパスワードは最初の # から次の # の手前までで、空白も含める */
  def parse(mail: String): MailCommands = {
    val token = Option(mail.indexOf(TokenMark)).filter(_ >= 0).map(start => mail.substring(start + TokenMark.length).takeWhile(c => !c.isWhitespace))
    val withoutTokens = removeTokens(mail)
    val plain = withoutTokens.indexOf('#') match {
      case -1 => withoutTokens
      case start =>
        val end = withoutTokens.indexOf('#', start + 1)
        withoutTokens.take(start) + (if (end < 0) "" else withoutTokens.drop(end))
    }
    MailCommands(plain.replace(AuthMark, ""), token, CapHandle.password(withoutTokens), mail.contains(AuthMark))
  }

  /** !# から空白の手前までをすべて取り除く */
  private def removeTokens(mail: String): String = {
    val start = mail.indexOf(TokenMark)
    if (start < 0) mail
    else {
      val end = mail.indexWhere(_.isWhitespace, start + TokenMark.length)
      mail.take(start) + removeTokens(if (end < 0) "" else mail.drop(end))
    }
  }
}
