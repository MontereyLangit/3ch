package io._3ch.bbs.handler.auth

import io._3ch.bbs.hash.{JwtRs256, RsaPublicKey}
import io._3ch.common.domain.account.{GoogleIdClaims, GoogleIdToken, GoogleIdentity}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.util.Try
import scala.util.control.NonFatal

@js.native
private trait GoogleResponse extends js.Object {
  val ok: Boolean = js.native
  val headers: GoogleHeaders = js.native
  def text(): js.Promise[String] = js.native
}
@js.native
private trait GoogleHeaders extends js.Object {
  def get(name: String): String = js.native
}
@js.native
@JSGlobal("fetch")
private object GoogleFetch extends js.Object {
  def apply(url: String, init: js.Object): js.Promise[GoogleResponse] = js.native
}

/** Google のトークンエンドポイントと JWKS を呼ぶ口。テストでは差し替える */
trait GoogleEndpoints {
  /** 認可コードを ID トークンに交換する。失敗は None */
  def exchange(code: String, verifier: String, redirectUri: String): Future[Option[String]]
  /** 署名鍵の一覧。refresh なら保存した鍵を使わず取り直す */
  def keys(refresh: Boolean): Future[Vector[RsaPublicKey]]
}

/** fetch で Google を呼ぶ。JWKS は Cache-Control の max-age の間、この isolate に保持する */
final class GoogleHttpEndpoints(settings: GoogleSettings) extends GoogleEndpoints {
  def exchange(code: String, verifier: String, redirectUri: String): Future[Option[String]] = {
    val body = Seq("code" -> code, "client_id" -> settings.clientId, "client_secret" -> settings.clientSecret, "redirect_uri" -> redirectUri,
      "grant_type" -> "authorization_code", "code_verifier" -> verifier).map { case (key, value) => key + "=" + js.URIUtils.encodeURIComponent(value) }.mkString("&")
    GoogleFetch(GoogleIdTokens.TokenUrl, js.Dynamic.literal(method = "POST",
      headers = js.Dictionary("Content-Type" -> "application/x-www-form-urlencoded"), body = body)).toFuture.flatMap { response =>
      if (!response.ok) Future.successful(None)
      else response.text().toFuture.map { text =>
        Try(js.JSON.parse(text).asInstanceOf[js.Dynamic].selectDynamic("id_token")).toOption
          .filter(value => js.typeOf(value) == "string").map(_.asInstanceOf[String])
      }
    }.recover { case NonFatal(_) => None }
  }

  def keys(refresh: Boolean): Future[Vector[RsaPublicKey]] = GoogleHttpEndpoints.cached.filter(_ => !refresh) match {
    case Some(keys) => Future.successful(keys)
    case None => GoogleFetch(GoogleIdTokens.CertsUrl, js.Dynamic.literal(method = "GET")).toFuture.flatMap { response =>
      if (!response.ok) Future.successful(Vector.empty)
      else response.text().toFuture.map { text =>
        val keys = GoogleIdTokens.parseKeys(text)
        val maxAge = Option(response.headers.get("Cache-Control")).flatMap(value => "max-age=([0-9]{1,7})".r.findFirstMatchIn(value)).map(_.group(1).toDouble)
        GoogleHttpEndpoints.store(keys, maxAge.getOrElse(0d))
        keys
      }
    }.recover { case NonFatal(_) => Vector.empty }
  }
}
object GoogleHttpEndpoints {
  private var keys: Vector[RsaPublicKey] = Vector.empty
  private var until: Double = 0
  private def cached: Option[Vector[RsaPublicKey]] = Option.when(keys.nonEmpty && js.Date.now() < until)(keys)
  private def store(value: Vector[RsaPublicKey], seconds: Double): Unit = {
    keys = value
    until = js.Date.now() + seconds * 1000
  }
}

/** Google の ID トークンを、署名・発行者・対象・期限・nonce の順に検証する */
object GoogleIdTokens {
  val AuthorizeUrl: String = "https://accounts.google.com/o/oauth2/v2/auth"
  val TokenUrl: String = "https://oauth2.googleapis.com/token"
  val CertsUrl: String = "https://www.googleapis.com/oauth2/v3/certs"

  private def text(value: js.Dynamic, name: String): Option[String] = {
    val v = value.selectDynamic(name)
    Option.when(js.typeOf(v) == "string")(v.asInstanceOf[String])
  }
  private def number(value: js.Dynamic, name: String): Option[Double] = {
    val v = value.selectDynamic(name)
    Option.when(js.typeOf(v) == "number" && v.asInstanceOf[Double].isFinite)(v.asInstanceOf[Double])
  }
  private def record(raw: String): Option[js.Dynamic] = Try(js.JSON.parse(raw)).toOption
    .filter(v => v != null && js.typeOf(v) == "object" && !js.Array.isArray(v)).map(_.asInstanceOf[js.Dynamic])

  /** JWKS の RS256 の鍵だけを読む */
  def parseKeys(raw: String): Vector[RsaPublicKey] = record(raw).flatMap { value =>
    val keys = value.selectDynamic("keys")
    Option.when(js.Array.isArray(keys))(keys.asInstanceOf[js.Array[js.Any]].toVector.flatMap { key =>
      Option(key).filter(k => js.typeOf(k) == "object").map(_.asInstanceOf[js.Dynamic]).flatMap { k =>
        for {
          kid <- text(k, "kid")
          kty <- text(k, "kty") if kty == "RSA"
          n <- text(k, "n")
          e <- text(k, "e")
          if text(k, "alg").forall(_ == "RS256") && text(k, "use").forall(_ == "sig")
        } yield RsaPublicKey(kid, n, e)
      }
    })
  }.getOrElse(Vector.empty)

  /** クレームを読む。aud は文字列だけを受け付ける */
  def claims(raw: String): Option[GoogleIdClaims] = record(raw).flatMap { v =>
    val verified = v.selectDynamic("email_verified")
    for {
      iss <- text(v, "iss")
      aud <- text(v, "aud")
      sub <- text(v, "sub")
      exp <- number(v, "exp")
      iat <- number(v, "iat")
      nonce <- text(v, "nonce")
    } yield GoogleIdClaims(iss, aud, sub, exp, iat, nonce, text(v, "email").filter(_.length <= 254),
      (js.typeOf(verified) == "boolean" && verified.asInstanceOf[Boolean]) || (js.typeOf(verified) == "string" && verified.asInstanceOf[String] == "true"))
  }

  /** 署名を検証し、鍵の ID が見つからなければ一度だけ鍵を取り直す */
  def verify(token: String, endpoints: GoogleEndpoints, now: Double, nonce: String, clientId: String): Future[Option[GoogleIdentity]] =
    JwtRs256.parse(token).flatMap(parts => record(parts.header).map(parts -> _)) match {
      case Some((parts, header)) if text(header, "alg").contains("RS256") => text(header, "kid") match {
        case None => Future.successful(None)
        case Some(kid) =>
          def find(refresh: Boolean): Future[Option[RsaPublicKey]] = endpoints.keys(refresh).map(_.find(_.kid == kid))
          find(false).flatMap(found => if (found.isDefined) Future.successful(found) else find(true)).flatMap {
            case None => Future.successful(None)
            case Some(key) => JwtRs256.verify(parts, key).map { valid =>
              if (!valid) None
              else claims(parts.payload).flatMap(c => GoogleIdToken.verifyClaims(c, now, nonce, clientId).toOption)
            }
          }
      }
      case _ => Future.successful(None)
    }
}
