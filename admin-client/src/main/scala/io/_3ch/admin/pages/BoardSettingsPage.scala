package io._3ch.admin.pages

import io._3ch.admin.api.AdminApiClient
import io._3ch.admin.components.{Confirm, Link, StatusBanner, Styles}
import io._3ch.admin.model.{BoardSettingsDraft, BoardSettingsLayout, SettingControl, SettingGroup, SettingRow}
import io._3ch.admin.{AdminPage, LoadState, Loaded, Nav, SaveState}
import io._3ch.common.admin.{AdminCodec, BoardSettingsView, RuleName}
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 板設定。SETTING.TXT の項目・その他の設定と head.txt を読込時の ETag で保存する */
object BoardSettingsPage {
  final case class Props(nav: Nav, bbs: String)

  val PartialMessage = "SETTING.TXTは保存しましたが、head.txtは他の操作で更新されていたため保存していません。画面を開き直してください。"
  private val group = "mb-3 border border-l-4 border-[#dce1e6] bg-white px-3.5 py-2.5"
  private val rowClass = "grid grid-cols-[minmax(0,1fr)_190px] gap-x-3 gap-y-1 border-t border-[#eef1f4] py-[7px] first-of-type:border-t-0 max-[767px]:grid-cols-1"
  private val reset = "cursor-pointer self-start border-0 bg-transparent p-0 text-[11px] text-[#06f] hover:underline"

  private def placeholder(default: String, wide: Boolean): String =
    if (!wide) default else if (default.isEmpty) "未設定" else s"未設定（既定 $default）"

  private def control(draft: BoardSettingsDraft, row: SettingRow, edit: BoardSettingsDraft => Callback, bad: Boolean): VdomNode = {
    def radio(key: String, value: String, label: String, checked: Boolean, onChange: Callback): VdomElement =
      <.label(^.key := value, ^.className := "block text-[13px]",
        <.input(^.`type` := "radio", ^.name := s"setting-$key", ^.value := value, ^.checked := checked, ^.onChange --> onChange), " ", label)
    row.control match {
      case SettingControl.Inputs(units) =>
        val wide = row.keys.size == 1
        <.div(^.className := (if (wide) "flex items-center gap-1" else "flex flex-wrap items-center gap-1"),
          row.keys.zipWithIndex.toVdomArray { case (key, i) =>
            val value = draft.value(key)
            <.span(^.key := key, ^.className := "contents",
              <.input(^.`type` := "text", ^.className := s"${if (bad) Styles.inputBad else Styles.input} ${if (wide) "" else "!w-16"}",
                ^.aria.label := (if (wide) row.label else s"${row.label}（${i + 1}）"), ^.value := (if (value.explicit) value.value else ""),
                ^.placeholder := placeholder(BoardSettingsDraft.default(key), wide),
                ^.onChange ==> ((e: ReactEventFromInput) => edit(draft.set(key, e.target.value)))),
              units.lift(i).filter(_.nonEmpty).fold(EmptyVdom)(unit => <.span(^.className := "whitespace-nowrap", unit)))
          })
      case SettingControl.Radios(options) =>
        val key = row.keys.head
        val current = draft.value(key).value
        val all = if (options.exists(_._1 == current)) options else options :+ (current -> (if (current.isEmpty) "空" else current))
        <.div(all.toVdomArray { case (value, label) => radio(key, value, label, current == value, edit(draft.set(key, value))) })
      case SettingControl.Check(label) =>
        val key = row.keys.head
        <.label(^.className := "block text-[13px]",
          <.input(^.`type` := "checkbox", ^.checked := draft.value(key).value == "checked",
            ^.onChange ==> ((e: ReactEventFromInput) => edit(draft.set(key, if (e.target.checked) "checked" else "")))), " ", label)
      case SettingControl.Bypass =>
        val key = row.keys.head
        val current = draft.value(key).value
        val options = BoardSettingsLayout.bypassOptions
        val all = if (options.exists(_._1 == current)) options else options :+ (current -> (if (current.isEmpty) "空（権限者とキャップと同じ）" else current))
        <.select(^.className := s"${Styles.select} w-full", ^.aria.label := row.label, ^.value := current,
          ^.onChange ==> ((e: ReactEventFromInput) => edit(draft.set(key, e.target.value))),
          all.toVdomArray { case (value, label) => <.option(^.key := value, ^.value := value, label) })
      case SettingControl.FrLevel =>
        val key = row.keys.head
        val current = draft.value(key).value
        val level = current.matches("[0-9]+") && current.toLongOption.exists(_ >= 1)
        val zero = current == "" || current == "0"
        <.div(
          radio(key, "off", "無効（off）", current == "off", edit(draft.set(key, "off"))),
          radio(key, "log", "記録のみ（log）", current == "log", edit(draft.set(key, "log"))),
          radio(key, "0", "有効・広域連投を除く（0）", zero, edit(draft.set(key, "0"))),
          radio(key, "1", "有効・広域連投も規制（1以上）", level, Callback.when(!level)(edit(draft.set(key, "1")))),
          if (level) <.input(^.`type` := "text", ^.inputMode := "numeric", ^.className := s"${Styles.input} mt-1 !w-16", ^.aria.label := "広域連投の段階",
            ^.value := current, ^.onChange ==> ((e: ReactEventFromInput) => edit(draft.set(key, e.target.value)))) else EmptyVdom,
          if (!level && !zero && current != "off" && current != "log") <.p(^.className := Styles.error, s"現在の値: $current") else EmptyVdom)
      case SettingControl.RentouKeys =>
        val key = row.keys.head
        val selected = BoardSettingsLayout.selectedKeys(draft.value(key).value)
        <.div(BoardSettingsLayout.rentouKeys.toVdomArray { case (name, label) =>
          val on = selected.contains(name)
          <.label(^.key := name, ^.className := "block text-[13px]",
            <.input(^.`type` := "checkbox", ^.checked := on, ^.disabled := on && selected.size == 1,
              ^.onChange --> edit(draft.set(key, BoardSettingsLayout.keysValue(if (on) selected - name else selected + name)))), " ", label)
        })
    }
  }

