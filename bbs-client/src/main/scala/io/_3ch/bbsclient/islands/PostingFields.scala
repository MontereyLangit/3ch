package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import org.scalajs.dom

import scala.scalajs.js
import scala.util.Try

/**
 * 書き込みフォームの入力値およびlocalStorageへの保存・復元状態
 * setField -> 入力欄の onChange
 * modMessage -> 本文を関数で書き換える
 * clearPosted -> 書き込み成功時に下書きを消して題名・本文を空にする
 */
final case class PostingFields(from: String, mail: String, subject: String, message: String, detailsOpen: Boolean,
  setField: (String, String) => Callback, modMessage: (String => String) => Callback, clearPosted: Callback) {

  def get(name: String): Option[String] = name match {
    case "FROM" => Option(from)
    case "mail" => Option(mail)
    case "subject" => Option(subject)
    case "MESSAGE" => Option(message)
    case _ => None
  }
}

object PostingFields {

  private final case class Values(from: String, mail: String, subject: String, message: String, detailsOpen: Boolean)

  private final case class Draft(subject: String, message: String, sent: Option[Double])

  private val initialValues = Values(from = "", mail = "", subject = "", message = "", detailsOpen = false)

  private val NameKey = "3ch:postName"
  private val MailKey = "3ch:postMail"
  private val DraftPrefix = "3ch:draft:"

  private def withField(values: Values, name: String, value: String): Values = name match {
    case "FROM" => values.copy(from = value)
    case "mail" => values.copy(mail = value)
    case "subject" => values.copy(subject = value)
    case "MESSAGE" => values.copy(message = value)
    case _ => values
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

  private def draftStorageKey(props: PostingFormIsland.Props): String = s"$DraftPrefix${props.draftKey}"

  /** 両方空なら下書きを消し、そうでなければ保存 */
  private def saveDraft(props: PostingFormIsland.Props, values: Values): Unit = {
    val key = draftStorageKey(props)
    if values.subject == "" && values.message == "" then BrowserStorage.remove(key)
    else {
      val draft = js.Dynamic.literal(subject = values.subject, MESSAGE = values.message)
      BrowserStorage.write(key, js.JSON.stringify(draft))
    }
  }

  /** 空でない保存済みの名前・メールを空欄の欄へ反映する */
  private def withIdentity(values: Values, name: Option[String], mail: Option[String]): Values = {
    val restoredName = name.filter(_ => values.from.isEmpty)
    val restoredMail = mail.filter(_ => values.mail.isEmpty)
    values.copy(from = restoredName.getOrElse(values.from), mail = restoredMail.getOrElse(values.mail),
      detailsOpen = values.detailsOpen || restoredName.isDefined || restoredMail.isDefined)
  }

  /** 下書きの本文（スレ立ての場合はタイトルも）を空欄へ反映する */
  private def withDraft(props: PostingFormIsland.Props, values: Values, draft: Draft): Values = {
    values.copy(
      subject = if props.threadKey.isEmpty && values.subject.isEmpty then draft.subject else values.subject,
      message = if values.message.isEmpty then draft.message else values.message)
  }

  /** 送信済みの下書きは、履歴移動（back_forward）以外の読み込みでは破棄 */
  private def discardsDraft(draft: Draft, backForward: Boolean): Boolean = draft.sent.isDefined && !backForward

  /**
   * 変更欄に応じ名前・メール記憶または下書き保存する
   * （状態の反映後に呼ぶ）
   */
  private def persist(props: PostingFormIsland.Props, values: Values, field: String): Unit = {
    field match {
      case "FROM" => BrowserStorage.write(NameKey, values.from)
      case "mail" => BrowserStorage.write(MailKey, values.mail)
      case "subject" | "MESSAGE" => saveDraft(props, values)
      case _ => ()
    }
  }

  /**
   * 書き込みフォームの入力値操作・localStorage保存・復元処理のHooks
   * @param props 書き込みフォームコンポーネントのProps
   * @return HookResult[PostingFields]
   */
  def usePostingFields(props: PostingFormIsland.Props): HookResult[PostingFields] = {
    for {
      state <- useState(initialValues)
      pending <- useRef(Set.empty[String])
      latest <- useRef((props, initialValues))
      _ <- useLayoutEffect(Callback {
        latest.value = (props, state.value)
        pending.value.foreach(field => persist(props, state.value, field))
        pending.value = Set.empty
      })
      _ <- useEffectOnMount(CallbackTo {
        val onPageShow: js.Function1[dom.Event, Unit] = event => {
          if event.asInstanceOf[js.Dynamic].selectDynamic("persisted").asInstanceOf[js.UndefOr[Boolean]].contains(true) then {
            val (p, v) = latest.value
            if v.message != "" || v.subject != "" then saveDraft(p, v)
          }
        }
        dom.window.addEventListener("pageshow", onPageShow)
        val name = BrowserStorage.read(NameKey).filter(_.nonEmpty)
        val mail = BrowserStorage.read(MailKey).filter(_.nonEmpty)
        val draft = BrowserStorage.read(draftStorageKey(props)).flatMap(parseDraft)
        val backForward = navigationType.contains("back_forward")
        val restored = withIdentity(state.value, name, mail)
        draft match {
          case Some(d) if discardsDraft(d, backForward) =>
            state.setState(restored).runNow()
            BrowserStorage.remove(draftStorageKey(props))
          case Some(d) =>
            val next = withDraft(props, restored, d)
            state.setState(next).runNow()
            if d.sent.isDefined then saveDraft(props, next)
          case None => state.setState(restored).runNow()
        }
        Callback(dom.window.removeEventListener("pageshow", onPageShow))
      })
    } yield {
      /** 状態更新を依頼して反映時にフィールドを保存するよう記録 */
      def modAndPersist(field: String)(f: Values => Values): Callback = Callback { pending.value += field } >> state.modState(f)

      val values = state.value
      PostingFields(
        from = values.from,
        mail = values.mail,
        subject = values.subject,
        message = values.message,
        detailsOpen = values.detailsOpen,
        setField = (field, value) => modAndPersist(field)(withField(_, field, value)),
        modMessage = f => modAndPersist("MESSAGE")(v => v.copy(message = f(v.message))),
        clearPosted = Callback(BrowserStorage.remove(draftStorageKey(props))) >> state.modState(_.copy(subject = "", message = "")))
    }
  }
}
