package io._3ch.admin.pages

import io._3ch.admin.api.AdminApiClient
import io._3ch.admin.components.{Confirm, Link, StatusBanner, Styles}
import io._3ch.admin.{AdminPage, LoadState, Loaded, Nav, SaveState}
import io._3ch.common.admin.{AdminCodec, ResView, ThreadAction, ThreadDetail}
import io._3ch.common.domain.SubjectText
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** スレの管理。停止・再開・削除・移動、レスの編集・あぼーん、dat と投稿者ログの閲覧 */
object ThreadPage {
  final case class Props(nav: Nav, bbs: String, key: String)

  /** 編集中のレス */
  final case class ResDraft(number: Int, name: String, mail: String, message: String) {
    def changed(res: ResView): Boolean = name != res.edit.name || mail != res.edit.mail || message != res.edit.message
  }

  /** dat の 1 行目のタイトル */
  def title(detail: ThreadDetail): String = SubjectText.decode(detail.dat.split("\n", 2).headOption.flatMap(_.split("<>", -1).lift(4)).getOrElse(""))

  /** 名前欄の表示。太字の切り替えのタグを除く */
  def displayName(res: ResView): String = res.edit.name.replace("</b>", "").replace("<b>", "")

  def state(detail: ThreadDetail): String =
    if (detail.hidden) "削除・移動済み" + detail.movedTo.fold("")(to => s"（移動先 $to）")
    else detail.movingTo.fold(if (detail.stopped) "停止中" else "公開中")(to => s"移動中（移動先 $to）")

  private def stateClass(detail: ThreadDetail): String =
    if (detail.hidden) "inline-block border border-[#d6dce3] bg-[#f4f6f8] px-2 py-px text-xs font-normal text-[#7a8794]"
    else if (detail.stopped || detail.movingTo.isDefined) "inline-block border border-[#f3d18a] bg-[#fff8e6] px-2 py-px text-xs font-normal text-[#8a5a00]"
    else "inline-block border border-[#b5dfc3] bg-[#e7f5ec] px-2 py-px text-xs font-normal text-[#1a7f37]"

  private def done(action: ThreadAction): String = action match {
    case ThreadAction.Stop => "書き込みを停止しました。"
    case ThreadAction.Resume => "書き込みの停止を解除しました。"
    case ThreadAction.Delete => "スレを削除しました。本文と投稿者ログは保持しています。"
    case ThreadAction.Move(board, key) => s"$board/$key へ移動しました。"
    case ThreadAction.EditRes(number, _, _, _) => s">>${number}を編集しました。"
    case ThreadAction.DeleteRes(number) => s">>${number}をあぼーんにしました。"
  }

