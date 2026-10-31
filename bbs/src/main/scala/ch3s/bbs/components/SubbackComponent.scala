package ch3s.bbs.components

import ch3s.common.domain.{BbsSubject, BbsSubjects}
import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.js
import scala.scalajs.js.URIUtils

case class SubbackProps(bbs: String, subjects: BbsSubjects, query: String = "", sort: String = "new")

/** Board DO の subject を使うスレ一覧。スレ立て時刻はスレキー(UNIX時刻)から出し、更新日時は現行subjectにないため空欄にする */
object SubbackComponent {
  final val Component = ScalaFnComponent[SubbackProps] { props =>
    val now = scala.scalajs.js.Date.now()
    val filtered = props.subjects.value.filter(t => SubjectText.decode(t.title.value).toLowerCase.contains(props.query.toLowerCase))
    val sorted = props.sort match {
      case "res" => filtered.sortBy(t => -t.resNum)
      case "speed" => filtered.sortBy(t => -speed(t, now))
      case _ => filtered
    }
    val (commonThreads, threads) = sorted.partition(common)
    <.div(^.className := "subback",
      <.details(^.className :=
        """common-threads my-2 border-t border-[#dce1e6] [&>summary]:px-4 [&>summary]:py-[9px] 
          | [&>summary>span]:ml-2.5 [&>summary>span]:text-xs [&>summary>span]:text-[#4d4d4d] max-[660px]:[&>summary]:px-[9px]""".stripMargin,
        <.summary(<.b("全板共通スレ"), <.span(s"${commonThreads.size}件")),
        heading, commonThreads.toVdomArray(row(_, props.bbs, now, true)),
        if commonThreads.isEmpty then <.p(^.className := "empty-state m-2 text-xs text-[#697785]", "全板共通スレはありません。") else EmptyVdom),
      <.div(^.className := "thread-list mt-[5px] border-t border-[#dcdcdc]", heading,
        <.div(^.className := s"list-row $rowGrid px-4 py-[11px] text-[13px] hover:bg-[#fafdff]", <.a(^.className := "text-[#06f] hover:underline",
          ^.href := s"/${URIUtils.encodeURIComponent(props.bbs)}/kako/", "↩過去ログ倉庫")),
        threads.toVdomArray(row(_, props.bbs, now, false)),
        if threads.isEmpty then <.p(^.className := "empty-state m-2 text-xs text-[#697785]", "該当するスレッドはありません。") else EmptyVdom),
      <.p(^.className := "list-note m-2 text-xs text-[#697785]", "新着順は板の並び順です。最終更新日時は未取得です。")
    )
  }
  private val rowGrid =
    """grid grid-cols-[minmax(0,1fr)_110px_85px_60px_50px_60px] items-center gap-2.5 border-b border-[#dce1e6] 
      | max-[660px]:grid-cols-[minmax(0,1fr)_40px_50px] max-[660px]:gap-2 max-[660px]:px-[9px]""".stripMargin
  private val createdFormat = js.Dynamic.newInstance(js.Dynamic.global.Intl.DateTimeFormat)("ja-JP", js.Dynamic.literal(
    year = "numeric", month = "2-digit", day = "2-digit", hour = "2-digit", minute = "2-digit", timeZone = "Asia/Tokyo"))

  private def row(thread: BbsSubject, bbs: String, now: Double, isCommon: Boolean): VdomElement = {
    val rate = if isCommon then "—" else f"${speed(thread, now)}%.1f"
    val threadBoard = if isCommon then "924" else bbs
    <.div(^.key := thread.key.toString, ^.className := s"list-row relative cursor-pointer $rowGrid px-4 py-[11px] text-[13px] hover:bg-[#fafdff]",
      VdomAttr("data-thread-key") := s"$threadBoard/${thread.key}", VdomAttr("data-res") := thread.resNum.toString,
      VdomAttr("data-common") := isCommon.toString,
      <.div(<.a(^.href := s"/test/read.cgi/${URIUtils.encodeURIComponent(threadBoard)}/${thread.key}/",
        ^.className := "thread-link text-black no-underline after:absolute after:inset-0 after:content-[''] hover:text-[#2464b5]", SubjectText.decode(thread.title.value)),
        <.div(^.className := "sub hidden text-xs text-[#697785] max-[660px]:block", s"勢い $rate")),
      <.span(^.className := "created tabular-nums text-[#aaa] max-[660px]:hidden", if isCommon then "—" else created(thread)),
      <.span(^.className := "when modified text-[#ff9025] max-[660px]:hidden", "—"),
      <.span(^.className := "speed numeric text-right tabular-nums text-[#cc3d3d] max-[660px]:hidden", rate),
      <.span(^.className := (if thread.key.toString.startsWith("924") then "new numeric cap-update text-right font-bold text-[#d00]"
      else "new numeric text-right font-bold text-black")),
      <.span(^.className := "numeric text-right tabular-nums", thread.resNum)
    )
  }

  private def speed(thread: BbsSubject, now: Double): Double = {
    if common(thread) then 0.0
    else thread.resNum / math.max(1.0 / 24, (now / 1000 - thread.key) / 86400)
  }

  private def common(thread: BbsSubject): Boolean = {
    val key = thread.key.toString
    key.startsWith("900") || key.startsWith("924")
  }

  private def created(thread: BbsSubject): String = {
    createdFormat.format(new js.Date(thread.key.toDouble * 1000)).asInstanceOf[String]
  }

  private def heading: VdomElement = {
    <.div(^.className := s"list-head $rowGrid px-4 py-3 text-xs", <.span("スレッド名"), <.span(^.className := "max-[660px]:hidden", "スレ立て"),
      <.span(^.className := "when max-[660px]:hidden", "最終更新"),
      <.span(^.className := "speed numeric text-right max-[660px]:hidden", "勢い"), <.span(^.className := "numeric text-right", "新着"),
      <.span(^.className := "numeric text-right", "レス"))
  }
}
