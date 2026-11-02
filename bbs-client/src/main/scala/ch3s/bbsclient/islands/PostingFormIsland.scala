package ch3s.bbsclient.islands

import ch3s.common.domain.ThreadCommand
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.Try

/**
 * 書き込みフォーム
 * （板topの新規スレッド作成とスレッドの書き込み欄で共通）
 */
object PostingFormIsland {

  /** `threadKey` は React が `key` を props として渡さないための名前。 */
  final case class Props(bbs: String, time: String, threadKey: Option[String], imgurClientId: String, commands: List[ThreadCommand]) {
    def draftKey: String = s"$bbs/${threadKey.getOrElse("new")}"
  }

  object Props {
    def fromJs(raw: js.Object): Props = {
      val o = raw.asInstanceOf[js.Dynamic]
      def text(name: String): String = o.selectDynamic(name).asInstanceOf[js.UndefOr[String]].getOrElse("")
      val commands = o.selectDynamic("commands").asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].toList.flatMap(_.toList).map { c =>
        def field(name: String): String = c.selectDynamic(name).asInstanceOf[js.UndefOr[String]].getOrElse("")
        ThreadCommand(field("text"), field("label"), field("field"), field("description"))
      }
      Props(text("bbs"), text("time"), o.selectDynamic("threadKey").asInstanceOf[js.UndefOr[String]].toOption, text("imgurClientId"), commands)
    }
  }

  /** `fileKey` を進めてファイル入力を作り直し、選択を空に戻す。`uploading` は実行中の画像アップロード数。 */
  final case class State(from: String, mail: String, subject: String, message: String, detailsOpen: Boolean, status: String, fileKey: Int,
    uploading: Int)

  private final case class Draft(subject: String, message: String, sent: Option[Double])

  private val NameKey = "3ch:postName"
  private val MailKey = "3ch:postMail"
  private val DraftPrefix = "3ch:draft:"
  private val ImgurEndpoint = "https://api.imgur.com/3/image"
  private val controlClass = "max-w-full border border-[#d6dce3] bg-white px-2.5 py-1 hover:border-[#b9c4d0]"

  private def getField(state: State, name: String): Option[String] = name match {
    case "FROM" => Option(state.from)
    case "mail" => Option(state.mail)
    case "subject" => Option(state.subject)
    case "MESSAGE" => Option(state.message)
    case _ => None
  }

  private def withField(state: State, name: String, value: String): State = name match {
    case "FROM" => state.copy(from = value)
    case "mail" => state.copy(mail = value)
    case "subject" => state.copy(subject = value)
    case "MESSAGE" => state.copy(message = value)
    case _ => state
  }

  /** JS の `String.prototype.replace(文字列, "")` と同じく最初の一致だけを除く。 */
  private def removeFirst(value: String, target: String): String = {
    val index = value.indexOf(target)
    if index < 0 then value else s"${value.substring(0, index)}${value.substring(index + target.length)}"
  }

  private def toggledValue(command: ThreadCommand, current: String): String = {
    val text = command.text
    if command.field == "MESSAGE" then {
      if current.contains(text) then removeFirst(removeFirst(current, s"""$text
"""), text) else s"$text\n$current"
    } else if current.contains(text) then {
      removeFirst(current, text).replaceAll("\\s+", " ").trim
    } else if current == "" || current.last.isWhitespace then {
      s"$current$text"
    } else {
      s"$current $text"
    }
  }

  private def parseDraft(raw: String): Option[Draft] = {
    Try(js.JSON.parse(raw)).toOption.filter(v => v != null && js.typeOf(v) == "object").map { value =>
      val record = value

      def str(name: String): String = {
        val v = record.selectDynamic(name)
        if js.typeOf(v) == "string" then v.asInstanceOf[String] else ""
      }
      val sent = record.selectDynamic("sent")
      Draft(str("subject"), str("MESSAGE"), if js.typeOf(sent) == "number" then Option(sent.asInstanceOf[Double]) else None)
    }
  }

  private def navigationType: Option[String] = {
    Try {
      val entries = dom.window.performance.asInstanceOf[js.Dynamic].getEntriesByType("navigation").asInstanceOf[js.Array[js.Dynamic]]
      entries.headOption.flatMap(entry => entry.selectDynamic("type").asInstanceOf[js.UndefOr[String]].toOption)
    }.toOption.flatten
  }

  /** 両方空なら下書きを消し、そうでなければ保存する。`sent` は送信直後の印。 */
  private def saveDraft(props: Props, state: State, sent: Option[Double] = None): Unit = {
    val key = s"$DraftPrefix${props.draftKey}"
    if state.subject == "" && state.message == "" then BrowserStorage.remove(key)
    else {
      val draft = js.Dynamic.literal(subject = state.subject, MESSAGE = state.message)
      sent.foreach(time => draft.updateDynamic("sent")(time))
      BrowserStorage.write(key, js.JSON.stringify(draft))
    }
  }

  /** 保存済みの名前・メール（空でないものだけ）を、空欄の欄へ反映する。保存値の読み込みは呼び出し側で行う。 */
  private def withIdentity(state: State, name: Option[String], mail: Option[String]): State = {
    val restoredName = name.filter(_ => state.from.isEmpty)
    val restoredMail = mail.filter(_ => state.mail.isEmpty)
    state.copy(from = restoredName.getOrElse(state.from), mail = restoredMail.getOrElse(state.mail),
      detailsOpen = state.detailsOpen || restoredName.isDefined || restoredMail.isDefined)
  }

  /** 下書きを空欄へ反映する。返信フォーム（`threadKey` あり）はタイトルを復元しない。 */
  private def withDraft(props: Props, state: State, draft: Draft): State = {
    state.copy(
      subject = if props.threadKey.isEmpty && state.subject.isEmpty then draft.subject else state.subject,
      message = if state.message.isEmpty then draft.message else state.message)
  }

  /** 送信済みの下書きは、履歴移動（back_forward）以外の読み込みでは破棄する。 */
  private def discardsDraft(draft: Draft, backForward: Boolean): Boolean = draft.sent.isDefined && !backForward

  final class Backend($: BackendScope[Props, State]) {
    private var mounted = false
    private val uploads = mutable.Set.empty[dom.AbortController]
    private val onPageShow: js.Function1[dom.Event, Unit] = event => {
      if event.asInstanceOf[js.Dynamic].selectDynamic("persisted").asInstanceOf[js.UndefOr[Boolean]].contains(true) then {
        $.props.flatMap(p => $.state.map(s => if s.message != "" || s.subject != "" then saveDraft(p, s))).runNow()
      }
    }

    private def setStatus(text: String): Callback = $.modState(_.copy(status = text))

    /** 状態の反映後に、変更した欄に応じて名前・メール記憶か下書きを保存する。 */
    private def persist(field: String): Callback = $.props.flatMap { p =>
      $.state.map { s =>
        field match {
          case "FROM" => BrowserStorage.write(NameKey, s.from)
          case "mail" => BrowserStorage.write(MailKey, s.mail)
          case "subject" | "MESSAGE" => saveDraft(p, s)
          case _ => ()
        }
      }
    }

    private def setField(field: String, value: String): Callback = $.modState(withField(_, field, value), persist(field))

    /** 保存値の読み込みと復元の判断は更新関数の外で行い、保存・削除は反映後に行う。 */
    def mount: Callback = $.props.flatMap { p =>
      Callback {
        mounted = true
        dom.window.addEventListener("pageshow", onPageShow)
      } >> CallbackTo {
        val name = BrowserStorage.read(NameKey).filter(_.nonEmpty)
        val mail = BrowserStorage.read(MailKey).filter(_.nonEmpty)
        val draft = BrowserStorage.read(s"$DraftPrefix${p.draftKey}").flatMap(parseDraft)
        (name, mail, draft, navigationType.contains("back_forward"))
      }.flatMap { case (name, mail, draft, backForward) =>
        val key = s"$DraftPrefix${p.draftKey}"
        draft match {
          case Some(d) if discardsDraft(d, backForward) =>
            $.modState(withIdentity(_, name, mail), Callback(BrowserStorage.remove(key)))
          case Some(d) =>
            val resave = if d.sent.isDefined then $.state.map(s => saveDraft(p, s)) else Callback.empty
            $.modState(s => withDraft(p, withIdentity(s, name, mail), d), resave)
          case None => $.modState(withIdentity(_, name, mail))
        }
      }
    }

    def unmount: Callback = Callback {
      mounted = false
      dom.window.removeEventListener("pageshow", onPageShow)
      uploads.foreach(_.abort())
      uploads.clear()
    }

    private def submit(e: ReactEventFromHtml): Callback = {
      $.state.flatMap { s =>
        if s.uploading > 0 then e.preventDefaultCB >> setStatus("アップロード中です。完了してから送信してください")
        else $.props.map(p => saveDraft(p, s, Option(js.Date.now())))
      }
    }

    /** 完了を反映する。アップロード数を減らし、ファイル入力を作り直す。 */
    private def finishUpload(result: Try[(Boolean, js.Dynamic)]): Callback = {
      val link = result.toOption.collect { case (true, json) => Try(json.data.link).toOption.filter(l => js.typeOf(l) == "string") }.flatten
      def done(s: State): State = s.copy(uploading = math.max(0, s.uploading - 1), fileKey = s.fileKey + 1)
      link match {
        case Some(url) =>
          $.modState(s => {
            val separator = if s.message != "" && !s.message.endsWith("\n") then "\n" else ""
            done(s.copy(message = s"${s.message}$separator${url.asInstanceOf[String]}", status = "画像をアップロードしました"))
          }, persist("MESSAGE"))
        case None => $.modState(s => done(s.copy(status = "アップロードできません")))
      }
    }

    private def startUpload(props: Props, file: dom.File): Callback = {
      $.modState(s => s.copy(uploading = s.uploading + 1, status = "アップロード中…")) >> Callback {
        val controller = new dom.AbortController()
        uploads += controller
        val body = new dom.FormData()
        body.append("image", file)
        val init = new dom.RequestInit {}
        init.method = dom.HttpMethod.POST
        init.headers = js.Dictionary("Authorization" -> s"Client-ID ${props.imgurClientId}")
        init.body = body
        init.signal = controller.signal
        Future.delegate(dom.fetch(ImgurEndpoint, init).toFuture)
          .flatMap(r => r.json().toFuture.map(json => (r.ok, json.asInstanceOf[js.Dynamic])))
          .onComplete { result =>
            uploads -= controller
            if mounted && !controller.signal.aborted then finishUpload(result).runNow()
          }
      }
    }

    private def chooseFile(e: ReactEventFromInput): Callback = $.props.flatMap { p =>
      val files = e.target.files
      if files == null || files.length == 0 then Callback.empty
      else {
        val file = files(0)
        if !file.`type`.startsWith("image/") then setStatus("画像ファイルを選んでください") >> $.modState(s => s.copy(fileKey = s.fileKey + 1))
        else startUpload(p, file)
      }
    }

    private def toggleCommand(command: ThreadCommand): Callback = $.state.flatMap { s =>
      getField(s, command.field) match {
        case Some(current) if command.text != "" => setField(command.field, toggledValue(command, current))
        case _ => Callback.empty
      }
    }

    private def textInput(name: String, value: String, size: Int, extra: TagMod = TagMod.empty) = {
      <.input(^.className := controlClass, ^.`type` := "text", ^.name := name, ^.size := size, ^.value := value,
        ^.onChange ==> ((e: ReactEventFromInput) => setField(name, e.target.value)), extra)
    }

    private def commandHelper(commands: List[ThreadCommand], state: State) = {
      <.div(^.className := "my-1 flex flex-wrap items-center gap-1 text-xs", VdomAttr("data-command-helper") := "",
        <.span("コマンド"),
        commands.toVdomArray { command =>
          val pressed = getField(state, command.field).exists(_.contains(command.text))
          <.button(^.key := command.text,
            ^.className := "cursor-pointer border border-[#d6dce3] bg-white px-2 py-0.5 aria-pressed:border-[#f66] aria-pressed:font-bold",
            ^.`type` := "button", ^.title := command.description, VdomAttr("aria-pressed") := pressed.toString,
            VdomAttr("data-command") := command.text, VdomAttr("data-command-field") := command.field,
            ^.onClick --> toggleCommand(command), command.label)
        })
    }

    private def imgurUpload(state: State) = {
      <.div(^.className := "my-1 text-xs",
        <.label("画像：", <.input(^.key := state.fileKey, ^.`type` := "file", ^.accept := "image/*", VdomAttr("data-imgur-upload") := "",
          ^.onChange ==> chooseFile)),
        <.span(^.className := "ml-2", VdomAttr("data-imgur-status") := "", VdomAttr("aria-live") := "polite", state.status))
    }

    def render(props: Props, state: State): VdomNode = {
      val imgurAttr = if props.imgurClientId.nonEmpty then VdomAttr("data-imgur-client-id") := props.imgurClientId else TagMod.empty
      val showCommands = props.threadKey.isEmpty && props.commands.nonEmpty

      <.form(^.className := "posting-form mt-2 mb-3 ml-[2em] text-base [&_label]:inline-block [&_label]:max-w-full max-[660px]:ml-3",
        ^.method := "post", ^.action := "/test/bbs.cgi", ^.acceptCharset := "UTF-8", ^.onSubmit ==> submit,
        VdomAttr("data-draft-key") := props.draftKey, imgurAttr,
        <.fieldset(^.className := "m-0 min-w-0 border-0 p-0",
          <.details(^.open := state.detailsOpen, <.summary("名前・E-mail"),
            <.label("名前：", textInput("FROM", state.from, 24)),
            <.label(" E-mail：", textInput("mail", state.mail, 24))),
          props.threadKey.fold[VdomNode](
            <.label("タイトル：", textInput("subject", state.subject, 80, ^.required := true)))(_ => EmptyVdom),
          <.button(^.className := s"$controlClass cursor-pointer", ^.`type` := "submit", ^.name := "submit", ^.value := "書き込む",
            if props.threadKey.isEmpty then "新規スレッド作成" else "書き込む"),
          if showCommands then commandHelper(props.commands, state) else EmptyVdom,
          <.label(^.className := "message-label block!", "本文", <.textarea(^.className := s"$controlClass block w-full resize-y",
            ^.name := "MESSAGE", ^.rows := 8, ^.cols := 80, ^.required := true, ^.value := state.message,
            ^.onChange ==> ((e: ReactEventFromTextArea) => setField("MESSAGE", e.target.value)))),
          if props.imgurClientId.nonEmpty then imgurUpload(state) else EmptyVdom,
          <.input(^.`type` := "hidden", ^.name := "bbs", ^.value := props.bbs),
          <.input(^.`type` := "hidden", ^.name := "time", ^.value := props.time),
          props.threadKey.toVdomArray(key => <.input(^.key := key, ^.`type` := "hidden", ^.name := "key", ^.value := key))
        )
      )
    }
  }

  final val Component = ScalaComponent
    .builder[Props]("PostingForm")
    .initialState(State(from = "", mail = "", subject = "", message = "", detailsOpen = false, status = "", fileKey = 0, uploading = 0))
    .backend(new Backend(_))
    .renderPS((self, p, s) => self.backend.render(p, s))
    .componentDidMount(_.backend.mount)
    .componentWillUnmount(_.backend.unmount)
    .build
}
