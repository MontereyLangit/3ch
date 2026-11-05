package io._3ch.bbs.domain.posting

/** 名前欄のコマンド `!id`・`!no` を除いた名前と、指定の有無（docs/トリップ・ID廃止とスレ主表示.md §2.3, §2.4）。 */
final case class NameCommands(name: String, showId: Boolean, noNusi: Boolean)

object NameCommands {
  private val IdCommand = "!id(?![A-Za-z0-9_])".r
  private val NoCommand = "!no(?![A-Za-z0-9_])".r

  /** `!id`・`!no` を全て取り除く。取り除いたときだけ前後の空白も落とす。 */
  def parse(name: String): NameCommands = {
    val showId = IdCommand.findFirstIn(name).isDefined
    val noNusi = NoCommand.findFirstIn(name).isDefined
    if (showId || noNusi) NameCommands(NoCommand.replaceAllIn(IdCommand.replaceAllIn(name, ""), "").trim, showId, noNusi)
    else NameCommands(name, showId = false, noNusi = false)
  }
}
