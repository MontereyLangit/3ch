package io._3ch.bbsclient.islands

import io._3ch.common.domain.{PostingOutcome, PostingPage, PostingResponse}
import japgolly.scalajs.react.*
import japgolly.scalajs.react.callback.AsyncCallback
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 送信の結果表示．Idle は表示なし */
private[islands] sealed trait SubmitResult

private[islands] object SubmitResult {
  case object Idle extends SubmitResult
  final case class Error(message: String) extends SubmitResult
  final case class Cookie(summary: String, terms: String) extends SubmitResult
  final case class Unknown(message: String) extends SubmitResult
}

/** formに付けるaction・マウント前のmethodのTagModと直近の送信結果 */
private[islands] final case class PostingSubmit(action: TagMod, result: SubmitResult)

private[islands] object PostingSubmit {

  private val UnknownMessage = "書き込みに失敗しました"
  private val NoRedirectMessage = "書き込みは完了しましたが、移動先を取得できませんでした。スレ一覧を再読み込みしてください。"
  private val UploadingMessage = "アップロード中です。完了してから送信してください"

  /** `<br>` を改行として本文を取り出す */
  private def textWithBreaks(node: dom.Node): String = {
    if node.nodeType == dom.Node.TEXT_NODE then node.textContent
    else if node.nodeName.equalsIgnoreCase("br") then "\n"
    else (0 until node.childNodes.length).map(i => textWithBreaks(node.childNodes(i))).mkString
  }

  private def parsePage(html: String): PostingPage = {
    val doc = new dom.DOMParser().parseFromString(html, dom.MIMEType.`text/html`)
    def text(selector: String): Option[String] = Option(doc.querySelector(selector)).map(textWithBreaks)
    PostingPage(
      title = Option(doc.querySelector("title")).fold("")(_.textContent),
      errorText = text("font > b"),
      refreshContent = Option(doc.querySelector("meta[http-equiv=\"refresh\"]")).flatMap(m => Option(m.getAttribute("content"))),
      cookieSummary = text("ul").getOrElse(""),
      cookieTerms = text("body > b").getOrElse(""))
  }

  /**
   * UTF-8 application/x-www-form-urlencodedでbbs.cgiへPOSTし、応答の分類を返す
   * 失敗は Unrecognized
   */
  private def postForm(formData: dom.FormData): Future[PostingOutcome] = {
    Future.delegate {
      val params = js.Dynamic.newInstance(js.Dynamic.global.URLSearchParams)(formData)
      params.set("submit", "書き込む")
      val init = new dom.RequestInit {}
      init.method = dom.HttpMethod.POST
      init.credentials = dom.RequestCredentials.`same-origin`
      init.body = params.asInstanceOf[dom.URLSearchParams]
      dom.fetch("/test/bbs.cgi", init).toFuture.flatMap { r =>
        val charset = PostingResponse.charset(Option(r.headers.get("content-type")).getOrElse(""))
        r.arrayBuffer().toFuture.map { buffer =>
          val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)(charset)
          PostingResponse.classify(parsePage(decoder.decode(buffer).asInstanceOf[String]))
        }
      }
    }.recover { case _ => PostingOutcome.Unrecognized }
  }

  /** 
   * form action
   * 成功時は下書きを消して遷移（TODO: 返信は暫定でリロード）
   * 失敗時は入力を残して結果を返す
   */
  private def submitAction(props: PostingFormIsland.Props, fields: PostingFields, upload: ImgurUpload)(
    previous: SubmitResult, formData: dom.FormData): AsyncCallback[SubmitResult] = {
    if upload.isUploading() then {
      AsyncCallback.delay {
        upload.setStatus(UploadingMessage).runNow()
        previous
      }
    } else {
      AsyncCallback.fromFuture(postForm(formData)).map {
        case PostingOutcome.Posted(redirect) =>
          fields.clearPosted.runNow()
          if props.threadKey.isDefined then {
            dom.window.location.reload()
            SubmitResult.Idle
          } else {
            redirect match {
              case Some(url) =>
                dom.window.location.assign(url)
                SubmitResult.Idle
              case None => SubmitResult.Unknown(NoRedirectMessage)
            }
          }
        case PostingOutcome.Rejected(message) => SubmitResult.Error(message)
        case PostingOutcome.NeedsCookie(summary, terms) => SubmitResult.Cookie(summary, terms)
        case PostingOutcome.Unrecognized => SubmitResult.Unknown(UnknownMessage)
      }
    }
  }

  def usePostingSubmit(props: PostingFormIsland.Props, fields: PostingFields, upload: ImgurUpload): HookResult[PostingSubmit] = {
    for {
      action <- useActionStateAsync[AsyncCallback, SubmitResult, dom.FormData](submitAction(props, fields, upload)(_, _), SubmitResult.Idle)
      ready <- useState(false)
      _ <- useEffectOnMount(ready.setState(true))
    } yield {
      // SSR・hydration では通常の POST 先を出し、マウント後に関数 action へ切り替える（JS 無しでも投稿できる）。
      // 関数 action では method を指定できない（React が補う）ため、切り替え時に外す。
      val tag: TagMod =
        if ready.value then ^.action ==> ((fd: dom.FormData) => action.dispatch(fd)) else TagMod(^.action := "/test/bbs.cgi", ^.method := "post")
      PostingSubmit(tag, action.state)
    }
  }
}
