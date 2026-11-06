package io._3ch.bbs.application.posting.ports

import io._3ch.bbs.domain.posting.moderation.{ModerationRuleFile, ModerationRuleSource}

import scala.concurrent.Future

/** 規制ルール（deny.cgi・ngwords.cgi） */
trait RuleStore {
  /** ルールファイルを読む。読めないときの失敗は Future の失敗のまま */
  def load(file: ModerationRuleFile): Future[ModerationRuleSource]
}
