package io._3ch.bbs.domain.posting

import io._3ch.common.domain.BbsSettings

/** 返信先スレの dat、スレ設定、スレ主。スレ主は読み込んだときに復号し、壊れていればそこで失敗する */
final case class ThreadSnapshot(dat: String, settings: BbsSettings, owner: () => Option[ThreadOwner])
