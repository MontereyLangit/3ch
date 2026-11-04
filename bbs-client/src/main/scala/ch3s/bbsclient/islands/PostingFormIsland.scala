package ch3s.bbsclient.islands

import ch3s.common.domain.{BoardId, ThreadCommand, ThreadKey}
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

import scala.scalajs.js

/**
 * 書き込みフォーム
 * （板topの新規スレッド作成とスレッドの書き込み欄で共通）
 * 入力値・画像アップロード・送信はそれぞれPostingFields，ImgurUpload，PostingSubmitのHookが持つ
 */
object PostingFormIsland {

  final case class Props(bbs: BoardId, time: String, threadKey: Option[ThreadKey], imgurClientId: String, commands: List[ThreadCommand]) {
    def draftKey: String = s"${bbs.value}/${threadKey.fold("new")(_.value.toString)}"
  }

  object Props {
    /** 板名・スレキーが不正なら None */
    def fromJs(raw: js.Object): Option[Props] = {
      val o = raw.asInstanceOf[js.Dynamic]

      def text(name: String): String = o.selectDynamic(name).asInstanceOf[js.UndefOr[String]].getOrElse("")

      val commands = o.selectDynamic("commands").asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].toList.flatMap(_.toList).map { c =>
        def field(name: String): String = c.selectDynamic(name).asInstanceOf[js.UndefOr[String]].getOrElse("")

        ThreadCommand(field("text"), field("label"), field("field"), field("description"))
      }
      val threadKey = o.selectDynamic("threadKey").asInstanceOf[js.UndefOr[String]].toOption.map(ThreadKey.parse(_).toOption)
      for {
        bbs <- BoardId.create(text("bbs")).toOption
        key <- threadKey.fold[Option[Option[ThreadKey]]](Option(None))(_.map(Option(_)))
      } yield Props(bbs, text("time"), key, text("imgurClientId"), commands)
    }
  }

  private val controlClass = "max-w-full border border-[#d6dce3] bg-white px-2.5 py-1 hover:border-[#b9c4d0]"

  private def removeFirst(value: String, target: String): String = {
    val index = value.indexOf(target)
    if index < 0 then value else s"${value.substring(0, index)}${value.substring(index + target.length)}"
  }

  private def toggledValue(command: ThreadCommand, current: String): String = {
    val text = command.text
    if command.field == "MESSAGE" then {
      if current.contains(text) then removeFirst(removeFirst(current, s"$text\n"), text) else s"$text\n$current"
    } else if current.contains(text) then {
      removeFirst(current, text).replaceAll("\\s+", " ").trim
    } else if current == "" || current.last.isWhitespace then {
      s"$current$text"
    } else {
      s"$current $text"
    }
  }

  private def textInput(name: String, value: String, size: Int, setField: (String, String) => Callback, extra: TagMod = TagMod.empty) = {
    <.input(^.className := controlClass, ^.`type` := "text", ^.name := name, ^.size := size, ^.value := value,
      ^.onChange ==> ((e: ReactEventFromInput) => setField(name, e.target.value)), extra)
  }

  private def commandHelper(commands: List[ThreadCommand], fields: PostingFields, toggleCommand: ThreadCommand => Callback) = {
    <.div(^.className := "my-1 flex flex-wrap items-center gap-1 text-xs", VdomAttr("data-command-helper") := "",
      <.span("コマンド"),
      commands.toVdomArray { command =>
        val pressed = fields.get(command.field).exists(_.contains(command.text))
        <.button(^.key := command.text,
          ^.className := "cursor-pointer border border-[#d6dce3] bg-white px-2 py-0.5 aria-pressed:border-[#f66] aria-pressed:font-bold",
          ^.`type` := "button", ^.title := command.description, VdomAttr("aria-pressed") := pressed.toString,
          VdomAttr("data-command") := command.text, VdomAttr("data-command-field") := command.field,
          ^.onClick --> toggleCommand(command), command.label)
      })
  }

  private def imgurUpload(upload: ImgurUpload) = {
    <.div(^.className := "my-1 text-xs",
      <.label("画像：", <.input(^.key := upload.fileKey, ^.`type` := "file", ^.accept := "image/*", VdomAttr("data-imgur-upload") := "",
        ^.onChange ==> upload.chooseFile)),
      <.span(^.className := "ml-2", VdomAttr("data-imgur-status") := "", VdomAttr("aria-live") := "polite", upload.status))
  }

  private val buttonClass = s"$controlClass cursor-pointer disabled:cursor-wait"

  /**
   * 書き込む/新規スレッド作成submitボタン．
   * 送信中（useFormStatus）は押せなくする
   * form の子でないと効かないため独立させる */
  private final val SubmitButton = ScalaFnComponent[String] { label =>
    for {
      status <- useFormStatus
    } yield <.button(^.className := buttonClass, ^.`type` := "submit", ^.name := "submit", ^.value := "書き込む", ^.disabled := status.pending, label)
  }

  private final val ResendButton = ScalaFnComponent[Unit] { _ =>
    for {
      status <- useFormStatus
    } yield <.button(^.className := buttonClass, ^.`type` := "submit", ^.name := "submit", ^.value := "書き込む", ^.disabled := status.pending,
      VdomAttr("data-cookie-resend") := "", "上記を承諾して書き込む")
  }

  private def resultView(result: SubmitResult): VdomNode = result match {
    case SubmitResult.Idle => EmptyVdom
    case SubmitResult.Error(message) =>
      <.p(^.className := "my-1 whitespace-pre-wrap font-bold text-[#f00]", VdomAttr("data-post-result") := "error", ^.role := "alert", message)
    case SubmitResult.Unknown(message) =>
      <.p(^.className := "my-1 whitespace-pre-wrap font-bold text-[#f00]", VdomAttr("data-post-result") := "unknown", ^.role := "alert", message)
    case SubmitResult.Cookie(summary, terms) =>
      <.div(^.className := "my-1 text-xs", VdomAttr("data-post-result") := "cookie",
        <.p(^.className := "m-0 whitespace-pre-wrap", summary), <.p(^.className := "my-2 whitespace-pre-wrap", terms), ResendButton())
  }

  private def appendLink(message: String, url: String): String = {
    val separator = if message != "" && !message.endsWith("\n") then "\n" else ""
    s"$message$separator$url"
  }

  final val Component = ScalaFnComponent[Props] { props =>
    for {
      fields <- PostingFields.usePostingFields(props)
      upload <- ImgurUpload.useImgurUpload(props.imgurClientId, url => fields.modMessage(appendLink(_, url)))
      submit <- PostingSubmit.usePostingSubmit(props, fields, upload)
    } yield {
      def toggleCommand(command: ThreadCommand): Callback = {
        fields.get(command.field) match {
          case Some(current) if command.text != "" => fields.setField(command.field, toggledValue(command, current))
          case _ => Callback.empty
        }
      }

      val imgurAttr = if props.imgurClientId.nonEmpty then VdomAttr("data-imgur-client-id") := props.imgurClientId else TagMod.empty
      val showCommands = props.threadKey.isEmpty && props.commands.nonEmpty

      <.form(^.className := "posting-form mt-2 mb-3 ml-[2em] text-base [&_label]:inline-block [&_label]:max-w-full max-[660px]:ml-3",
        ^.acceptCharset := "UTF-8", submit.action,
        VdomAttr("data-draft-key") := props.draftKey, imgurAttr,
        resultView(submit.result),
        <.fieldset(^.className := "m-0 min-w-0 border-0 p-0",
          <.details(^.open := fields.detailsOpen, <.summary("名前・E-mail"),
            <.label("名前：", textInput("FROM", fields.from, 24, fields.setField)),
            <.label(" E-mail：", textInput("mail", fields.mail, 24, fields.setField))),
          props.threadKey.fold[VdomNode](
            <.label("タイトル：", textInput("subject", fields.subject, 80, fields.setField, ^.required := true)))(_ => EmptyVdom),
          SubmitButton(if props.threadKey.isEmpty then "新規スレッド作成" else "書き込む"),
          if showCommands then commandHelper(props.commands, fields, toggleCommand) else EmptyVdom,
          <.label(^.className := "message-label block!", "本文", <.textarea(^.className := s"$controlClass block w-full resize-y",
            ^.name := "MESSAGE", ^.rows := 8, ^.cols := 80, ^.required := true, ^.value := fields.message,
            ^.onChange ==> ((e: ReactEventFromTextArea) => fields.setField("MESSAGE", e.target.value)))),
          if props.imgurClientId.nonEmpty then imgurUpload(upload) else EmptyVdom,
          <.input(^.`type` := "hidden", ^.name := "bbs", ^.value := props.bbs.value),
          <.input(^.`type` := "hidden", ^.name := "time", ^.value := props.time),
          props.threadKey.map(_.value.toString).toVdomArray(key => <.input(^.key := key, ^.`type` := "hidden", ^.name := "key", ^.value := key))
        )
      )
    }
  }

  /**
   * jsから渡されるpropsを [[Props]] へ変換して描画
   * 不正なpropsのときは何も描画しない
   */
  final val JsComponent = ScalaFnComponent[js.Object] { raw =>
    Props.fromJs(raw).fold[VdomNode](EmptyVdom)(Component(_))
  }
}
