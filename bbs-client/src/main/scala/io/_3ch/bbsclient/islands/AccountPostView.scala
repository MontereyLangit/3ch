package io._3ch.bbsclient.islands

import io._3ch.common.domain.SubjectText
import io._3ch.common.text.JstTime
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.js
import scala.scalajs.js.URIUtils

/** アカウント設定画面の props の読み取りと、書き込み記録の 1 行 */
private[islands] object AccountPostView {
  val button = "cursor-pointer border border-[#d6dce3] bg-white px-3 py-1 disabled:opacity-50"

  def number(raw: js.Object, name: String): Option[Double] = {
    val value = raw.asInstanceOf[js.Dynamic].selectDynamic(name)
    Option.when(js.typeOf(value) == "number")(value.asInstanceOf[Double]).filter(_.isFinite)
  }
  def objects(raw: js.Object, name: String): List[js.Object] =
    raw.asInstanceOf[js.Dynamic].selectDynamic(name).asInstanceOf[js.UndefOr[js.Array[js.Object]]].toOption.fold(List.empty[js.Object])(_.toList)
  def time(raw: js.Object, name: String): String = number(raw, name).filter(_ > 0).fold("")(JstTime.dateTime)

  /** 日時・書き込み先（スレへのリンク）・接続元・本文の先頭 */
  def apply(raw: js.Object): VdomElement = {
    val bbs = AuthClient.text(raw, "bbs")
    val key = AuthClient.text(raw, "key")
    val resNum = number(raw, "resNum").fold(0)(_.toInt)
    val path = s"/test/read.cgi/${URIUtils.encodeURIComponent(bbs)}/${URIUtils.encodeURIComponent(key)}/$resNum"
    <.div(^.className := "mt-2 text-xs", <.span(^.className := "mr-2 text-[#697785]", time(raw, "t")),
      <.a(^.href := path, ^.className := "text-[#06f] hover:underline", s"$bbs › ${SubjectText.decode(AuthClient.text(raw, "title"))} >>$resNum"),
      <.span(^.className := "ml-2 text-[#697785]", AuthClient.text(raw, "network")),
      <.p(^.className := "mt-1 whitespace-pre-wrap", SubjectText.decode(AuthClient.text(raw, "excerpt"))))
  }
}
