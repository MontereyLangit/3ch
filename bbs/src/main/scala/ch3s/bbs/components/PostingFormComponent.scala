package ch3s.bbs.components

import japgolly.scalajs.react.ScalaFnComponent
import japgolly.scalajs.react.vdom.html_<^.*

case class PostingFormProps(bbs: String, time: String, key: Option[String] = None, dummy: Boolean = false)

/** bbs.cgi の既存フィールドを使う投稿欄。ダミーフィードからは送信しない。 */
object PostingFormComponent {
  final val Component = ScalaFnComponent[PostingFormProps] { props =>
    <.form(^.className := "posting-form mt-2 mb-3 ml-[2em] text-base [&_label]:inline-block [&_label]:max-w-full max-[660px]:ml-3",
      ^.method := "post", ^.action := "/test/bbs.cgi", ^.acceptCharset := "UTF-8",
      <.fieldset(^.className := "m-0 min-w-0 border-0 p-0 disabled:opacity-60", ^.disabled := props.dummy,
        <.details(<.summary("名前・E-mail"),
          <.label("名前：", <.input(^.className := controlClass, ^.`type` := "text", ^.name := "FROM", ^.size := 24)),
          <.label(" E-mail：", <.input(^.className := controlClass, ^.`type` := "text", ^.name := "mail", ^.size := 24))),
        props.key.fold[VdomNode](
          <.label("タイトル：", <.input(^.className := controlClass, ^.`type` := "text", ^.name := "subject", ^.size := 80, ^.required := true)))(_ => EmptyVdom),
        <.button(^.className := s"$controlClass cursor-pointer", ^.`type` := "submit", ^.name := "submit", ^.value := "書き込む",
          if props.key.isEmpty then "新規スレッド作成" else "書き込む"),
        <.label(^.className := "message-label block!", "本文", <.textarea(^.className := s"$controlClass block w-full resize-y",
          ^.name := "MESSAGE", ^.rows := 8, ^.cols := 80, ^.required := true)),
        <.input(^.`type` := "hidden", ^.name := "bbs", ^.value := props.bbs),
        <.input(^.`type` := "hidden", ^.name := "time", ^.value := props.time),
        props.key.toVdomArray(key => <.input(^.key := key, ^.`type` := "hidden", ^.name := "key", ^.value := key))
      ),
      if props.dummy then <.small("ダミーデータのため書き込みはできません。") else EmptyVdom
    )
  }
  private val controlClass = "max-w-full border border-[#d6dce3] bg-white px-2.5 py-1 hover:border-[#b9c4d0]"
}
