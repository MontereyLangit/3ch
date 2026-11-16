package io._3ch.bbsclient.islands

import io._3ch.common.islands.AccountPostItemProps
import io._3ch.common.domain.SubjectText
import io._3ch.common.text.JstTime
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.js
import scala.scalajs.js.URIUtils

/** アカウント設定画面の時刻の表示と、書き込み記録の 1 行 */
private[islands] object AccountPostView {
  val button = "cursor-pointer border border-[#d6dce3] bg-white px-3 py-1 disabled:opacity-50"

  /** 有限の数値なら Some */
  def number(value: js.UndefOr[Double]): Option[Double] = value.toOption.filter(_.isFinite)
  def list[A](value: js.UndefOr[js.Array[A]]): List[A] = value.toOption.fold(List.empty[A])(_.toList)
  /** UNIX 秒を日本時間で出す。値が無ければ空 */
  def time(value: js.UndefOr[Double]): String = number(value).filter(_ > 0).fold("")(JstTime.dateTime)

  /** 日時・書き込み先（スレへのリンク）・接続元・本文の先頭 */
  def apply(raw: AccountPostItemProps): VdomElement = {
    val bbs = raw.bbs
    val key = raw.key
    val resNum = raw.resNum
    val path = s"/test/read.cgi/${URIUtils.encodeURIComponent(bbs)}/${URIUtils.encodeURIComponent(key)}/$resNum"
    <.div(^.className := "mt-2 text-xs", <.span(^.className := "mr-2 text-[#697785]", time(raw.t)),
      <.a(^.href := path, ^.className := "text-[#06f] hover:underline", s"$bbs › ${SubjectText.decode(raw.title)} >>$resNum"),
      <.span(^.className := "ml-2 text-[#697785]", raw.network),
      <.p(^.className := "mt-1 whitespace-pre-wrap", SubjectText.decode(raw.excerpt)))
  }
}
