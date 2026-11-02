package ch3s.bbs.domain.posting

/** 認証済の投稿者。`accountKey` は移行しても変わらない内部 ID、`displayId` は表示するユーザー ID。 */
final case class PostingUser(accountKey: String, displayId: String)

object PostingUser {
  /** `!id` 指定時に名前欄へ付ける ` </b>(@ユーザーID)<b>`。未認証・キャップ・指定なしは空（docs/トリップ・ID廃止とスレ主表示.md §2.3）。 */
  def nameSuffix(user: Option[PostingUser], showId: Boolean, cap: Boolean): String =
    if (showId && !cap) user.fold("")(u => s" </b>(@${escape(u.displayId)})<b>") else ""

  private def escape(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
