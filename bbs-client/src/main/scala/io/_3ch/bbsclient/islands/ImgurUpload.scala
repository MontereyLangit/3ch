package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.Try

/**
 * Imgurアップロード処理の状態
 * fileKey -> file入力のキー．変えると選択が元に戻る
 * isUploading -> 非同期のactionから読む時に使う最新値
 * setStatus -> 表示用の文言
 */
final case class ImgurUpload(uploading: Int, fileKey: Int, status: String, isUploading: () => Boolean, chooseFile: ReactEventFromInput => Callback,
  setStatus: String => Callback)

object ImgurUpload {

  private final case class State(uploading: Int, fileKey: Int, status: String)

  private val Endpoint = "https://api.imgur.com/3/image"

  /**
   * Imgurアップロード処理のHooks
   * onLinkはURL発行成功時に非同期で呼ばれる
   */
  def useImgurUpload(clientId: String, onLink: String => Callback): HookResult[ImgurUpload] = {
    for {
      state <- useState(State(uploading = 0, fileKey = 0, status = ""))
      mounted <- useRef(false)
      uploads <- useRef(mutable.Set.empty[dom.AbortController])
      uploadingNow <- useRef(0)
      onLinkNow <- useRef(onLink)
      _ <- useLayoutEffect(Callback {
        uploadingNow.value = state.value.uploading
        onLinkNow.value = onLink
      })
      _ <- useEffectOnMount(CallbackTo {
        mounted.value = true
        Callback {
          mounted.value = false
          uploads.value.foreach(_.abort())
          uploads.value.clear()
        }
      })
    } yield {
      def done(s: State): State = s.copy(uploading = math.max(0, s.uploading - 1), fileKey = s.fileKey + 1)

      def finish(result: Try[(Boolean, js.Dynamic)]): Callback = {
        val link = result.toOption.collect { case (true, json) => Try(json.data.link).toOption.filter(l => js.typeOf(l) == "string") }.flatten
        link match {
          case Some(url) =>
            state.modState(s => done(s.copy(status = "画像をアップロードしました"))) >> Callback.suspend(onLinkNow.value(url.asInstanceOf[String]))
          case None => state.modState(s => done(s.copy(status = "アップロードできません")))
        }
      }

      def start(file: dom.File): Callback = {
        state.modState(s => s.copy(uploading = s.uploading + 1, status = "アップロード中…")) >> Callback {
          val controller = new dom.AbortController()
          uploads.value += controller
          val body = new dom.FormData()
          body.append("image", file)
          val init = new dom.RequestInit {}
          init.method = dom.HttpMethod.POST
          init.headers = js.Dictionary("Authorization" -> s"Client-ID $clientId")
          init.body = body
          init.signal = controller.signal
          Future.delegate(dom.fetch(Endpoint, init).toFuture)
            .flatMap(r => r.json().toFuture.map(json => (r.ok, json.asInstanceOf[js.Dynamic])))
            .onComplete { result =>
              uploads.value -= controller
              if mounted.value && !controller.signal.aborted then finish(result).runNow()
            }
        }
      }

      def setStatus(text: String): Callback = state.modState(_.copy(status = text))

      def chooseFile(e: ReactEventFromInput): Callback = {
        val files = e.target.files
        if files == null || files.length == 0 then Callback.empty
        else {
          val file = files(0)
          if !file.`type`.startsWith("image/") then setStatus("画像ファイルを選んでください") >> state.modState(s => s.copy(fileKey = s.fileKey + 1))
          else start(file)
        }
      }

      ImgurUpload(state.value.uploading, state.value.fileKey, state.value.status, () => uploadingNow.value > 0, chooseFile, setStatus)
    }
  }
}
