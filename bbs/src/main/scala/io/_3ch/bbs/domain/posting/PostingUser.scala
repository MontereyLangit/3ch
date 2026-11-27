package io._3ch.bbs.domain.posting

/** 認証済みの投稿者。`accountKey` は移行しても変わらない内部 ID、`displayId` は表示するユーザー ID */
final case class PostingUser(accountKey: String, displayId: String)

object PostingUser {
  /** `!id` を指定したときに名前欄へ付ける ` </b>(@ユーザーID)<b>`。未認証、キャップ、指定なしのときは空（docs/トリップ・ID廃止とスレ主表示.md §2.3） */
  def nameSuffix(user: Option[PostingUser], showId: Boolean, cap: Boolean): String =
    if (showId && !cap) user.fold("")(u => s" </b>(@${escape(u.displayId)})<b>") else ""

  private def escape(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
