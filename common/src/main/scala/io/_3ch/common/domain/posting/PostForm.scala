package io._3ch.common.domain.posting

/** 投稿の種類 */
enum PostKind {
  case NewThread, Reply
}

/**
  * bbs.cgi のフォーム入力（サニタイズ済み）。
  * showId・noNusi は名前欄の !id・!no（showid 欄を含む）、isSJIS は Shift_JIS で送られたか
  */
final case class PostForm(
  name: String = "",
  mail: String = "",
  message: String = "",
  subject: String = "",
  bbs: String = "",
  key: String = "",
  time: String = "",
  submit: String = "",
  showId: Boolean = false,
  noNusi: Boolean = false,
  isSJIS: Boolean = false,
  isMonazilla: Boolean = false
) {

  /** 振り分け後の投稿の種類。題名が残っていればスレ立て */
  def kind: PostKind = if (subject.nonEmpty) PostKind.NewThread else PostKind.Reply
}
