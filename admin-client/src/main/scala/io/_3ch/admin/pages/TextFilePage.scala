package io._3ch.admin.pages

import io._3ch.admin.api.AdminApiClient
import io._3ch.admin.components.{Confirm, StatusBanner, Styles}
import io._3ch.admin.{LoadState, Loaded, Nav, SaveState}
import io._3ch.common.admin.{AdminCodec, TextFile}
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 1 つのファイルを読み込み時の ETag で条件付きに保存する画面（規制ルール・板一覧） */
object TextFilePage {
  /** api は /admin/api より後のパス、label は入力欄の名前、help は「書き方」の枠 */
  final case class Props(nav: Nav, title: String, file: String, api: String, label: String, help: VdomNode)


  final val Component = ScalaFnComponent[Props] { props =>
    for {
      loaded <- Loaded.useLoad(props.api)(() => AdminApiClient.get(props.api)(AdminCodec.textFile))
      draft <- useState(Option.empty[String])
      status <- useState[SaveState](SaveState.Idle)
      dirty = loaded.state match {
        case LoadState.Ready(file) => draft.value.exists(_ != file.text)
        case _ => false
      }
      _ <- useEffectWithDeps(dirty)(props.nav.setDirty)
    } yield {
      val reopen = Confirm(dirty, Confirm.DiscardMessage)(draft.setState(None) >> status.setState(SaveState.Idle) >> loaded.reload)
      <.div(
        <.h1(^.className := Styles.h1, props.title, " ", <.small(^.className := Styles.small, props.file)),
        StatusBanner(status.value, Some(reopen)),
        loaded.state match {
          case LoadState.Loading => <.p(^.className := Styles.notice, "読み込み中…")
          case LoadState.Failed(error) => <.p(^.className := Styles.error, error.message)
          case LoadState.Ready(file) =>
            val text = draft.value.getOrElse(file.text)
            val save = status.setState(SaveState.Saving) >> Callback {
              AdminApiClient.put(props.api, AdminCodec.textFile(TextFile(text, file.etag)))(AdminCodec.saved).foreach {
                case Right(saved) => (loaded.replace(TextFile(text, saved.etag)) >> draft.setState(None) >> status.setState(SaveState.Saved("保存しました。"))).runNow()
                case Left(error) => status.setState(SaveState.from(error)).runNow()
              }
            }
            <.form(^.onSubmit ==> (e => e.preventDefaultCB >> save),
              <.div(^.className := "mb-3 border border-l-4 border-[#dce1e6] border-l-[#b9c4d0] bg-white px-3.5 py-2.5",
                <.h2(^.className := Styles.h2, <.label(^.htmlFor := "file-text", s"■ ${props.label}")),
                <.textarea(^.id := "file-text", ^.className := Styles.textarea, ^.rows := 18, ^.spellCheck := false, ^.value := text,
                  ^.onChange ==> ((e: ReactEventFromTextArea) => draft.setState(Some(e.target.value)))),
                status.value.field("text").fold(EmptyVdom)(message => <.p(^.className := s"${Styles.error} mt-1", message)),
                <.div(^.className := Styles.help, props.help)),
              <.div(^.className := "flex flex-wrap items-center justify-end gap-2",
                if (dirty) <.span(^.className := Styles.notice, "未保存の変更があります。") else EmptyVdom,
                <.button(^.`type` := "button", ^.className := Styles.button, ^.disabled := !dirty || status.value.busy,
                  ^.onClick --> (draft.setState(None) >> status.setState(SaveState.Idle)), "元に戻す"),
                <.button(^.`type` := "submit", ^.className := Styles.primary, ^.disabled := !dirty || status.value.busy, "保存")))
        })
    }
  }
}
