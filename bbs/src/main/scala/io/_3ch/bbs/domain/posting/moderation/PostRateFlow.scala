package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateLimit.Entry

/** Thread.addRes の連投規制の組み合わせ（返信のみの判定、規制モードと試行モードの違い、コピペの吸い込み）。DO はこの結果に従って保存する */
object PostRateFlow {
  /** スレ単位の判定結果。refusal は規制モードで拒否する理由、observed は試行モードで記録だけする理由 */
  final case class ThreadJudgement(refusal: Option[PostRateRejection], observed: Option[PostRateRejection])

  /** 最終的な扱い。Swallow は成功画面のまま dat に残さない。Store は保存し、observed を試行モードの記録として返す */
  enum Verdict {
    case Store(observed: Seq[PostRateFinding])
    case Swallow
  }

  /** スレ単位の判定。>>1 とスレ立て（返信でない書き込み）は見ない */
  def judgeThread(check: PostRateCheck, reply: Boolean, recent: ThreadRecentPosts, now: Double): ThreadJudgement = {
    val rejection = if (reply) recent.check(check.thread, check.identity, now) else None
    if (check.enforce) ThreadJudgement(rejection, None) else ThreadJudgement(None, rejection)
  }

  /** Board.reservePost が返した拒否コード（空は許可）のうち、規制モードで投稿を拒否するもの */
  def boardRefusal(check: PostRateCheck, boardCode: String): Option[String] =
    Option.when(check.enforce && boardCode.nonEmpty)(boardCode)

  /** 板の判定後の扱い。規制モードのコピペは吸い込み、それ以外は保存して試行モードの判定を集める */
  def conclude(check: PostRateCheck, reply: Boolean, thread: ThreadJudgement, boardCode: String, dat: String, datLine: String): Verdict = {
    val copipe = reply && PostRateLimit.copipe(dat, datLine)
    if (copipe && check.enforce) Verdict.Swallow
    else Verdict.Store(thread.observed.map(PostRateFinding.Rejected(_)).toSeq ++ PostRateFinding.fromCode(boardCode) ++ Option.when(copipe)(PostRateFinding.Copipe))
  }

  /** 新しい投稿を足した直近の投稿 */
  def recorded(check: PostRateCheck, recent: ThreadRecentPosts, now: Double): ThreadRecentPosts =
    recent.appended(Entry(now, check.identity), check.thread, now)
}
