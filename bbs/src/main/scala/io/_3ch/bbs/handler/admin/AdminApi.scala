package io._3ch.bbs.handler.admin

import io._3ch.bbs.application.admin.{AdminFailure, AdminService}
import io._3ch.bbs.domain.admin.SettingProblem
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.{Request, Response, TextEncoder}
import io._3ch.bbs.handler.{AdminText, Responses}
import io._3ch.bbs.repository.AdminRepository
import io._3ch.common.admin.{AdminCodec, AdminError, AdminErrorCode, Json, RuleName, ThreadAction}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 管理 API（/admin/api 以下）。認証は呼び出し側で済ませる */
object AdminApi {
  val MaxBodyBytes = 262144

  private final case class Rejected(error: AdminError) extends Exception(error.message)

  private def reject(code: AdminErrorCode, message: String, fields: Map[String, String] = Map.empty): Nothing =
    throw Rejected(AdminError(code, message, fields))

  /** JSON の応答。no-store・nosniff・no-referrer を付ける */
  def json(value: Json, status: Int = 200, extra: Seq[(String, String)] = Nil): Response = {
    val headers = js.Dictionary("content-type" -> "application/json; charset=UTF-8", "cache-control" -> "no-store", "x-content-type-options" -> "nosniff",
      "referrer-policy" -> "no-referrer")
    extra.foreach((name, value) => headers(name) = value)
    Responses.response(Json.render(value), status, headers)
  }

  def error(value: AdminError, extra: Seq[(String, String)] = Nil): Response = json(AdminCodec.error(value), value.code.status, extra)

  def handle(r: Request, e: WorkerEnv, path: String): Future[Response] = {
    val service = new AdminService(AdminRepository.files(e), AdminRepository.threads(e))
    val segments = path.stripPrefix("/admin/api").split("/", -1).toList.filter(_.nonEmpty)
    Future.unit.flatMap(_ => route(r, service, segments)).recover {
      case Rejected(value) => error(value)
      case ex =>
        js.Dynamic.global.console.error("admin api", ex.toString)
        error(AdminError(AdminErrorCode.Internal, AdminText.apiInternal))
    }
  }

  private def route(r: Request, service: AdminService, segments: List[String]): Future[Response] = (r.method, segments) match {
    case ("GET", List("boards")) => service.boards().map(list => json(AdminCodec.boardList(list)))
    case ("GET", List("boards", bbs, "settings")) =>
      val board = valid(AdminService.validBoard(bbs))
      service.boardSettings(board).map(view => json(AdminCodec.boardSettings(view)))
    case ("PUT", List("boards", bbs, "settings")) =>
      val board = valid(AdminService.validBoard(bbs))
      body(r, AdminCodec.boardSettingsUpdate).flatMap(service.saveBoardSettings(board, _)).map(result => json(AdminCodec.boardSettingsSaved(valid(result))))
    case ("GET", List("boards", bbs, "threads")) =>
      val board = valid(AdminService.validBoard(bbs))
      service.threadList(board).map(list => json(AdminCodec.threadList(list)))
    case ("GET", List("boards", bbs, "threads", key)) =>
      val (board, k) = thread(bbs, key)
      service.thread(board, k).map(result => json(AdminCodec.threadDetail(valid(result))))
    case ("POST", List("boards", bbs, "threads", key, "actions")) =>
      val (board, k) = thread(bbs, key)
      body(r, AdminCodec.threadAction).flatMap { action =>
        checkAction(action)
        service.moderate(board, k, action)
      }.map { result =>
        valid(result)
        json(Json.Obj(List("ok" -> Json.Bool(true))))
      }
    case ("GET", List("rules", name)) =>
      service.rules(ruleName(name)).map(file => json(AdminCodec.textFile(file)))
    case ("PUT", List("rules", name)) =>
      val rule = ruleName(name)
      body(r, AdminCodec.textFile).flatMap(file => service.saveRules(rule, file.text, AdminService.condition(file.etag)))
        .map(result => json(AdminCodec.saved(valid(result))))
    case ("GET", List("menu")) => service.menu().map(file => json(AdminCodec.textFile(file)))
    case ("PUT", List("menu")) =>
      body(r, AdminCodec.textFile).flatMap(file => service.saveMenu(file.text, AdminService.condition(file.etag)))
        .map(result => json(AdminCodec.saved(valid(result))))
    case (_, path) if known(path) => reject(AdminErrorCode.Method, AdminText.apiMethod)
    case _ => reject(AdminErrorCode.NotFound, AdminText.apiNotFound)
  }

