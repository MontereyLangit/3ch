package io._3ch.common.domain.posting

import io._3ch.common.domain.account.Phoebe

/** 本文先頭のスレ Lv の指定。!バルサン は Lv3、!SETTING:BBS_PHOEBE:n は Lv n を求める */
enum PhoebeCommand {
  case Balsan
  case Setting(value: Int)

  /** 求める Lv */
  def level: Int = this match {
    case Balsan => 3
    case Setting(n) => n
  }

  /** スレ立て人か staff ならスレ設定への書き込みにする。下げてよいのは staff の !SETTING だけ。どちらでもなければ None。staff 以外が求められる Lv は自分の Lv（lv）まで */
  def request(owner: Boolean, staff: Boolean, boardLevel: Int, lv: Int): Option[ThreadPhoebeRequest] =
    Option.when(owner || staff)(ThreadPhoebeRequest(if (staff) level else math.min(level, lv), lower = staff && this != Balsan, boardLevel))
}

object PhoebeCommand {
  private val BalsanText = "!バルサン"
  private val SettingText = "!SETTING:BBS_PHOEBE:"

  /** 本文の先頭にあるコマンドを読む。直後は本文の終わりか空白か改行。0〜10 以外の n は読まない */
  def parse(message: String): Option[PhoebeCommand] = {
    def ended(rest: String): Boolean = rest.isEmpty || rest.head == ' ' || rest.startsWith("<br>")
    if (message.startsWith(BalsanText) && ended(message.drop(BalsanText.length))) Some(Balsan)
    else if (message.startsWith(SettingText)) {
      val rest = message.drop(SettingText.length)
      val digits = rest.takeWhile(c => c >= '0' && c <= '9')
      Option.when(digits.nonEmpty && digits.length <= 2 && digits.toInt <= Phoebe.MaxLevel && ended(rest.drop(digits.length)))(Setting(digits.toInt))
    } else None
  }
}

/** Thread DO でスレ設定の BBS_PHOEBE を書き換える要求。boardLevel はスレ設定に値が無いときの現在値 */
final case class ThreadPhoebeRequest(level: Int, lower: Boolean, boardLevel: Int) {
  /** スレ設定の現在の値から書き込む値を決める。上げるときと、下げてよい要求で下げるときだけ Some */
  def next(threadValue: Option[String]): Option[Int] = {
    val current = threadValue.fold(boardLevel)(value => Phoebe.required(Some(value)))
    Option.when(level > current || (lower && level < current))(level)
  }
}
