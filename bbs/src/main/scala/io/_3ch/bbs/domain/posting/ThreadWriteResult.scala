package io._3ch.bbs.domain.posting

import io._3ch.bbs.domain.posting.moderation.ThreadCreationRejection
import io._3ch.common.domain.posting.ThreadWriteRejection

/** Thread DO へのレスの追記の結果 */
enum ThreadWriteResult {
  /** 書けた。number は確定したレス番号 */
  case Written(number: Int)
  /** スレの状態で拒否された */
  case Refused(rejection: ThreadWriteRejection)
  /** スレ立て規制で拒否された */
  case CreationLimited(rejection: ThreadCreationRejection)
}
