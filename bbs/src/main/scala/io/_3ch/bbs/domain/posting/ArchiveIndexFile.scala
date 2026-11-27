package io._3ch.bbs.domain.posting

/** 保存済みの本文と ETag */
final case class StoredIndex(text: String, etag: Option[String])

/** 過去ログ目次のファイル */
enum ArchiveIndexFile {

  /** key の上 4 桁ごとのスレ一覧 */
  case ThreadIndex(bbs: String, key: String)

  /** 板のスレ帯の一覧 */
  case BandList(bbs: String)
}
