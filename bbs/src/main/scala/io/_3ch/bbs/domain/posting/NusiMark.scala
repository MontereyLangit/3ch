package io._3ch.bbs.domain.posting

/** 名前欄に付ける ` </b>(主)<b>` の判定（>>1 は投稿者自身が owner）（docs/トリップ・ID廃止とスレ主表示.md §2.4）。 */
object NusiMark {
  val suffix: String = " </b>(主)<b>"

  /** キャップ・!no・板とスレの BBS_DISABLE_NUSI のどれにも当たらず、投稿者がスレ主と一致すれば付ける */
  def applies(cap: Boolean, noNusi: Boolean, boardDisabled: Boolean, threadDisabled: Boolean,
              owner: Option[ThreadOwner], poster: ThreadOwner): Boolean =
    !cap && !noNusi && !boardDisabled && !threadDisabled && owner.exists(_.matches(poster))
}
