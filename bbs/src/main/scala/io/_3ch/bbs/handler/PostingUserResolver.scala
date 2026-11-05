package io._3ch.bbs.handler

import io._3ch.bbs.domain.posting.PostingUser
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.Request
import scala.concurrent.Future

/** 投稿者の認証主体の解決（docs/トリップ・ID廃止とスレ主表示.md §2.3）。 */
object PostingUserResolver {
  type Resolver = (Request, WorkerEnv) => Future[Option[PostingUser]]

  // TODO: 認証ブランチで Cookie / 専ブラ認証キーからの解決に差し替える。
  val unresolved: Resolver = (_, _) => Future.successful(None)
}
