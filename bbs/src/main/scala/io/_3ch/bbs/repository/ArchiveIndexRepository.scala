package io._3ch.bbs.repository

import io._3ch.bbs.application.posting.ports.{ArchiveIndexFile, ArchiveIndexStore, StoredIndex}
import io._3ch.bbs.external.cloudflare.WorkerEnv

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** R2 の過去ログ目次（スレ帯別と板全体の subject.txt）と headline.txt。書き込みは ETag 条件付き */
object ArchiveIndexRepository {
  val HeadlineFile = "headline.txt"

  /** key の上 4 桁ごとの過去ログ目次 */
  def subjectFile(board: String, key: String): String = s"$board/thread/${key.take(4)}/subject.txt"

  /** スレ帯の一覧 */
  def archiveListFile(board: String): String = s"$board/thread/subject.txt"

  /** 無ければ None */
  def read(file: String, e: WorkerEnv): Future[Option[StoredIndex]] =
    e.ch3www.get(file).toFuture.flatMap { obj =>
      if (obj == null) Future.successful(None)
      else {
        val etag = obj.asInstanceOf[js.Dynamic].etag.asInstanceOf[js.Any]
        obj.text().toFuture.map(text => Some(StoredIndex(text, if (etag == null || js.isUndefined(etag)) None else Some(etag.toString))))
      }
    }

  /**
    * 空でない ETag があれば一致するときだけ書き、無ければ doesNotExist を付ける（R2 はこの条件を解さず無条件に書く。KB-25）
    * 書けたら true
    */
  def writeIndex(file: String, value: String, etag: Option[String], e: WorkerEnv): Future[Boolean] = {
    val condition = etag.filter(_.nonEmpty).fold(js.Dynamic.literal(doesNotExist = true))(t => js.Dynamic.literal(etagMatches = t))
    put(file, value, condition, e).map(result => result != null && js.Dynamic.global.Boolean(result.uploaded).asInstanceOf[Boolean])
  }

  /** headline.txt を ETag が一致するときだけ書く。ETag が無ければ条件なしで、書けたかは見ない */
  def writeHeadline(value: String, etag: Option[String], e: WorkerEnv): Future[Unit] =
    put(HeadlineFile, value, js.Dynamic.literal(etagMatches = etag.orUndefined), e).map(_ => ())

  private def put(file: String, value: String, condition: js.Dynamic, e: WorkerEnv): Future[js.Dynamic] =
    e.ch3www.asInstanceOf[js.Dynamic].put(file, value, js.Dynamic.literal(onlyIf = condition)).asInstanceOf[js.Promise[js.Dynamic]].toFuture

  /** 投稿処理のポート */
  def store(e: WorkerEnv): ArchiveIndexStore = new ArchiveIndexStore {
    private def path(file: ArchiveIndexFile): String = file match {
      case ArchiveIndexFile.ThreadIndex(bbs, key) => subjectFile(bbs, key)
      case ArchiveIndexFile.BandList(bbs) => archiveListFile(bbs)
    }
    def read(file: ArchiveIndexFile): Future[Option[StoredIndex]] = ArchiveIndexRepository.read(path(file), e)
    def write(file: ArchiveIndexFile, value: String, etag: Option[String]): Future[Boolean] = writeIndex(path(file), value, etag, e)
    def readHeadline(): Future[Option[StoredIndex]] = ArchiveIndexRepository.read(HeadlineFile, e)
    def writeHeadline(value: String, etag: Option[String]): Future[Unit] = ArchiveIndexRepository.writeHeadline(value, etag, e)
  }
}
