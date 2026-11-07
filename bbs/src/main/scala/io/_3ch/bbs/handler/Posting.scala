package io._3ch.bbs.handler

import io._3ch.bbs.application.auth.PostingAccountService
import io._3ch.bbs.application.posting.{PostingOutcome, PostingPorts, PostingRequest, PostingService}
import io._3ch.bbs.application.posting.ports.{Clock, PostRateLog, Random}
import io._3ch.bbs.domain.account.AuthenticationFailure
import io._3ch.bbs.domain.posting.PostingError
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.{Request, Response}
import io._3ch.bbs.handler.auth.{AuthEnv, SessionCredentials}
import io._3ch.bbs.hash.WebHasher
import io._3ch.common.domain.account.AccountRejection
import io._3ch.bbs.repository.{AccountRepository, ArchiveIndexRepository, BoardRepository, CapRepository, ModerationRuleRepository, ThreadRepository, UserAgentTableRepository, EdgeRuleRepository, PhoebeRuleRepository}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** bbs.cgi の書き込み。リクエストを PostingRequest にしてサービスに渡し、結果を画面にする */
object Posting {
  def entry(request: Request, env: WorkerEnv, resolveUser: PostingUserResolver.Resolver = PostingUserResolver.unresolved): js.Promise[Response] = {
    val settings = AuthEnv.settings(env)
    val ports = PostingPorts(
      threads = ThreadRepository.store(env),
      settings = BoardRepository.settingsStore(env),
      rules = ModerationRuleRepository.store(env),
      caps = CapRepository.store(env),
      archive = ArchiveIndexRepository.store(env),
      users = () => resolveUser(request, env),
      hasher = WebHasher,
      clock = Clock.system,
      random = Random.system,
      rateLog = PostRateLog.console,
      accounts = settings.map(config => new PostingAccountService(new AccountRepository(env), new SessionCredentials(config.secret, request.url),
        config.agreementVersion, _ => js.Dynamic.global.console.warn("account post recording failed"))),
      agents = UserAgentTableRepository.store(env),
      edge = EdgeRuleRepository.store(env),
      phoebe = PhoebeRuleRepository.store(env)
    )
    new PostingService(ports, String.valueOf(env.salt)).post(PostingRequest.from(request)).map(outcome => respond(outcome, settings.isDefined)).toJSPromise
  }

  private def respond(outcome: PostingOutcome, authenticated: Boolean): Response = {
    val context = outcome.context
    val charset = if (context.form.isSJIS) "Shift_JIS" else "UTF-8"
    val result = outcome match {
      case PostingOutcome.Rejected(error, rejected, _) => if (authenticated) rejectionHeaders(error, rejected.form.bbs) else Nil
      case _ => Seq("x-threadkey" -> context.form.key, "x-resnum" -> context.number.toString)
    }
    RendererShell.legacyReact(PostingPages.render(outcome, charset), encoding = charset, extra = outcome.headers ++ result)
  }

  /** 認証を有効にした環境の拒否に付ける追加ヘッダ。ログインと再同意はモーダルで、Lv 不足は必要 Lv を返す */
  private[handler] def rejectionHeaders(error: PostingError, bbs: String): Seq[(String, String)] = {
    val authUrl = "/auth/?return=/" + bbs + "/"
    val code = Seq("x-post-error" -> error.code)
    error match {
      case PostingError.Authentication(AuthenticationFailure.LoginRequired | AuthenticationFailure.Account(AccountRejection.Agreement)) =>
        code ++ Seq("x-auth-required" -> "login", "x-auth-url" -> authUrl)
      case PostingError.AuthenticationLevel(required) =>
        code ++ Seq("x-auth-required" -> "level", "x-phoebe-required" -> required.toString, "x-auth-url" -> authUrl)
      case _ => code
    }
  }
}
