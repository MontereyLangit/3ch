package io._3ch.bbs.handler

import io._3ch.bbs.application.posting.{PostingOutcome, PostingPorts, PostingRequest, PostingService}
import io._3ch.bbs.application.posting.ports.{Clock, PostRateLog, Random}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.{Request, Response}
import io._3ch.bbs.hash.WebHasher
import io._3ch.bbs.repository.{ArchiveIndexRepository, BoardRepository, CapRepository, ModerationRuleRepository, ThreadRepository}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** bbs.cgi の書き込み。リクエストを PostingRequest にしてサービスに渡し、結果を画面にする */
object Posting {
  def entry(request: Request, env: WorkerEnv, resolveUser: PostingUserResolver.Resolver = PostingUserResolver.unresolved): js.Promise[Response] = {
    val ports = PostingPorts(
      threads = ThreadRepository.store(env),
      settings = BoardRepository.settingsStore(env),
      rules = ModerationRuleRepository.store(env),
      caps = CapRepository.store(env),
      archive = ArchiveIndexRepository.store(env),
      users = if (env.AUTH_SECRET.exists(_.nonEmpty)) new io._3ch.bbs.repository.PostingAccountSource(request, env) else () => resolveUser(request, env),
      hasher = WebHasher,
      clock = Clock.system,
      random = Random.system,
      rateLog = PostRateLog.console
    )
    new PostingService(ports, String.valueOf(env.salt)).post(PostingRequest.from(request)).map(outcome => respond(outcome, env.AUTH_SECRET.exists(_.nonEmpty))).toJSPromise
  }

  private def respond(outcome: PostingOutcome, authenticated: Boolean): Response = {
    val context = outcome.context
    val charset = if (context.form.isSJIS) "Shift_JIS" else "UTF-8"
    val posted = outcome match {
      case PostingOutcome.Rejected(_, _, _) => Nil
      case _ => Seq("x-threadkey" -> context.form.key, "x-resnum" -> context.number.toString)
    }
    RendererShell.legacyReact(PostingPages.render(outcome, charset), encoding = charset, extra = outcome.headers ++ posted ++ (if (authenticated) outcome match {
      case PostingOutcome.Rejected(error, context, _) =>
        val authUrl = "/auth/?return=/" + context.form.bbs + "/"
        val headers = Seq("x-post-error" -> error.code)
        error match {
          case io._3ch.bbs.domain.posting.PostingError.Authentication(io._3ch.bbs.domain.account.AuthenticationFailure.LoginRequired) => headers ++ Seq("x-auth-required" -> "login", "x-auth-url" -> authUrl)
          case io._3ch.bbs.domain.posting.PostingError.AuthenticationLevel(required) => headers ++ Seq("x-auth-required" -> "level", "x-phoebe-required" -> required.toString, "x-auth-url" -> authUrl)
          case _ => headers
        }
      case _ => Nil
    } else Nil))
  }
}
