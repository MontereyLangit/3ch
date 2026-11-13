package io._3ch.bbs.domain.posting

import io._3ch.bbs.domain.posting.moderation.{PostRateFinding, PostRateRejection, ThreadCreationRejection}
import io._3ch.common.domain.posting.ThreadWriteRejection

/** Thread DO へのレスの追記の結果 */
enum ThreadWriteResult {
  /** 書けた。number は確定したレス番号、observed は連投規制の試行モードで記録だけした判定 */
  case Written(number: Int, observed: Seq[PostRateFinding] = Nil)
  /** 連投規制のコピペとして書いたように見せて捨てた。number は採番する予定だった番号 */
  case Swallowed(number: Int)
  /** スレの状態で拒否された */
  case Refused(rejection: ThreadWriteRejection)
  /** スレ立て規制で拒否された */
  case CreationLimited(rejection: ThreadCreationRejection)
  /** 連投規制で拒否された */
  case RateLimited(rejection: PostRateRejection)
}
