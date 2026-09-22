package ch3s.bbs.durable

import ch3s.bbs.external.cloudflare.{DurableObject, DurableObjectState}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * v6s ビルド骨格の動作確認専用DO（docs/移植方針_step3_ScalaJS.md のスコープ外＝BBSロジックは
 * 一切持たない）。このクラスの存在意義は次の2点を実機（`wrangler dev` ローカル）で証明すること:
 *
 *   1. Scala.js で定義した `DurableObject` サブクラスが `@JSExportTopLevel(..., "server")` で
 *      正しく `scalajs:server.js` から export され、`app/server.ts` の named re-export
 *      ワークアラウンド（vite.config.ts の `entryContentAfterHooks`）を経て
 *      `wrangler.jsonc` の `durable_objects.bindings` から解決できること。
 *   2. 追加したファサードメンバ `DurableObjectState.blockConcurrencyWhile`
 *      （参照実装のファサードには存在しない。docs/移植方針_step3_ScalaJS.md 「2.」参照）が
 *      実際に排他制御として機能すること。次タスクの `Thread.addRes` 移植はこれに依存する。
 *
 * `state()` の呼び出し名（Scala側のメソッド名）がそのままRPCの呼び出し名になる（参照実装 v6-s/
 * ch3s-migration で実測済み: `DurableObject` を `extends` したScala.js-definedクラスは、
 * 公開メソッドが `@JSExport` 無しで素のJS名としてexportされる）。
 */
@JSExportTopLevel("V6PlaceholderDurableObject", "server")
class V6PlaceholderDurableObject(ctx: DurableObjectState, env: js.Any) extends DurableObject(ctx, env):

  private var loaded: Boolean = false
  private var count: Int = 0

  /**
   * `blockConcurrencyWhile` で読み込みを排他化する。素朴な `isLoaded` フラグ（参照実装の代用実装）
   * だけだと、`storage.get` の await 中に別の呼び出しが割り込んで `count` の初期値を2回読む
   * 競合が起こり得る。`blockConcurrencyWhile` はコールバック完了までこのDOインスタンスへの
   * 他の呼び出しをブロックするため、その競合が起きないことを保証する。
   */
  private def ensureLoaded(): js.Promise[Unit] =
    ctx.blockConcurrencyWhile[Unit] { () =>
      if loaded then js.Promise.resolve[Unit](())
      else
        ctx.storage.get[Double]("count").`then` { stored =>
          count = stored.toOption.map(_.toInt).getOrElse(0)
          loaded = true
        }
    }

  /** 疎通確認用。DOが生成・呼び出しできることだけを確かめる。 */
  def ping(): js.Promise[String] =
    ensureLoaded().`then`(_ => "pong")

  /** `blockConcurrencyWhile` 経由の読み込み → 更新 → 永続化が一通り動くことを確かめる。 */
  def increment(): js.Promise[Int] =
    ensureLoaded().`then` { _ =>
      count += 1
      val newCount = count
      ctx.storage.put("count", newCount.toDouble).`then`(_ => newCount)
    }
