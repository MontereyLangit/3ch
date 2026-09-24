package ch3s.bbs.external.cloudflare

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/**
 * Cloudflare Workers / Durable Object / R2 / KV の手書きfacade（step3方針: ScalablyTypedは使わない。
 * 必要なメンバだけを宣言する）。参照実装 ch3s-migration の
 * `bbs/src/main/scala/ch3s/bbs/external/cloudflare/Bindings.scala` をベースに、
 * 参照実装には無い次の2点を追加した（docs/移植方針_step3_ScalaJS.md 「2. 参照実装に無く、
 * 今回作る必要があるもの」）。
 *   - `blockConcurrencyWhile`（v5 `Thread.addRes` が前提とする排他制御。参照実装は
 *     `isLoaded` フラグで代用していたが、それでは同時実行時の競合を防げない）
 *   - R2 (`ch3www`) / KV (`CAP`) バインディングの facade
 */
@js.native
@JSImport("cloudflare:workers", "DurableObject")
class DurableObject(ctx: js.Any, env: js.Any) extends js.Object

@js.native
trait DurableObjectStorage extends js.Object {
  def get[T](key: String): js.Promise[js.UndefOr[T]] = js.native
  def put[T](key: String, value: T): js.Promise[Unit] = js.native
  def delete(key: String): js.Promise[Boolean] = js.native

  /**
   * 追加分（Thread/Board DO移植で必要になった一括版）。実Cloudflare Storage APIは
   * `get(keys[])` → `Map<string, any>`（存在しないキーはMapに含まれない）、
   * `put(entries)` → 複数キーの単一トランザクション書き込み、を単体キー版と同じメソッド名の
   * オーバーロードとして提供する。`thread.js` の `addRes`（`storage.put({dat, log, modifiedAt, ...})`）や
   * `inspect`（`storage.get(['dat','log',...])`）はこの複数キー形を前提にしている。
   */
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

  /**
   * 追加分（参照実装のDOファサードには無い）。v5 `Thread.addRes` は複数の投稿が同時に
   * 到着した場合でも、直前の読み込み〜書き込みが1つの排他区間として実行されることを前提にしている
   * （`isLoaded` フラグだけでは、await中に別の呼び出しが割り込むと壊れる）。
   * Workers runtime 組み込みの `blockConcurrencyWhile` は、渡したコールバックが完了するまで
   * 同じDOインスタンスへの他のリクエスト処理をブロックする（Cloudflareの排他保証そのもの）ため、
   * 次の投稿パイプライン移植ではこちらを使う。
   */
  def blockConcurrencyWhile[T](callback: js.Function0[js.Promise[T]]): js.Promise[T] = js.native
}

@js.native
trait DurableObjectStub extends js.Object

@js.native
trait DurableObjectNamespace extends js.Object {
  def idFromName(name: String): DurableObjectId = js.native
  def get(id: DurableObjectId): DurableObjectStub = js.native
}

/**
 * `V6PlaceholderDurableObject` の型付きRPCスタブ（参照実装の `ThreadDoStub`/`BoardDoStub` と
 * 同じパターン: DO毎に必要なメンバだけを宣言するサブトレイト）。`/_v6/health` が
 * `blockConcurrencyWhile` 経由のRPCを実際に呼べることを証明するために使う。
 */
@js.native
trait V6PlaceholderDoStub extends DurableObjectStub {
  def ping(): js.Promise[String] = js.native
}

/**
 * `Thread` DOの型付きRPCスタブ。`thread.js` 自身が兄弟スレッド（旧デュアルDO移行経路。
 * `getDat`/`getLog`/`getSettings` はいずれもゼロ引数呼び出しがあるため `js.UndefOr` 既定値にした）や
 * 移動先スレッドを `THREAD_DO` 越しに叩く箇所で使う。戻り値 `inspect` はプレーンオブジェクト
 * （`Object.fromEntries` 相当）なので `ThreadInspectResult` で受ける。
 */
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
 * `Thread.inspect` / `receiveMove` の管理画面向け一括状態。実体は `Object.fromEntries(map)` で
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
 * `Board` DOの型付きRPCスタブ。`updateThread` の `threadData` は `{key, title?, resNum}` という
 * ゆるい形（`title` 省略時は既存行から抽出する `??` フォールバックがJS側にある）なので、
 * 素直に `js.Dynamic` で渡す。
 */
@js.native
trait BoardDoStub extends DurableObjectStub {
  def getSubject(): js.Promise[String] = js.native
  def getRemoved(): js.Promise[js.Array[String]] = js.native
  def removeThread(key: String): js.Promise[Unit] = js.native
  def updateThread(threadData: js.Dynamic, isSage: js.UndefOr[Boolean] = js.native, maxThreads: js.UndefOr[Double] = js.native): js.Promise[Unit] = js.native
}

/**
 * R2 バケット facade（追加分）。実バインディング名は既存 `wrangler.jsonc`（v5）の
 * `r2_buckets` 参照（`ch3www`）。値そのもの（`SETTING.TXT` 等の実データ）はまだ扱わないため、
 * 次の投稿パイプライン移植で使う分だけを最小限宣言する。
 *
 * `get` の戻り値は意図的に `js.UndefOr` にしていない: 実際のCloudflare R2は未存在キーに対して
 * `null` を返す（`undefined` ではない）。`thread.js` の旧形式移行ロジックは `object !== null` で
 * 判定しており、`js.UndefOr`（`undefined` の有無だけを見る）で受けると `null` を「値あり」と
 * 誤認してしまう。そのため生の（nullを取りうる）`R2ObjectBody` として宣言し、呼び出し側で
 * `!= null` 比較する。
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
 * KV Namespace facade（追加分）。実バインディング名は既存 `wrangler.jsonc`（v5）の
 * `kv_namespaces` 参照（`CAP`）。
 */
@js.native
trait KVNamespace extends js.Object {
  def get(key: String): js.Promise[js.UndefOr[String]] = js.native
  def put(key: String, value: String): js.Promise[Unit] = js.native
}

/**
 * Workers バインディング一式。`env.THREAD_DO` 等へ型付きで触るためのキャスト先。
 * `V6_PLACEHOLDER_DO` は本骨格でのみ使う検証用DO（次タスクで `THREAD_DO`/`BOARD_DO` に置き換わる）。
 */
@js.native
trait WorkerEnv extends js.Object {
  val V6_PLACEHOLDER_DO: DurableObjectNamespace = js.native
  /** `src/durable_objects/thread.js` / `board.js` 移植分のバインディング（`wrangler.v6.jsonc` 参照）。 */
  val THREAD_DO: DurableObjectNamespace = js.native
  val BOARD_DO: DurableObjectNamespace = js.native
  val ch3www: R2Bucket = js.native
  val CAP: KVNamespace = js.native
}
