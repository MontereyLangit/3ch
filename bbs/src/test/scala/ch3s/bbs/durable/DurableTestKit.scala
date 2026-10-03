package ch3s.bbs.durable

import ch3s.bbs.external.cloudflare.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future
import scala.collection.mutable
import scala.scalajs.js.annotation.JSImport

/** DO / R2 / KV(CAP) / ASSETS の最小テストダブル（docs/v6s実装メモ.md 4.1）。 */
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

  @js.native
  @JSImport("node:fs", JSImport.Namespace)
  private object NodeFs extends js.Object {
    def readFileSync(path: String, encoding: String): String = js.native
    def existsSync(path: String): Boolean = js.native
  }

  @js.native
  @JSImport("node:path", JSImport.Namespace)
  private object NodePath extends js.Object {
    def join(paths: String*): String = js.native
    def resolve(paths: String*): String = js.native
  }

  /** `process.cwd()` から上へ辿って `public/3chiko.html` を持つディレクトリ（リポジトリルート）を探す。 */
  private def publicDir(): String = {
    var dir = NodePath.resolve(".")
    var depth = 0
    while (depth < 8) {
      val candidate = NodePath.join(dir, "public")
      if (NodeFs.existsSync(NodePath.join(candidate, "3chiko.html"))) return candidate
      dir = NodePath.resolve(NodePath.join(dir, ".."))
      depth += 1
    }
    throw new IllegalStateException("public/3chiko.html が見つからない")
  }

  /** 1テストケース分の Thread/Board/R2/KV(CAP)/ASSETS を束ねる環境。R2 は etag と `onlyIf.etagMatches` を再現する。 */
  final class Env {
    private val threadInstances = mutable.Map.empty[String, Thread]
    private val boardInstances = mutable.Map.empty[String, Board]
    private val r2 = mutable.Map.empty[String, (String, String)]
    private val capStore = mutable.Map.empty[String, String]
    private var etagCounter = 0
    private var r2GetHook: String => Unit = _ => ()

    private def nextEtag(): String = { etagCounter += 1; s"etag$etagCounter" }

    private def namespace(getOrCreate: String => js.Object): js.Dynamic =
      js.Dynamic.literal(
        idFromName = ((name: String) => name): js.Function1[String, js.Any],
        get = ((id: js.Any) => getOrCreate(id.asInstanceOf[String])): js.Function1[js.Any, js.Any]
      )

    private def str(v: js.Any): String = js.Dynamic.global.String(v).asInstanceOf[String]

    /** 実R2 の `put(onlyIf)` と同じく etagMatches / etagDoesNotMatch だけを評価し、未知のプロパティ（doesNotExist 等）は無視する。条件不成立は null を返す。 */
    private def r2Put(key: String, value: js.Any, opts: js.UndefOr[js.Dynamic]): js.Any = {
      val rec = r2.get(key)
      val onlyIf: js.Dynamic = opts.toOption.filter(o => !js.isUndefined(o.onlyIf)).map(_.onlyIf).getOrElse(js.Dynamic.literal())
      val matches: js.Any = onlyIf.etagMatches.asInstanceOf[js.Any]
      val doesNotMatch: js.Any = onlyIf.etagDoesNotMatch.asInstanceOf[js.Any]
      val failMatch = !js.isUndefined(matches) && (rec.isEmpty || rec.get._2 != str(matches))
      val failNotMatch = !js.isUndefined(doesNotMatch) && rec.exists(_._2 == str(doesNotMatch))
      if failMatch || failNotMatch then null.asInstanceOf[js.Any]
      else {
        val etag = nextEtag()
        r2.update(key, (str(value), etag))
        js.Dynamic.literal(uploaded = new js.Date(), etag = etag)
      }
    }

    val worker: WorkerEnv = {
      val e = js.Dynamic.literal(
        ch3www = js.Dynamic.literal(
          get = ((key: String) =>
            js.Promise.resolve[js.Any](r2.get(key) match {
              case Some((text, etag)) =>
                val body = js.Dynamic.literal(text = (() => js.Promise.resolve[js.Any](text)): js.Function0[js.Promise[js.Any]], etag = etag)
                r2GetHook(key)
                body
              case None => null.asInstanceOf[js.Any]
            }
            )
          ): js.Function1[String, js.Promise[js.Any]],
          put = ((key: String, value: js.Any, opts: js.UndefOr[js.Dynamic]) => js.Promise.resolve[js.Any](r2Put(key, value, opts))): js.Function3[String, js.Any, js.UndefOr[js.Dynamic], js.Promise[js.Any]]
        ),
        CAP = js.Dynamic.literal(
          get = ((key: String) => js.Promise.resolve[js.Any](capStore.get(key) match {
            case Some(v) => v
            case None => null.asInstanceOf[js.Any]
          })): js.Function1[String, js.Promise[js.Any]]
        ),
        ASSETS = js.Dynamic.literal(
          fetch = ((req: js.Dynamic) => {
            val pathname = js.Dynamic.newInstance(js.Dynamic.global.URL)(req.url).pathname.asInstanceOf[String]
            val file = NodePath.join(publicDir(), "." + pathname)
            val response =
              if NodeFs.existsSync(file) then js.Dynamic.newInstance(js.Dynamic.global.Response)(NodeFs.readFileSync(file, "utf8"), js.Dynamic.literal(headers = js.Dynamic.literal("content-type" -> "text/html; charset=utf-8")))
              else js.Dynamic.newInstance(js.Dynamic.global.Response)("Not Found", js.Dynamic.literal(status = 404))
            js.Promise.resolve[js.Any](response)
          }): js.Function1[js.Dynamic, js.Promise[js.Any]]
        ),
        salt = "test-salt-12345"
      )
      e.updateDynamic("THREAD_DO")(namespace(name => threadInstances.getOrElseUpdate(name, new Thread(fakeState(), e.asInstanceOf[WorkerEnv]))))
      e.updateDynamic("BOARD_DO")(namespace(name => boardInstances.getOrElseUpdate(name, new Board(fakeState(), e.asInstanceOf[js.Any]))))
      e.asInstanceOf[WorkerEnv]
    }

    /** KV (`CAP`) にキャップを登録する（キー: パスワードハッシュ、値: 表示名）。 */
    def putCap(key: String, name: String): Unit = capStore.update(key, name)

    /** R2 の現在の本文。存在しなければ None。 */
    def r2Text(key: String): Option[String] = r2.get(key).map(_._1)

    /** R2 の etag を指定して直接書き込む（他者の書き込みや競合の再現用）。 */
    def putR2Raw(key: String, text: String, etag: String): Unit = r2.update(key, (text, etag))

    /** 現在の etag を別の値に差し替える（読み取り後の競合注入用）。 */
    def replaceR2Etag(key: String, etag: String): Unit = r2.get(key).foreach { case (text, _) => r2.update(key, (text, etag)) }

    /** R2 の `get` が結果を返した直後に呼ばれるフックを差し込む。 */
    def onR2Get(hook: String => Unit): Unit = r2GetHook = hook

    /** R2 (`ch3www`) に旧形式スレッドログを仕込む。キーは `bbs/thread/head4/key.cgi` の形。 */
    def putR2(key: String, text: String): Unit = r2.update(key, (text, nextEtag()))

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
