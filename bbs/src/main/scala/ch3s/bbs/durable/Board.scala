package ch3s.bbs.durable

import ch3s.bbs.external.cloudflare.{DurableObject, DurableObjectState}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future
import ch3s.common.text.Utf8
import ch3s.bbs.domain.posting.moderation.ThreadCreationHistory
import ch3s.bbs.durable.codec.ThreadCreationCodec
import ch3s.bbs.durable.support.DurableLock

/**
 * 板のスレッド一覧を管理するDurable Object (Board)
 * 1つの掲示板に対して1つのインスタンスが対応します。
 */
@JSExportTopLevel("Board", "server")
class Board(val state: DurableObjectState, env: js.Any) extends DurableObject(state, env) {

  private val creationPageSize = 64
  private val creationHistoryLimit = 8000
  private val creationPageCountKey = "threadCreationPageCount"
  private def creationPageKey(index: Int): String = s"threadCreationHistory:$index"

  private def creationHistory(): Future[ThreadCreationHistory] = {
    state.storage.get[Int](creationPageCountKey).toFuture.flatMap { count =>
      val keys = (0 until count.getOrElse(0)).map(creationPageKey)
      Future.sequence(keys.grouped(128).map { batch =>
        state.storage.get(batch.toJSArray).toFuture.map { values =>
          batch.flatMap(key => values.get(key).toSeq.flatMap(_.asInstanceOf[js.Array[js.Dynamic]].toSeq))
        }
      }.toVector).map(pages => ThreadCreationHistory(pages.flatten.map(ThreadCreationCodec.decodeRecord)))
    }
  }

  private def saveCreationHistory(history: ThreadCreationHistory): Future[Unit] = {
    val pages = history.records.map(ThreadCreationCodec.encodeRecord).grouped(creationPageSize).toVector
    state.storage.get[Int](creationPageCountKey).toFuture.flatMap { previousCount =>
      val values = js.Dictionary[js.Any](creationPageCountKey -> pages.size)
      pages.zipWithIndex.foreach { case (page, index) => values(creationPageKey(index)) = page.toJSArray }
      state.storage.put(values).toFuture.flatMap { _ =>
        Future.sequence((pages.size until previousCount.getOrElse(0)).map(index => state.storage.delete(creationPageKey(index)).toFuture)).map(_ => ())
      }
    }
  }

  private def nowSeconds(): Double = js.Date.now() / 1000

  /** スレ立て履歴の判定と予約を直列化する。成功は空文字、拒否は [[ThreadCreationRejection.code]] を返す。 */
  def reserveThreadCreation(key: String, limit: js.Dynamic): js.Promise[String] = DurableLock.serialized(state) {
    val now = nowSeconds()
    val (settings, request) = ThreadCreationCodec.decodeRequest(limit)
    creationHistory().flatMap { history =>
      history.reserve(key, request, settings, now, creationHistoryLimit) match {
        case Left(rejection) => Future.successful(rejection.code)
        case Right(reserved) => saveCreationHistory(reserved).map(_ => "")
      }
    }
  }.toJSPromise

  def completeThreadCreation(key: String): js.Promise[Unit] = DurableLock.serialized(state) {
    val now = nowSeconds()
    creationHistory().flatMap(history => saveCreationHistory(history.complete(key, now)))
  }.toJSPromise

  def cancelThreadCreation(key: String): js.Promise[Unit] = DurableLock.serialized(state) {
    val now = nowSeconds()
    creationHistory().flatMap(history => saveCreationHistory(history.cancel(key, now)))
  }.toJSPromise

  /**
   * subject.txt形式の文字列を返します。
   */
  def getSubject(): js.Promise[String] =
    state.storage.get[String]("subjectList").toFuture.map(_.getOrElse("")).toJSPromise

  def getRemoved(): js.Promise[js.Array[String]] =
    state.storage.get[js.Array[String]]("removed").toFuture.map(_.getOrElse(js.Array[String]())).toJSPromise

  def removeThread(key: String): js.Promise[Unit] = state.blockConcurrencyWhile(() =>
    (for {
      subject <- getSubject().toFuture
      removed <- getRemoved().toFuture
      lines = subject.split("\n", -1).filter(s => s.nonEmpty && !s.startsWith(s"$key.dat<>"))
      _ <- state.storage.put(js.Dictionary[js.Any](
        "subjectList" -> (if lines.isEmpty then "" else lines.mkString("\n") + "\n"),
        "removed" -> (if removed.contains(key) then removed else removed.concat(js.Array(key)))
      )).toFuture
    } yield ()).toJSPromise)

  /**
   * スレッド一覧を更新します。
   * @param data - 更新対象のスレッド情報
   * @param sage - sage投稿の場合はtrue
   * @param maxThreads - 板の最大スレッド数
   */
  def updateThread(data: js.Dynamic, sage: js.UndefOr[Boolean] = js.undefined, maxThreads: js.UndefOr[Double] = js.undefined): js.Promise[Unit] =
    state.blockConcurrencyWhile(() => updateThreadUnlocked(data, sage, maxThreads))

  private def updateThreadUnlocked(data: js.Dynamic, sage: js.UndefOr[Boolean], maxThreads: js.UndefOr[Double]): js.Promise[Unit] =
    getSubject().toFuture.flatMap { subject =>
      var lines = if subject.isEmpty then Vector.empty[String] else subject.split("\n", -1).toVector
      if lines.lastOption.contains("") then lines = lines.dropRight(1) // 最後の空行を除外
      val key = data.key.asInstanceOf[String]
      val index = lines.indexWhere(_.startsWith(s"$key.dat<>"))
      val title = if index >= 0 && (js.isUndefined(data.title) || data.title == null) then {
        // titleがnullの場合は既存の行からタイトルを抽出して使用する
        val old = lines(index)
        old.asInstanceOf[js.Dynamic].substring(old.indexOf("<>") + 2, old.lastIndexOf(" (")).asInstanceOf[String]
      } else js.Dynamic.global.String(data.title).asInstanceOf[String]
      val line = s"$key.dat<>$title (${js.Dynamic.global.String(data.resNum).asInstanceOf[String]})"
      // 既存スレ更新でsageの場合レス数のみ更新
      if index >= 0 && js.Dynamic.global.Boolean(sage).asInstanceOf[Boolean] then lines = lines.updated(index, line)
      else {
        // 先頭に持ってくる
        if index >= 0 then lines = lines.patch(index, Nil, 1)
        lines = line +: lines
      }
      // 最大スレッド数のデフォルト値を適用
      val limit = if js.isUndefined(maxThreads) || maxThreads.asInstanceOf[js.Any] == null then 700.0 else maxThreads.get
      if lines.length > limit then {
        // Array.slice(0, negative) counts from the end; do not turn it into take(negative).
        val end = if limit < 0 then math.max(0, lines.length + limit.toInt) else limit.toInt
        lines = lines.take(end)
      }
      def render = if lines.isEmpty then "" else lines.mkString("\n") + "\n"
      var result = render
      while Utf8.encode(result).length > 512 * 1024 && lines.nonEmpty do {
        lines = lines.dropRight(1)
        result = render
      }
      state.storage.put("subjectList", result).toFuture
    }.toJSPromise
}