  private def known(segments: List[String]): Boolean = segments match {
    case List("boards") | List("menu") | List("rules", _) | List("boards", _, "settings" | "threads") | List("boards", _, "threads", _) |
      List("boards", _, "threads", _, "actions") => true
    case _ => false
  }

  private def thread(bbs: String, key: String): (String, String) =
    (valid(AdminService.validBoard(bbs)), valid(AdminService.validKey(key)))

  private def ruleName(name: String): RuleName = RuleName.fromValue(name).getOrElse(reject(AdminErrorCode.NotFound, AdminText.apiNotFound))

  /** レス番号は 1 以上、名前・メールに改行を入れない、本文の CR は CRLF の一部に限る */
  private def checkAction(action: ThreadAction): Unit = {
    def number(n: Int): Option[(String, String)] = Option.when(n < 1)("number" -> AdminText.apiResNumber)
    def line(name: String, value: String): Option[(String, String)] =
      Option.when(value.contains('\r') || value.contains('\n'))(name -> AdminText.settingProblem(SettingProblem.LineBreak))
    val problems = action match {
      case ThreadAction.EditRes(n, name, mail, message) =>
        number(n).toList ++ line("name", name) ++ line("mail", mail) ++
          Option.when(message.replace("\r\n", "\n").contains('\r'))("message" -> AdminText.apiField)
      case ThreadAction.DeleteRes(n) => number(n).toList
      case ThreadAction.Move(targetBoard, targetKey) =>
        AdminService.validBoard(targetBoard, "targetBoard").left.toOption.map(failureField).toList ++
          AdminService.validKey(targetKey, "targetKey").left.toOption.map(failureField)
      case ThreadAction.Stop | ThreadAction.Resume | ThreadAction.Delete => Nil
    }
    if (problems.nonEmpty) reject(AdminErrorCode.Invalid, AdminText.apiInvalid, problems.toMap)
  }

  private def failureField(failure: AdminFailure): (String, String) = failure match {
    case AdminFailure.InvalidBoard(field) => field -> AdminText.failure(failure)
    case AdminFailure.InvalidKey(field) => field -> AdminText.failure(failure)
    case other => "body" -> AdminText.failure(other)
  }

  /** 失敗を応答のエラーにする */
  private def valid[A](result: Either[AdminFailure, A]): A = result.fold(failure => throw Rejected(toError(failure)), identity)

  private def toError(failure: AdminFailure): AdminError = {
    val message = AdminText.failure(failure)
    failure match {
      case AdminFailure.InvalidBoard(_) | AdminFailure.InvalidKey(_) => AdminError(AdminErrorCode.Invalid, message, Map(failureField(failure)))
      case AdminFailure.CreationLineBreak => AdminError(AdminErrorCode.Invalid, message)
      case AdminFailure.CreationSettings(_) => AdminError(AdminErrorCode.Invalid, message)
      case AdminFailure.Settings(problems) => AdminError(AdminErrorCode.Invalid, message, problems.map((field, problem) => field -> AdminText.settingProblem(problem)))
      case AdminFailure.Rules(_) => AdminError(AdminErrorCode.Invalid, message, Map("text" -> message))
      case AdminFailure.Conflict(_) => AdminError(AdminErrorCode.Conflict, message)
      case AdminFailure.ThreadMissing => AdminError(AdminErrorCode.NotFound, message)
      case AdminFailure.ThreadRefused(_) => AdminError(AdminErrorCode.ThreadState, message)
    }
  }

  /** application/json の本文を 256KB まで読んでデコードする */
  private def body[A](r: Request, decode: Json => AdminCodec.Decoded[A]): Future[A] = {
    val contentType = Option(r.headers.get("content-type")).getOrElse("").toLowerCase
    if (contentType.split(";")(0).trim != "application/json") reject(AdminErrorCode.ContentType, AdminText.apiContentType)
    if (js.Dynamic.global.Number(r.headers.get("content-length")).asInstanceOf[Double] > MaxBodyBytes) reject(AdminErrorCode.TooLarge, AdminText.apiTooLarge)
    r.asInstanceOf[js.Dynamic].text().asInstanceOf[js.Promise[String]].toFuture.map { text =>
      if (new TextEncoder().encode(text).length > MaxBodyBytes) reject(AdminErrorCode.TooLarge, AdminText.apiTooLarge)
      val parsed = Json.parse(text).fold(reason => reject(AdminErrorCode.Invalid, AdminText.apiMalformed, Map("body" -> reason)), identity)
      decode(parsed).fold(field => reject(AdminErrorCode.Invalid, AdminText.apiField, Map(field -> AdminText.apiField)), identity)
    }
  }
}
