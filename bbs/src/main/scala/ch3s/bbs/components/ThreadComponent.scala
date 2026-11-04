package ch3s.bbs.components

import ch3s.common.domain.{BoardId, ThreadKey}
import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.js.URIUtils

case class ThreadPost(number: Int, name: String, date: String, message: String, slip: String = "")

/**
 * スレッドコンポーネント要props
 * moreは1レス目の後ろに出す省略表示
 * read.cgiで全レスを出すときはNoneにすること
 */
case class ThreadProps(bbs: BoardId, time: String, key: ThreadKey, title: String, count: Int, posts: Seq[ThreadPost],
                       more: Option[String] = None)

/** スレッドフィードとスレッド画面で共通のスレタイ，レス，書き込み欄のコンポーネント */
object ThreadComponent {
  final val Component = ScalaFnComponent[ThreadProps] { props =>
    val key = props.key.value.toString
    val isCap = key.startsWith("924")
    val threadBoard = if key.startsWith("924") || key.startsWith("900") then "924" else props.bbs.value
    val (first, rest) = props.posts.splitAt(1)
    <.article(^.className := "thread mt-[5px] border-t border-[#dcdcdc] pt-[5px]",
      <.h2(^.className := "thread-title m-0 text-lg font-normal text-[red]",
        <.a(^.href := s"/test/read.cgi/${URIUtils.encodeURIComponent(threadBoard)}/$key/", ^.className := "text-inherit no-underline", props.title),
        <.small(s" (${props.count})")),
      first.toVdomArray(post(_, isCap)),
      props.more.whenDefined(label => <.div(^.className := "more py-[5px] text-center text-black", label)),
      rest.toVdomArray(post(_, isCap)),
      <.details(^.className := "reply-box [&>summary]:text-[#06f]", <.summary("書き込み欄"),
        PostingFormComponent.Component(PostingFormProps(props.bbs, props.time, Option(props.key))))
    )
  }

  private def post(value: ThreadPost, isCap: Boolean): VdomElement = {
    <.div(^.key := value.number, ^.className := "post mt-2.5 border-b border-dotted border-[#dcdcdc] pb-[5px]",
      <.span(^.className := "number text-[11px] font-bold text-[#4d4d4d]", s"${value.number} : "),
      <.span(^.className := s"${if isCap then "name cap" else "name"} ml-1.5 text-[11px] text-[#4d4d4d]",
        if isCap then "３ちゃんねる運営 ★" else value.name),
      if !isCap && value.slip.nonEmpty then <.span(^.className := "slip ml-1 text-[11px] font-bold text-[#333]", value.slip) else EmptyVdom,
      <.span(^.className := "date inline-flex gap-x-1 pl-4 text-[11px] text-[#aaa]", value.date),
      <.div(^.className := "message ml-10 text-base whitespace-pre-wrap text-black max-[660px]:ml-5", value.message)
    )
  }
}