  private def settingRow(draft: BoardSettingsDraft, status: SaveState, row: SettingRow, edit: BoardSettingsDraft => Callback): VdomElement = {
    val explicit = row.keys.exists(draft.value(_).explicit)
    val errors = row.keys.flatMap(key => status.field(s"fields.$key"))
    val inputs = row.control match {
      case SettingControl.Inputs(_) => true
      case _ => false
    }
    <.div(^.key := row.keys.mkString(","), ^.className := rowClass,
      <.div(<.b(^.className := "block text-[13px]", row.label), if (row.note.nonEmpty) <.small(^.className := Styles.notice, row.note) else EmptyVdom),
      <.div(^.className := "flex flex-col gap-[3px]",
        control(draft, row, edit, errors.nonEmpty),
        errors.toVdomArray(message => <.span(^.key := message, ^.className := Styles.error, message)),
        if (explicit) <.span(^.className := "flex flex-wrap items-center gap-2", <.span(^.className := Styles.tagExplicit, "設定済み"),
          <.button(^.`type` := "button", ^.className := reset, ^.onClick --> edit(row.keys.foldLeft(draft)(_.reset(_))), "既定に戻す"))
        else if (!inputs) <.span(^.className := Styles.tag, "未設定（既定）") else EmptyVdom))
  }

  private def settingGroup(draft: BoardSettingsDraft, status: SaveState, value: SettingGroup, edit: BoardSettingsDraft => Callback): VdomElement =
    <.section(^.key := value.title, ^.className := s"$group ${value.accent}",
      <.h2(^.className := "mb-2 text-[13.5px] font-bold", s"■ ${value.title}"),
      value.rows.toVdomArray(row => settingRow(draft, status, row, edit)))

