package ch3s.bbs.durable

import ch3s.bbs.external.cloudflare.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future
import scala.collection.mutable

/**
 * `tests/thread.test.js` の `state()` / `setup()` をScala側に移植したテストダブル。
 * 狙いは同じ: 本番の [[Thread]] / [[Board]] のコードは一切変えず、
 * `DurableObjectState.storage` / `blockConcurrencyWhile` / `THREAD_DO` / `BOARD_DO` / `ch3www`(R2)
 * の最小実装だけを与えて実際のDOメソッドを動かす。
 *
 * `structuredClone` で値を複製するのもJS版の`state()`と同じ理由:
 * 呼び出し側が受け取ったオブジェクトを書き換えても、ストレージ内部の値が汚染されないようにするため
 * （`inspect()` が返す `Map` はテストから直接読むことがある）。
 */
object DurableTestKit {

  private def clone(v: js.Any): js.Any =
    if v == null || js.isUndefined(v) then v
    else js.Dynamic.global.structuredClone(v).asInstanceOf[js.Any]

  private def fakeStorage(): DurableObjectStorage = {
    // js.Map[K, V] gets `.contains`/`.get`/`.update`/`.subtractOne` etc. via the automatic
    // `js.Any.wrapMap` implicit conversion to `js.WrappedMap` (found through the `js.Any`
    // companion, which is in implicit scope for every js-facing type) — no raw `.has`/`.set`.
    val data = js.Map[String, js.Any]()
    val obj = js.Dynamic.literal(
      get = ((key: js.Any) =>
        if js.Array.isArray(key) then {
          val keys = key.asInstanceOf[js.Array[String]]
          val result = js.Map[String, js.Any]()
          keys.foreach(k => data.get(k).foreach(v => result.update(k, clone(v))))
          js.Promise.resolve[js.Any](result)
        } else {
          val k = key.asInstanceOf[String]
          js.Promise.resolve[js.Any](data.get(k).map(clone).getOrElse(js.undefined.asInstanceOf[js.Any]))
        }
      ): js.Function1[js.Any, js.Promise[js.Any]],
      put = ((key: js.Any, value: js.UndefOr[js.Any]) => {
        if value.isDefined then data.update(key.asInstanceOf[String], clone(value.get))
        else key.asInstanceOf[js.Dictionary[js.Any]].foreach((k, v) => data.update(k, clone(v)))
        js.Promise.resolve[js.Any](js.undefined)
      }): js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]],
      delete = ((key: js.Any) => {
        val k = key.asInstanceOf[String]
        val existed = data.contains(k)
        data.subtractOne(k)
        js.Promise.resolve[js.Any](existed)
      }): js.Function1[js.Any, js.Promise[js.Any]]
    )
    obj.asInstanceOf[DurableObjectStorage]
  }

  private def fakeState(): DurableObjectState = {
    val storage = fakeStorage()
    var tail: Future[Unit] = Future.successful(())
    val obj = js.Dynamic.literal(
      storage = storage.asInstanceOf[js.Any],
      blockConcurrencyWhile = ((callback: js.Function0[js.Promise[js.Any]]) => {
        val resultFuture: Future[js.Any] = tail.flatMap(_ => callback().toFuture)
        // JS版と同じ割り切り: 前段が失敗しても以降の呼び出しはブロックし続けない。
        tail = resultFuture.transformWith(_ => Future.successful(()))
        resultFuture.toJSPromise
      }): js.Function1[js.Function0[js.Promise[js.Any]], js.Promise[js.Any]]
    )
    obj.asInstanceOf[DurableObjectState]
  }

  /** 1テストケース分の Thread/Board/R2 を束ねる環境。 */
  final class Env {
    private val threadInstances = mutable.Map.empty[String, Thread]
    private val boardInstances = mutable.Map.empty[String, Board]
    private val r2 = mutable.Map.empty[String, String]

    private def namespace(getOrCreate: String => js.Object): js.Dynamic =
      js.Dynamic.literal(
        idFromName = ((name: String) => name): js.Function1[String, js.Any],
        get = ((id: js.Any) => getOrCreate(id.asInstanceOf[String])): js.Function1[js.Any, js.Any]
      )

    val worker: WorkerEnv = {
      val e = js.Dynamic.literal(
        ch3www = js.Dynamic.literal(
          get = ((key: String) =>
            js.Promise.resolve[js.Any](r2.get(key) match {
              case Some(text) => js.Dynamic.literal(text = (() => js.Promise.resolve[js.Any](text)): js.Function0[js.Promise[js.Any]])
              case None => null.asInstanceOf[js.Any]
            }
            )
          ): js.Function1[String, js.Promise[js.Any]]
        )
      )
      e.updateDynamic("THREAD_DO")(namespace(name => threadInstances.getOrElseUpdate(name, new Thread(fakeState(), e.asInstanceOf[WorkerEnv]))))
      e.updateDynamic("BOARD_DO")(namespace(name => boardInstances.getOrElseUpdate(name, new Board(fakeState(), e.asInstanceOf[js.Any]))))
      e.asInstanceOf[WorkerEnv]
    }

    /** R2 (`ch3www`) に旧形式スレッドログを仕込む。キーは `bbs/thread/head4/key.cgi` の形。 */
    def putR2(key: String, text: String): Unit = r2.put(key, text)

    def thread(name: String): ThreadDoStub = worker.THREAD_DO.get(worker.THREAD_DO.idFromName(name)).asInstanceOf[ThreadDoStub]
    def board(name: String): BoardDoStub = worker.BOARD_DO.get(worker.BOARD_DO.idFromName(name)).asInstanceOf[BoardDoStub]

    /** 直接 storage を触りたいテスト用に、生の `Thread` インスタンス（`state`フィールド公開）を返す。 */
    def threadInstance(name: String): Thread = threadInstances.getOrElseUpdate(name, new Thread(fakeState(), worker))
  }

  def newEnv(): Env = new Env()

  /** `js.JavaScriptException(new js.Error(msg))` からメッセージ文字列を取り出す（Posting.scala の errorText と同じ発想）。 */
  def messageOf(e: Throwable): String = e match {
    case x: js.JavaScriptException => x.exception.asInstanceOf[js.Dynamic].message.asInstanceOf[String]
    case other => other.getMessage
  }

  /** node:assert の `assert.rejects` 相当。成功時は失敗させ、失敗時はそのメッセージを返す。 */
  def rejectionMessage(fut: Future[Any]): Future[String] =
    fut.transform {
      case scala.util.Failure(e) => scala.util.Success(messageOf(e))
      case scala.util.Success(v) => scala.util.Failure(new AssertionError(s"expected the future to fail, but it resolved with: $v"))
    }

  /** 生の `Thread` インスタンスへ直接 storage を書き込む（JS版 `source.state.storage.put({...})` 相当）。 */
  def seed(t: Thread, entries: (String, js.Any)*): Future[Unit] =
    t.state.storage.put(js.Dictionary(entries*)).toFuture
}
