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
trait DurableObjectStorage extends js.Object:
  def get[T](key: String): js.Promise[js.UndefOr[T]] = js.native
  def put[T](key: String, value: T): js.Promise[Unit] = js.native
  def delete(key: String): js.Promise[Boolean] = js.native

@js.native
trait DurableObjectId extends js.Object:
  val name: js.UndefOr[String] = js.native

@js.native
trait DurableObjectState extends js.Object:
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

@js.native
trait DurableObjectStub extends js.Object

@js.native
trait DurableObjectNamespace extends js.Object:
  def idFromName(name: String): DurableObjectId = js.native
  def get(id: DurableObjectId): DurableObjectStub = js.native

/**
 * `V6PlaceholderDurableObject` の型付きRPCスタブ（参照実装の `ThreadDoStub`/`BoardDoStub` と
 * 同じパターン: DO毎に必要なメンバだけを宣言するサブトレイト）。`/_v6/health` が
 * `blockConcurrencyWhile` 経由のRPCを実際に呼べることを証明するために使う。
 */
@js.native
trait V6PlaceholderDoStub extends DurableObjectStub:
  def ping(): js.Promise[String] = js.native

/**
 * R2 バケット facade（追加分）。実バインディング名は既存 `wrangler.jsonc`（v5）の
 * `r2_buckets` 参照（`ch3www`）。値そのもの（`SETTING.TXT` 等の実データ）はまだ扱わないため、
 * 次の投稿パイプライン移植で使う分だけを最小限宣言する。
 */
@js.native
trait R2ObjectBody extends js.Object:
  def text(): js.Promise[String] = js.native
  def arrayBuffer(): js.Promise[js.typedarray.ArrayBuffer] = js.native

@js.native
trait R2Bucket extends js.Object:
  def get(key: String): js.Promise[js.UndefOr[R2ObjectBody]] = js.native
  def put(key: String, value: js.Any): js.Promise[js.Any] = js.native

/**
 * KV Namespace facade（追加分）。実バインディング名は既存 `wrangler.jsonc`（v5）の
 * `kv_namespaces` 参照（`CAP`）。
 */
@js.native
trait KVNamespace extends js.Object:
  def get(key: String): js.Promise[js.UndefOr[String]] = js.native
  def put(key: String, value: String): js.Promise[Unit] = js.native

/**
 * Workers バインディング一式。`env.THREAD_DO` 等へ型付きで触るためのキャスト先。
 * `V6_PLACEHOLDER_DO` は本骨格でのみ使う検証用DO（次タスクで `THREAD_DO`/`BOARD_DO` に置き換わる）。
 */
@js.native
trait WorkerEnv extends js.Object:
  val V6_PLACEHOLDER_DO: DurableObjectNamespace = js.native
  val ch3www: R2Bucket = js.native
  val CAP: KVNamespace = js.native
