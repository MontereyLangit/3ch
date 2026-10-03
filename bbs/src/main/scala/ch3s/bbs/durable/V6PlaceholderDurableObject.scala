package ch3s.bbs.durable

import ch3s.bbs.external.cloudflare.{DurableObject, DurableObjectState}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
// TODO: これ多分不要なので削除で
/** ビルド骨格の動作確認専用 DO（BBS ロジックなし） */
@JSExportTopLevel("V6PlaceholderDurableObject", "server")
class V6PlaceholderDurableObject(ctx: DurableObjectState, env: js.Any) extends DurableObject(ctx, env) {

  private var loaded: Boolean = false
  private var count: Int = 0

  /** 初回読み込みを `blockConcurrencyWhile` で排他化する */
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
}
