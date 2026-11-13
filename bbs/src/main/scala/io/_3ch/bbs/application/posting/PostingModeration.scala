package io._3ch.bbs.application.posting

import io._3ch.bbs.application.posting.ports.RuleStore
import io._3ch.bbs.domain.posting.moderation.{ModerationDecision, ModerationInput, ModerationRuleFile, ModerationService}
import io._3ch.common.domain.posting.PostForm

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** deny.cgi・ngwords.cgi を順に読み、拒否した時点で読み込みを止める */
object PostingModeration {
  /** 免除・Tor の判定ではファイルを読まず、読み込みの障害は Future の失敗で返す */
  def check(input: ModerationInput, store: RuleStore): Future[ModerationDecision] = {
    def loop(files: List[ModerationRuleFile], form: PostForm): Future[ModerationDecision] = files match {
      case Nil => Future.successful(ModerationDecision.Pass(form))
      case file :: rest => store.load(file).flatMap { source =>
        ModerationService.evaluate(input, form, source) match {
          case ModerationDecision.Pass(next) => loop(rest, next)
          case denied => Future.successful(denied)
        }
      }
    }
    ModerationService.beforeRules(input).fold(loop(ModerationRuleFile.values.toList, input.form))(Future.successful)
  }
}