  final val Component = ScalaFnComponent[Props] { props =>
    for {
      loaded <- Loaded.useLoad(s"${props.bbs}/${props.key}")(() => AdminApiClient.get(s"/boards/${props.bbs}/threads/${props.key}/")(AdminCodec.threadDetail))
      status <- useState[SaveState](SaveState.Idle)
      editing <- useState(Option.empty[ResDraft])
      target <- useState((props.bbs, props.key))
      dirty = (loaded.state, editing.value) match {
        case (LoadState.Ready(detail), Some(draft)) => detail.res.find(_.number == draft.number).forall(draft.changed)
        case _ => false
      }
      _ <- useEffectWithDeps(dirty)(props.nav.setDirty)
    } yield {
      val nav = props.nav
      val busy = status.value.busy
      def run(action: ThreadAction, confirm: Option[String] = None): Callback =
        Confirm(confirm.isDefined, confirm.getOrElse(""))(status.setState(SaveState.Saving) >> Callback {
          AdminApiClient.post(s"/boards/${props.bbs}/threads/${props.key}/actions", AdminCodec.threadAction(action))(_ => Right(())).foreach {
            case Right(_) => (status.setState(SaveState.Saved(done(action))) >> editing.setState(None) >> loaded.reload).runNow()
            case Left(error) => status.setState(SaveState.from(error)).runNow()
          }
        })
      def fieldError(name: String): VdomNode = status.value.field(name).fold(EmptyVdom)(message => <.p(^.className := s"${Styles.error} mt-1", message))
      def editor(res: ResView, draft: ResDraft): VdomElement = <.form(^.className := "mt-2 ml-10",
        ^.onSubmit ==> (e => e.preventDefaultCB >> run(ThreadAction.EditRes(draft.number, draft.name, draft.mail, draft.message))),
        <.div(^.className := "mb-2.5", <.label(^.htmlFor := s"res-name-${res.number}", ^.className := "mb-[3px] block font-bold", "名前"),
          <.input(^.id := s"res-name-${res.number}", ^.`type` := "text", ^.className := s"${Styles.input} sm:w-3/5", ^.value := draft.name,
            ^.onChange ==> ((e: ReactEventFromInput) => editing.setState(Some(draft.copy(name = e.target.value))))), fieldError("name")),
        <.div(^.className := "mb-2.5", <.label(^.htmlFor := s"res-mail-${res.number}", ^.className := "mb-[3px] block font-bold", "メール"),
          <.input(^.id := s"res-mail-${res.number}", ^.`type` := "text", ^.className := s"${Styles.input} sm:w-3/5", ^.value := draft.mail,
            ^.onChange ==> ((e: ReactEventFromInput) => editing.setState(Some(draft.copy(mail = e.target.value))))), fieldError("mail")),
        <.div(^.className := "mb-2.5", <.label(^.htmlFor := s"res-message-${res.number}", ^.className := "mb-[3px] block font-bold", "本文"),
          <.textarea(^.id := s"res-message-${res.number}", ^.className := Styles.textarea, ^.rows := 5, ^.value := draft.message,
            ^.onChange ==> ((e: ReactEventFromTextArea) => editing.setState(Some(draft.copy(message = e.target.value))))), fieldError("message")),
        <.div(^.className := "flex flex-wrap items-center gap-2",
          <.button(^.`type` := "submit", ^.className := Styles.primary, ^.disabled := busy, "保存"),
          <.button(^.`type` := "button", ^.className := Styles.button, ^.onClick --> editing.setState(None), "取り消し"),
          <.span(^.className := Styles.notice, "HTMLはエスケープします。投稿日時と投稿者ログは変わりません。")))
      def resRow(detail: ThreadDetail, res: ResView): VdomElement = {
        val draft = editing.value.filter(_.number == res.number)
        val aborn = res.name == "あぼーん" && res.message == "あぼーん"
        <.div(^.key := res.number, ^.id := s"res-${res.number}",
          ^.className := (if (draft.isDefined) "my-1 border border-[#f3dca8] bg-[#fffdf5] px-2.5 py-2" else "border-t border-[#eef1f4] py-2"),
          if (draft.isEmpty && !detail.hidden) <.span(^.className := "float-right text-xs",
            <.button(^.`type` := "button", ^.className := Styles.textButton, ^.disabled := busy,
              ^.onClick --> editing.setState(Some(ResDraft(res.number, res.edit.name, res.edit.mail, res.edit.message))), "編集"),
            if (aborn) EmptyVdom else <.button(^.`type` := "button", ^.className := s"${Styles.textButton} text-[#c00]", ^.disabled := busy,
              ^.onClick --> run(ThreadAction.DeleteRes(res.number), Some(s">>${res.number}をあぼーんにしますか？レス番号と投稿者ログは残ります。")), "あぼーん"))
          else EmptyVdom,
          <.div(^.className := "text-[13px]", <.span(^.className := "font-bold", res.number),
            <.span(^.className := s"ml-3 font-bold ${if (aborn) "text-[#aaa]" else "text-[green]"}", displayName(res)),
            if (res.edit.mail.nonEmpty) <.span(^.className := "ml-1.5 text-[#697785]", s"[${res.edit.mail}]") else EmptyVdom,
            <.span(^.className := "ml-1.5 text-[#666]", res.date),
            if (draft.isDefined) <.b(^.className := "ml-2 text-xs", "編集中") else EmptyVdom),
          draft.fold[VdomNode](<.div(^.className := s"mt-1 ml-10 whitespace-pre-wrap leading-relaxed ${if (aborn) "text-[#aaa]" else ""}", res.edit.message))(editor(res, _)))
      }
      <.div(
        <.p(^.className := s"${Styles.notice} mt-2", Link(nav, AdminPage.Threads(props.bbs), Styles.link, "スレ一覧"), s" › ${props.bbs}/${props.key}"),
        loaded.state match {
          case LoadState.Loading => <.p(^.className := Styles.notice, "読み込み中…")
          case LoadState.Failed(error) => <.div(<.h1(^.className := Styles.h1, s"${props.bbs}/${props.key}"), <.p(^.className := Styles.error, error.message))
          case LoadState.Ready(detail) => <.div(
            <.h1(^.className := Styles.h1, title(detail), " ", <.small(^.className := Styles.small, s"(${detail.res.size})"), " ",
              <.span(^.className := stateClass(detail), state(detail))),
            StatusBanner(status.value),
            <.div(^.className := "mb-2.5 flex flex-wrap items-center gap-2",
              if (detail.stopped) <.button(^.`type` := "button", ^.className := Styles.button, ^.disabled := busy, ^.onClick --> run(ThreadAction.Resume), "書き込みの停止を解除")
              else <.button(^.`type` := "button", ^.className := Styles.button, ^.disabled := busy, ^.onClick --> run(ThreadAction.Stop), "書き込みを停止"),
              if (detail.hidden) EmptyVdom else <.button(^.`type` := "button", ^.className := Styles.danger, ^.disabled := busy,
                ^.onClick --> run(ThreadAction.Delete, Some("このスレを公開一覧とdatから削除しますか？本文と投稿者ログは保持します。")), "スレを削除")),
            <.details(^.className := Styles.panel,
              <.summary(^.className := "cursor-pointer font-bold", "■ 別の板・keyへ移動"),
              <.form(^.className := "mt-2 flex flex-wrap items-end gap-2",
                ^.onSubmit ==> (e => e.preventDefaultCB >> run(ThreadAction.Move(target.value._1.trim, target.value._2.trim),
                  Some(s"${target.value._1.trim}/${target.value._2.trim} へ移動しますか？"))),
                <.div(<.label(^.htmlFor := "move-board", ^.className := "block font-bold", "移動先の板"),
                  <.input(^.id := "move-board", ^.`type` := "text", ^.className := s"${Styles.input} w-40", ^.value := target.value._1,
                    ^.onChange ==> ((e: ReactEventFromInput) => target.modState(_.copy(_1 = e.target.value)))), fieldError("targetBoard")),
                <.div(<.label(^.htmlFor := "move-key", ^.className := "block font-bold", "移動先のkey"),
                  <.input(^.id := "move-key", ^.`type` := "text", ^.inputMode := "numeric", ^.className := s"${Styles.input} w-40", ^.value := target.value._2,
                    ^.onChange ==> ((e: ReactEventFromInput) => target.modState(_.copy(_2 = e.target.value)))), fieldError("targetKey")),
                <.button(^.`type` := "submit", ^.className := Styles.button, ^.disabled := busy, "移動する")),
              <.p(^.className := s"${Styles.notice} mt-1.5",
                "本文・投稿者ログ・スレ設定・停止状態を引き継ぎます。使用済みのkeyには移動できません。途中で失敗したときは、同じ移動先で移動し直してください。")),
            <.div(^.className := Styles.panel,
              <.h2(^.className := Styles.h2, "■ レス"),
              detail.res.toVdomArray(res => resRow(detail, res))),
            <.details(^.className := Styles.panel,
              <.summary(^.className := "cursor-pointer font-bold", "■ 投稿者ログ ", <.small(^.className := Styles.small, "削除後も保持")),
              <.pre(^.className := s"${Styles.pre} mt-2", detail.log)),
            <.details(^.className := Styles.panel,
              <.summary(^.className := "cursor-pointer font-bold", "■ dat"),
              <.pre(^.className := s"${Styles.pre} mt-2", detail.dat)),
            detail.legacyLog.filter(_.nonEmpty).fold(EmptyVdom)(log => <.details(^.className := Styles.panel,
              <.summary(^.className := "cursor-pointer font-bold", "■ 旧形式ログ"),
              <.pre(^.className := s"${Styles.pre} mt-2", log))))
        })
    }
  }
}
