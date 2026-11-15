package io._3ch.bbs.application.posting.ports

import io._3ch.bbs.domain.posting.{StoredIndex, ArchiveIndexFile}
import scala.concurrent.Future

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
