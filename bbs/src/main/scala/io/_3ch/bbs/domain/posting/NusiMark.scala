package io._3ch.bbs.domain.posting

/** 返信の名前欄に付ける ` (主)` の判定（docs/トリップ・ID廃止とスレ主表示.md §2.4） */
object NusiMark {
  /** 名前欄の末尾に付ける印 */
  val suffix: String = " (主)"

  /** 返信で、キャップ・!no・板とスレの BBS_DISABLE_NUSI のどれにも当たらず、投稿者がスレ主と一致すれば付ける */
  def applies(isReply: Boolean, cap: Boolean, noNusi: Boolean, boardDisabled: Boolean, threadDisabled: Boolean,
              owner: Option[ThreadOwner], poster: ThreadOwner): Boolean =
    isReply && !cap && !noNusi && !boardDisabled && !threadDisabled && owner.exists(_.matches(poster))
}
