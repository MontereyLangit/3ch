package ch3s.bbs.external.web

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Fetch API `Response` / `ResponseInit` の最小手書き facade（docs/v6s実装メモ.md 2.5）。 */
trait ResponseInit extends js.Object {
  var status: js.UndefOr[Int] = js.undefined
  var headers: js.UndefOr[js.Object] = js.undefined
}

@js.native
@JSGlobal("Response")
class Response(body: js.UndefOr[js.Any] = js.native, init: js.UndefOr[ResponseInit] = js.native) extends js.Object {
  val headers: Headers = js.native
}

@js.native
trait Headers extends js.Object {
  def get(name: String): String = js.native
  def set(name: String, value: String): Unit = js.native
}

@js.native
trait Request extends js.Object {
  val method: String = js.native
  val url: String = js.native
  val headers: Headers = js.native
  val cf: js.UndefOr[js.Dynamic] = js.native
  def arrayBuffer(): js.Promise[js.typedarray.ArrayBuffer] = js.native
}
