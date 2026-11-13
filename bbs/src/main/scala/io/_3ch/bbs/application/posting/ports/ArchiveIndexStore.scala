package io._3ch.bbs.application.posting.ports

import scala.concurrent.Future

/** 保存済みの本文と ETag */
final case class StoredIndex(text: String, etag: Option[String])

/** 過去ログ目次のファイル */
enum ArchiveIndexFile {
  /** key の上 4 桁ごとのスレ一覧 */
  case ThreadIndex(bbs: String, key: String)
  /** 板のスレ帯の一覧 */
  case BandList(bbs: String)
}

/** 過去ログ目次と headline.txt（R2）。書き込みは ETag 条件付き */
trait ArchiveIndexStore {
  /** 無ければ None */
  def read(file: ArchiveIndexFile): Future[Option[StoredIndex]]

  /** etag があれば一致するときだけ、無ければまだ無いときだけ書く。書けたら true */
  def write(file: ArchiveIndexFile, value: String, etag: Option[String]): Future[Boolean]

  /** 無ければ None */
  def readHeadline(): Future[Option[StoredIndex]]

  /** headline.txt を etag が一致するときだけ書く。書けたかは返さない */
  def writeHeadline(value: String, etag: Option[String]): Future[Unit]
}
