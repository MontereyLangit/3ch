package io._3ch.bbs.domain.posting

import io._3ch.bbs.domain.posting.moderation.ThreadCreationRejection

/** Thread DO へのレスの追記の結果 */
enum ThreadWriteResult {
  /** 書けた。number は確定したレス番号 */
  case Written(number: Int)
  /** スレ立て規制で拒否された */
  case CreationLimited(rejection: ThreadCreationRejection)
}
