package io._3ch.bbs.domain.posting

import io._3ch.bbs.domain.posting.moderation.ThreadCreationLimit

/**
  * Thread DO の addRes に渡す投稿の付帯情報。初期スレ設定・スレ立て規制・スレ主はスレ立てのときだけ使う
  * 旧形式やテストの部分的な引数で項目が無いときは None
  */
final case class ThreadPosting(
  bbs: Option[String] = None,
  key: Option[String] = None,
  title: Option[String] = None,
  sage: Boolean = false,
  maxThreads: Option[Double] = None,
  create: Boolean = false,
  initialSettings: Option[String] = None,
  creationLimit: Option[ThreadCreationLimit] = None,
  owner: Option[ThreadOwner] = None
)
