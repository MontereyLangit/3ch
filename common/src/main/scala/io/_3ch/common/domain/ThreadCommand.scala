package io._3ch.common.domain

/** スレ立て時コマンド。`field` は "MESSAGE" / "FROM" / "mail"。 */
case class ThreadCommand(text: String, label: String, field: String, description: String)

object ThreadCommand {
  /** サーバーがコマンドを解釈するようになるまで空。 */
  val all: Seq[ThreadCommand] = Nil
}
