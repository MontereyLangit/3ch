package io._3ch.bbs.domain.posting

import io._3ch.bbs.domain.posting.moderation.{PostRateCheck, ThreadCreationLimit}
import io._3ch.common.domain.posting.{PhoebeAdmission, ThreadPhoebeRequest}

/**
  * Thread DO の addRes に渡す投稿の付帯情報。初期スレ設定・スレ立て規制・スレ主はスレ立てのときだけ使う
  * 旧形式やテストの部分的な引数で項目が無いときは None。rateLimit が無ければ連投規制を判定しない。phoebe は本文コマンドによるスレ設定の変更。
  * admission があればロック内で最新のスレ設定の BBS_PHOEBE を判定し直す
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
  owner: Option[ThreadOwner] = None,
  rateLimit: Option[PostRateCheck] = None,
  phoebe: Option[ThreadPhoebeRequest] = None,
  admission: Option[PhoebeAdmission] = None
)
