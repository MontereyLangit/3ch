package io._3ch.bbs.repository

import io._3ch.bbs.application.posting.ports.RuleStore
import io._3ch.bbs.domain.posting.moderation.{ModerationRuleFile, ModerationRuleSet, ModerationRuleSource}
import io._3ch.bbs.external.cloudflare.WorkerEnv

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** R2 の規制ルール（deny.cgi / ngwords.cgi） */
object ModerationRuleRepository {
  /** ルールファイルを読む。R2 の get の失敗はそのまま失敗にし、本文が読めないか書式が不正なら Broken */
  def load(file: ModerationRuleFile, e: WorkerEnv): Future[ModerationRuleSource] =
    e.ch3www.get(file.path).toFuture.flatMap { obj =>
      if (obj == null) Future.successful(ModerationRuleSource.Missing)
      else obj.text().toFuture.map { text =>
        ModerationRuleSet.parse(text).fold(_ => ModerationRuleSource.Broken, ModerationRuleSource.Loaded(_))
      }.recover { case _ => ModerationRuleSource.Broken }
    }

  /** 投稿処理のポート */
  def store(e: WorkerEnv): RuleStore = file => load(file, e)
}
