package ch3s.bbs.external.cloudflare

import ch3s.bbs.external.web.{Request, Response}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Cloudflare Workers / DO / R2 / KV の手書きfacade */
@js.native
@JSImport("cloudflare:workers", "DurableObject")
class DurableObject(ctx: js.Any, env: js.Any) extends js.Object

@js.native
trait DurableObjectStorage extends js.Object {
  def get[T](key: String): js.Promise[js.UndefOr[T]] = js.native
  def put[T](key: String, value: T): js.Promise[Unit] = js.native
  def delete(key: String): js.Promise[Boolean] = js.native

  /** 複数キー版 */
  def get(keys: js.Array[String]): js.Promise[js.Map[String, js.Any]] = js.native
  def put(entries: js.Dictionary[js.Any]): js.Promise[Unit] = js.native
}

@js.native
trait DurableObjectId extends js.Object {
  val name: js.UndefOr[String] = js.native
}

@js.native
trait DurableObjectState extends js.Object {
  val id: DurableObjectId = js.native
  val storage: DurableObjectStorage = js.native

  /** コールバック完了まで同DOへの他処理をブロックする */
  def blockConcurrencyWhile[T](callback: js.Function0[js.Promise[T]]): js.Promise[T] = js.native
}

@js.native
trait DurableObjectStub extends js.Object

@js.native
trait DurableObjectNamespace extends js.Object {
  def idFromName(name: String): DurableObjectId = js.native
  def get(id: DurableObjectId): DurableObjectStub = js.native
}

/** スレDO の型付きRPCスタブ */
@js.native
trait ThreadDoStub extends DurableObjectStub {
  def getDat(bbs: js.UndefOr[String] = js.native, key: js.UndefOr[String] = js.native): js.Promise[String] = js.native
  def getLog(): js.Promise[String] = js.native
  def getSettings(): js.Promise[String] = js.native
  def setSettings(newSettings: String): js.Promise[Unit] = js.native
  def addRes(datLine: String, logLine: String, posting: js.UndefOr[js.Dynamic] = js.native): js.Promise[Int] = js.native
  def inspect(bbs: js.UndefOr[String] = js.native, key: js.UndefOr[String] = js.native): js.Promise[ThreadInspectResult] = js.native
  def moderate(action: String, input: js.Dynamic): js.Promise[Unit] = js.native
  def receiveMove(data: js.Dynamic, source: String): js.Promise[Unit] = js.native
  def publishMove(source: String, bbs: String, key: String): js.Promise[Unit] = js.native
  def setDat(dat: String): js.Promise[Unit] = js.native
}

/**
 * `Thread.inspect` / `receiveMove` の管理画面向け一括状態
 * 実体は `Object.fromEntries(map)` で
 * 組み立てたプレーンオブジェクトなので、キーが存在しないフィールドは（`undefined` ではなく）
 * そもそもプロパティ自体が無い。`js.UndefOr` はどちらの場合も未定義として扱えるのでこれで足りる。
 */
@js.native
trait ThreadInspectResult extends js.Object {
  val dat: js.UndefOr[String] = js.native
  val log: js.UndefOr[String] = js.native
  val legacyLog: js.UndefOr[String] = js.native
  val settings: js.UndefOr[String] = js.native
  val hidden: js.UndefOr[Boolean] = js.native
  val stopped: js.UndefOr[Boolean] = js.native
  val movedTo: js.UndefOr[String] = js.native
  val movingTo: js.UndefOr[String] = js.native
  val importedFrom: js.UndefOr[String] = js.native
}

/**
 * 板DOの型付きRPCスタブ
 * `updateThread` の `threadData` は `{key, title?, resNum}` という
 * ゆるい形（`title` 省略時は既存行から抽出する `??` フォールバックがJS側にある）なので、
 * 素直に `js.Dynamic` で渡す
 */
@js.native
trait BoardDoStub extends DurableObjectStub {
  def getSubject(): js.Promise[String] = js.native
  def getRemoved(): js.Promise[js.Array[String]] = js.native
  def removeThread(key: String): js.Promise[Unit] = js.native
  def updateThread(threadData: js.Dynamic, isSage: js.UndefOr[Boolean] = js.native, maxThreads: js.UndefOr[Double] = js.native): js.Promise[Unit] = js.native
}

/** 
 * R2 オブジェクト
 * 未存在キーは `null` なので `js.UndefOr` にしないこと
 */
@js.native
trait R2ObjectBody extends js.Object {
  def text(): js.Promise[String] = js.native
  def arrayBuffer(): js.Promise[js.typedarray.ArrayBuffer] = js.native
}

@js.native
trait R2Bucket extends js.Object {
  def get(key: String): js.Promise[R2ObjectBody] = js.native
  def put(key: String, value: js.Any): js.Promise[js.Any] = js.native
}

/**
 * KV Namespace facade（追加分）。実バインディング名は`wrangler.jsonc` の
 * `kv_namespaces` 参照（`CAP`）。
 */
@js.native
trait KVNamespace extends js.Object {
  def get(key: String): js.Promise[js.UndefOr[String]] = js.native
  def put(key: String, value: String): js.Promise[Unit] = js.native
}

/** Workers Static Assets バインディング（`env.ASSETS`）。3chiko.html配信に使う（PublicRoutes参照）。 */
@js.native
trait Fetcher extends js.Object {
  def fetch(request: Request): js.Promise[Response] = js.native
}

/** Workers バインディング一式。`env.THREAD_DO` 等へ型付きで触るためのキャスト先。 */
@js.native
trait WorkerEnv extends js.Object {
  /** v5 `thread.js` / `board.js` 移植分のバインディング（`wrangler.jsonc` 参照）。 */
  val THREAD_DO: DurableObjectNamespace = js.native
  val BOARD_DO: DurableObjectNamespace = js.native
  val ch3www: R2Bucket = js.native
  val CAP: KVNamespace = js.native
  val ASSETS: Fetcher = js.native
  /** 板topの画像アップロード用 imgur Client-ID。未設定ならアップロード欄を出さない。 */
  val IMGUR_CLIENT_ID: js.UndefOr[String] = js.native
}
