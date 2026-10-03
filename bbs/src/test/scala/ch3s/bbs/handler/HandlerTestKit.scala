package ch3s.bbs.handler

import ch3s.bbs.external.web.{Request, TextEncoder}
import ch3s.bbs.util.SjisEncoding
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

/** ハンドラ層のテストが共有する、Request/Response・グローバル（Date / Math.random / caches）のテストダブル。 */
object HandlerTestKit {
  val global: js.Dynamic = js.Dynamic.global.globalThis

  def fut[A](p: js.Any): Future[A] = p.asInstanceOf[js.Promise[A]].toFuture

  /** 実 `Request`（GET/POST）。`body` は文字列。 */
  def request(url: String, headers: Seq[(String, String)] = Nil, method: String = "GET", body: js.UndefOr[js.Any] = js.undefined): Request = {
    val init = js.Dynamic.literal(method = method, headers = js.Dictionary(headers*))
    if body.isDefined then init.updateDynamic("body")(body.get)
    js.Dynamic.newInstance(global.Request)(url, init).asInstanceOf[Request]
  }

  def text(res: js.Dynamic): Future[String] = fut[String](res.text())

  def bytes(res: js.Dynamic): Future[List[Int]] =
    fut[ArrayBuffer](res.arrayBuffer()).map(buffer => bytesOf(new Uint8Array(buffer)))

  /** Shift_JIS 本文のレスポンスを復号する。 */
  def sjisText(res: js.Dynamic): Future[String] =
    fut[ArrayBuffer](res.arrayBuffer()).map(buffer => SjisEncoding.decode(new Uint8Array(buffer)))

  def sjisBytes(text: String): List[Int] = bytesOf(SjisEncoding.encode(text))

  def utf8Bytes(text: String): List[Int] = bytesOf(new TextEncoder().encode(text))

  def bytesOf(array: Uint8Array): List[Int] = (0 until array.length).map(i => array(i).toInt).toList

  def header(res: js.Dynamic, name: String): Option[String] = {
    val value = res.headers.get(name)
    if value == null then None else Some(value.asInstanceOf[String])
  }

  def status(res: js.Dynamic): Int = res.status.asInstanceOf[Int]

  def ctx: js.Dynamic = js.Dynamic.literal(waitUntil = ((p: js.Any) => p): js.Function1[js.Any, js.Any])

  /** `body` の実行中だけグローバル `name` を `value` に差し替え、完了（成功・失敗とも）で元に戻す。 */
  private def withGlobal[A](name: String, value: js.Any)(body: => Future[A]): Future[A] = {
    val original = global.selectDynamic(name)
    global.updateDynamic(name)(value)
    val restore = () => global.updateDynamic(name)(original)
    val result = try body catch { case e: Throwable => restore(); throw e }
    result.transform { r => restore(); r }
  }

  /** 引数なしの `new Date()` と `Date.now()` だけを固定ミリ秒にする（明示引数付きの `new Date(x)` は素通し）。 */
  def withFixedTime[A](ms: Double)(body: => Future[A]): Future[A] = {
    val make = js.Dynamic.newInstance(js.Dynamic.global.Function)(
      "RealDate", "ms",
      "class FixedDate extends RealDate { constructor(...args) { if (args.length === 0) return new RealDate(ms); return new RealDate(...args); } static now() { return ms; } } return FixedDate;"
    )
    withGlobal("Date", make(global.Date, ms).asInstanceOf[js.Any])(body)
  }

  def withRandom[A](value: Double)(body: => Future[A]): Future[A] = {
    val math = global.Math
    val original = math.random
    math.random = ((() => value): js.Function0[Double])
    val restore = () => math.random = original
    val result = try body catch { case e: Throwable => restore(); throw e }
    result.transform { r => restore(); r }
  }

  /** `publicFetch` が直接参照する Workers の Cache API (`caches.default`) のインメモリ実装。 */
  private def makeCaches(): js.Dynamic = {
    val store = mutable.Map.empty[String, js.Any]
    def keyOf(req: js.Any): String = if js.typeOf(req) == "string" then req.asInstanceOf[String] else req.asInstanceOf[js.Dynamic].url.asInstanceOf[String]
    js.Dynamic.literal(
      default = js.Dynamic.literal(
        `match` = ((req: js.Any) => js.Promise.resolve[js.Any](store.getOrElse(keyOf(req), js.undefined.asInstanceOf[js.Any]))): js.Function1[js.Any, js.Promise[js.Any]],
        put = ((req: js.Any, res: js.Dynamic) => {
          store.update(keyOf(req), res.applyDynamic("clone")().asInstanceOf[js.Any])
          js.Promise.resolve[js.Any](js.undefined)
        }): js.Function2[js.Any, js.Dynamic, js.Promise[js.Any]]
      )
    )
  }

  private var savedCaches: Option[js.Any] = None

  /** `globalThis.caches` を新しいインメモリ実装へ差し替える。`restoreCaches()` で元に戻す。 */
  def installCaches(): Unit = {
    if savedCaches.isEmpty then savedCaches = Some(global.caches.asInstanceOf[js.Any])
    global.caches = makeCaches()
  }

  def restoreCaches(): Unit = {
    savedCaches.foreach(original => if js.isUndefined(original) then js.Dynamic.global.Reflect.deleteProperty(global, "caches") else global.caches = original)
    savedCaches = None
  }

  def sleep(ms: Int): Future[Unit] = {
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future
  }
}
