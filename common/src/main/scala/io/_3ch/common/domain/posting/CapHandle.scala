package io._3ch.common.domain.posting

/** KV で照合済みのキャップ表示名と、制限を迂回する強キャップの指定。 */
final case class CapHandle(name: String, strong: Boolean) {
  def displayName(postedName: String): String = (if (postedName.isEmpty) "" else postedName + "＠") + name + " ★"
}

object CapHandle {
  def fromStored(value: String): Option[CapHandle] = {
    if (value.isEmpty) None
    else {
      val strong = value.endsWith("★")
      val name = if (!strong) value else {
        val start = value.indexOf('★')
        val end = value.indexWhere(c => c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029', start)
        value.take(start) + (if (end < 0) "" else value.drop(end))
      }
      Some(CapHandle(name, strong))
    }
  }

  /** メールの最初の # の直後だけをパスワードとして読む。 */
  def password(mail: String): Option[String] = mail.split("#", -1).lift(1).filter(_.nonEmpty)
}
