package io._3ch.common.domain.posting

import io._3ch.common.domain.account.Phoebe

/** Thread DO が書き込む直前に BBS_PHOEBE を判定し直すための投稿者側の条件。boardLevel はスレ設定に値が無いときの必要 Lv */
final case class PhoebeAdmission(lv: Int, exempt: Boolean, boardLevel: Int) {
  /** 保存されたスレ設定の値（無ければ板の値）で書き込めないときの必要 Lv。書き込めれば None */
  def refusal(threadValue: Option[String]): Option[Int] = {
    val required = threadValue.fold(boardLevel)(value => Phoebe.required(Some(value)))
    Option.when(!Phoebe.admits(lv, required, exempt))(required)
  }
}

object PhoebeAdmission {
  private val Prefix = "thread-write:level:"

  /** Thread DO が Lv 不足の拒否として返す文字列 */
  def code(required: Int): String = Prefix + required

  /** RPC の文字列から必要 Lv を読む。Lv 不足の拒否でなければ None */
  def requiredOf(code: String): Option[Int] =
    Option.when(code.startsWith(Prefix))(code.drop(Prefix.length)).filter(rest => rest.nonEmpty && rest.length <= 2 && rest.forall(c => c >= '0' && c <= '9'))
      .map(_.toInt).filter(_ <= Phoebe.MaxLevel)
}
