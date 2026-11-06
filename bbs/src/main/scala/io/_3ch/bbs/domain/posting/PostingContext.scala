package io._3ch.bbs.domain.posting

import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.posting.{CapHandle, MailCommands, PostForm}

import scala.collection.immutable.ListMap

/**
  * 投稿処理の途中の状態。段ごとに copy で埋め、拒否したときはその時点の form と identity.host をエラー画面に出す
  * settings は板設定を返信ならスレ設定で上書きしたもの、number はレス番号（書き込み後は確定した番号）、threadOwner は呼んだときに復号する返信先のスレ主
  * mailCommands は認証を有効にした環境でだけ読むメール欄のコマンド
  */
final case class PostingContext(
  form: PostForm,
  identity: PostingIdentity,
  time: String,
  unixtime: String,
  settings: BbsSettings = BbsSettings(ListMap.empty),
  number: Int = 0,
  title: String = "",
  cap: Option[CapHandle] = None,
  shikibetsu: String = "",
  threadOwner: () => Option[ThreadOwner] = () => None,
  sage: Boolean = false,
  ponCookie: Option[String] = None,
  capKey: Option[String] = None,
  mailCommands: MailCommands = MailCommands.empty,
  sessionCookie: Option[String] = None
) {
  /** スレ立てか。レス番号が 1 のとき */
  def creating: Boolean = number == 1
}
