package io._3ch.bbs.domain.posting

/** 返信の名前欄に付ける ` (主)` の判定（docs/トリップ・ID廃止とスレ主表示.md §2.4）。 */
object NusiMark {
  val suffix: String = " (主)"

  def applies(isReply: Boolean, cap: Boolean, noNusi: Boolean, boardDisabled: Boolean, threadDisabled: Boolean,
              owner: Option[ThreadOwner], poster: ThreadOwner): Boolean =
    isReply && !cap && !noNusi && !boardDisabled && !threadDisabled && owner.exists(_.matches(poster))
}
