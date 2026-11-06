package io._3ch.bbs.external.web

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Fetch API `Response` / `ResponseInit` の手書きfacade */
trait ResponseInit extends js.Object {
  var status: js.UndefOr[Int] = js.undefined
  var statusText: js.UndefOr[String] = js.undefined
  var headers: js.UndefOr[js.Object] = js.undefined
}

@js.native
@JSGlobal("Response")
class Response(responseBody: js.UndefOr[js.Any] = js.native, init: js.UndefOr[ResponseInit] = js.native) extends js.Object {
  val status: Int = js.native
  val statusText: String = js.native
  val body: js.Any = js.native
  val headers: Headers = js.native
  def text(): js.Promise[String] = js.native
  def json(): js.Promise[js.Any] = js.native
}

@js.native
trait Headers extends js.Object {
  def get(name: String): String = js.native
  def set(name: String, value: String): Unit = js.native
  def append(name: String, value: String): Unit = js.native
  def delete(name: String): Unit = js.native
}

@js.native
trait Request extends js.Object {
  val method: String = js.native
  val url: String = js.native
  val headers: Headers = js.native
  val cf: js.UndefOr[js.Dynamic] = js.native
  def arrayBuffer(): js.Promise[js.typedarray.ArrayBuffer] = js.native
  def text(): js.Promise[String] = js.native
  def json(): js.Promise[js.Any] = js.native
}

@js.native
@JSGlobal("URL")
class URL(url: String, base: js.UndefOr[String] = js.native) extends js.Object {
  val protocol: String = js.native
  val hostname: String = js.native
  val origin: String = js.native
  val pathname: String = js.native
  val searchParams: URLSearchParams = js.native
}

@js.native
trait URLSearchParams extends js.Object {
  def get(name: String): String = js.native
}
