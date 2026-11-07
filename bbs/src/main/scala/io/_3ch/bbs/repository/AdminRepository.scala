package io._3ch.bbs.repository

import io._3ch.bbs.application.admin.ports.{AdminFiles, AdminThreads, ThreadRecord, WriteCondition, WriteResult}
import io._3ch.bbs.durable.codec.ThreadModerationCodec
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.common.admin.{TextFile, ThreadAction}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 管理操作の R2 ファイルと Board / Thread DO */
object AdminRepository {
  private def etagOf(value: js.Dynamic): Option[String] = {
    val etag = value.etag.asInstanceOf[js.Any]
    if (etag == null || js.isUndefined(etag)) None else Some(etag.toString)
  }

  def files(e: WorkerEnv): AdminFiles = new AdminFiles {
    def read(path: String): Future[Option[TextFile]] = e.ch3www.get(path).toFuture.flatMap { obj =>
      if (obj == null) Future.successful(None)
      else obj.text().toFuture.map(text => Some(TextFile(text, etagOf(obj.asInstanceOf[js.Dynamic]))))
    }

    def write(path: String, text: String, condition: WriteCondition): Future[WriteResult] = {
      val bucket = e.ch3www.asInstanceOf[js.Dynamic]
      val result = condition match {
        case WriteCondition.Always => bucket.put(path, text)
        case WriteCondition.Matches(etag) => bucket.put(path, text, js.Dynamic.literal(onlyIf = js.Dynamic.literal(etagMatches = etag)))
        case WriteCondition.Absent => bucket.put(path, text, js.Dynamic.literal(onlyIf = js.Dynamic.literal(etagDoesNotMatch = "*")))
      }
      result.asInstanceOf[js.Promise[js.Dynamic]].toFuture.map { written =>
        if (written == null) WriteResult.Conflict else WriteResult.Written(if (js.isUndefined(written)) None else etagOf(written))
      }
    }

    def topPrefixes(): Future[List[String]] = {
      def page(cursor: Option[String], acc: List[String]): Future[List[String]] = {
        val options = js.Dynamic.literal(delimiter = "/")
        cursor.foreach(c => options.cursor = c)
        e.ch3www.list(options).toFuture.flatMap { result =>
          val prefixes = acc ++ result.delimitedPrefixes.toList.map(_.stripSuffix("/"))
          if (result.truncated && result.cursor.isDefined) page(result.cursor.toOption, prefixes) else Future.successful(prefixes)
        }
      }
      page(None, Nil)
    }
  }

  def threads(e: WorkerEnv): AdminThreads = new AdminThreads {
    def subject(bbs: String): Future[String] = e.BOARD_DO.get(e.BOARD_DO.idFromName(bbs)).getSubject().toFuture

    def removed(bbs: String): Future[List[String]] = e.BOARD_DO.get(e.BOARD_DO.idFromName(bbs)).getRemoved().toFuture.map(_.toList)

    def inspect(bbs: String, key: String): Future[ThreadRecord] =
      e.THREAD_DO.get(e.THREAD_DO.idFromName(s"$bbs/$key")).inspect(bbs, key).toFuture.map(ThreadModerationCodec.decode)

    def moderate(bbs: String, key: String, action: ThreadAction): Future[Either[String, Unit]] =
      e.THREAD_DO.get(e.THREAD_DO.idFromName(s"$bbs/$key")).moderate(action.name, ThreadModerationCodec.encode(bbs, key, action)).toFuture
        .map(_ => Right(()))
        .recover {
          case x: js.JavaScriptException =>
            val message = x.exception.asInstanceOf[js.Dynamic].message.asInstanceOf[js.Any]
            Left(if (message == null || js.isUndefined(message)) "" else message.toString)
          case other => Left(other.getMessage)
        }
  }
}