  final val Component = ScalaFnComponent[Props] { props =>
    for {
      loaded <- Loaded.useLoad(props.bbs)(() => AdminApiClient.get(s"/boards/${props.bbs}/settings/")(AdminCodec.boardSettings))
      edited <- useState(Option.empty[BoardSettingsDraft])
      status <- useState[SaveState](SaveState.Idle)
      dirty = edited.value.exists(_.changes > 0)
      _ <- useEffectWithDeps(dirty)(props.nav.setDirty)
    } yield {
      val nav = props.nav
      val bbs = props.bbs
      val reopen = Confirm(dirty, Confirm.DiscardMessage)(edited.setState(None) >> status.setState(SaveState.Idle) >> loaded.reload)
      def body(view: BoardSettingsView): VdomElement = {
        val draft = edited.value.getOrElse(BoardSettingsDraft(view))
        val edit = (next: BoardSettingsDraft) => edited.setState(Some(next))
        val changes = draft.changes
        val busy = status.value.busy
        val save = status.setState(SaveState.Saving) >> Callback {
          AdminApiClient.put(s"/boards/$bbs/settings", AdminCodec.boardSettingsUpdate(draft.update))(AdminCodec.boardSettingsSaved).foreach {
            case Right(saved) if saved.partial =>
              (edited.setState(Some(draft.settingsSaved(saved.settingsEtag))) >> status.setState(SaveState.Conflict(PartialMessage))).runNow()
            case Right(_) => (edited.setState(None) >> status.setState(SaveState.Saved("保存しました。")) >> loaded.reload).runNow()
            case Left(error) => status.setState(SaveState.from(error)).runNow()
          }
        }
        val discard = edited.setState(None) >> status.setState(SaveState.Idle)
        def buttons: VdomNode = <.span(^.className := "flex gap-2",
          <.button(^.`type` := "button", ^.className := Styles.button, ^.disabled := changes == 0 || busy, ^.onClick --> discard, "元に戻す"),
          <.button(^.`type` := "submit", ^.className := Styles.primary, ^.disabled := changes == 0 || busy, "適用"))
        <.form(^.onSubmit ==> (e => e.preventDefaultCB >> save),
          if (changes > 0 || status.value.isInstanceOf[SaveState.Invalid])
            <.div(^.className := "sticky top-16 z-[5] mb-3 flex flex-wrap items-center gap-2.5 border border-[#f3c9a8] bg-[#fff8f1] px-3 py-2",
              <.span("未保存の変更が", <.b(s"${changes}件"), "あります。"),
              status.value match {
                case SaveState.Invalid(_, fields) => <.span(^.className := Styles.error, s"${fields.size}件の入力に誤りがあります。")
                case _ => EmptyVdom
              },
              <.span(^.className := "ml-auto"), buttons)
          else EmptyVdom,
          StatusBanner(status.value, Some(reopen)),
          <.div(^.className := "grid grid-cols-2 items-start gap-3 max-[959px]:block",
            <.div(BoardSettingsLayout.left.toVdomArray(g => settingGroup(draft, status.value, g, edit))),
            <.div(
              BoardSettingsLayout.right.toVdomArray(g => settingGroup(draft, status.value, g, edit)),
              <.section(^.className := s"$group border-l-[#f66]",
                <.h2(^.className := "mb-2 text-[13.5px] font-bold", <.label(^.htmlFor := "board-head", "■ ヘッダー（head.txt） "),
                  <.small(^.className := Styles.small, "板トップとサイドバーに表示")),
                <.textarea(^.id := "board-head", ^.className := Styles.textarea, ^.rows := 6, ^.value := draft.head,
                  ^.onChange ==> ((e: ReactEventFromTextArea) => edit(draft.setHead(e.target.value))))),
              <.section(^.className := s"$group border-l-[#b9c4d0]",
                <.h2(^.className := "mb-2 text-[13.5px] font-bold", "■ この板に適用される規制ルール ",
                  <.small(^.className := Styles.small, "全板共通のファイルのうち、この板に一致する行")),
                <.pre(^.className := Styles.pre, (view.rules.access ++ view.rules.ngwords) match {
                  case Nil => "（ありません）"
                  case lines => lines.mkString("\n")
                }),
                <.p(^.className := s"${Styles.notice} mt-1.5", Link(nav, AdminPage.Rules(RuleName.Access), Styles.link, "アクセス規制を編集"), "・",
                  Link(nav, AdminPage.Rules(RuleName.Ngwords), Styles.link, "NGワードを編集"))),
              <.section(^.className := s"$group border-l-[#b9c4d0]",
                <.h2(^.className := "mb-2 text-[13.5px] font-bold", <.label(^.htmlFor := "board-extra", "■ その他の設定（直接編集） "),
                  <.small(^.className := Styles.small, "上の項目に無いキー。そのまま保存されます")),
                <.textarea(^.id := "board-extra", ^.className := Styles.textarea, ^.rows := 5, ^.spellCheck := false, ^.value := draft.extra,
                  ^.onChange ==> ((e: ReactEventFromTextArea) => edit(draft.setExtra(e.target.value)))),
                status.value.field("extra").fold(EmptyVdom)(message => <.p(^.className := s"${Styles.error} mt-1", message)),
                <.div(^.className := Styles.help, <.b("書き方"), ": 1行に", <.code("キー=値"), "。上の項目と同じキーは書けません。", <.code("="), "の無い行はそのまま残ります。")))),
          <.div(^.className := "mt-1 mb-3 flex justify-end", buttons))
      }
      <.div(
        <.h1(^.className := Styles.h1, "板設定 ", <.small(^.className := Styles.small, bbs)),
        <.nav(^.className := "flex flex-wrap gap-[3px] pt-1 pb-3",
          <.span(^.className := "mr-2.5 border-b-2 border-[#f66] py-1 font-bold", "板設定"),
          Link(nav, AdminPage.Threads(bbs), "mr-2.5 border-b-2 border-transparent py-1 text-[#333] no-underline hover:border-[#f66]", "スレ一覧"),
          <.a(^.href := s"/$bbs/", ^.className := "mr-2.5 border-b-2 border-transparent py-1 text-[#333] no-underline hover:border-[#f66]", "板トップを開く")),
        loaded.state match {
          case LoadState.Loading => <.p(^.className := Styles.notice, "読み込み中…")
          case LoadState.Failed(error) => <.p(^.className := Styles.error, error.message)
          case LoadState.Ready(view) => body(view)
        })
    }
  }
}
