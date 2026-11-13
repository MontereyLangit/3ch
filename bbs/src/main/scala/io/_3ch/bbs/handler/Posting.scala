package io._3ch.bbs.handler

import io._3ch.bbs.application.posting.{PostingOutcome, PostingPorts, PostingRequest, PostingService}
import io._3ch.bbs.application.posting.ports.{Clock, Random}
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
      users = () => resolveUser(request, env),
      hasher = WebHasher,
      clock = Clock.system,
      random = Random.system
    )
    new PostingService(ports, String.valueOf(env.salt)).post(PostingRequest.from(request)).map(respond).toJSPromise
  }

  private def respond(outcome: PostingOutcome): Response = {
    val context = outcome.context
    val charset = if (context.form.isSJIS) "Shift_JIS" else "UTF-8"
    val posted = outcome match {
      case PostingOutcome.Posted(_, _) => Seq("x-threadkey" -> context.form.key, "x-resnum" -> context.number.toString)
      case _ => Nil
    }
    RendererShell.legacyReact(PostingPages.render(outcome, charset), encoding = charset, extra = outcome.headers ++ posted)
  }
}
