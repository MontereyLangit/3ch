package io._3ch.bbs.durable.codec

import io._3ch.bbs.domain.posting.ThreadPosting
import io._3ch.bbs.domain.posting.moderation.PostRateFinding
import io._3ch.bbs.external.cloudflare.ThreadAddResult
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** Thread.addRes の引数 posting の変換。RPC ではプレーンオブジェクトのまま渡し、真偽は JavaScript の真偽判定で読む */
object PostingCodec {
  /** 投稿の付帯情報を RPC 引数にする */
  def encode(posting: ThreadPosting): js.Dynamic = js.Dynamic.literal(
    bbs = posting.bbs.orUndefined, key = posting.key.orUndefined, title = posting.title.orUndefined, sage = posting.sage,
    maxThreads = posting.maxThreads.orUndefined, create = posting.create, initialSettings = posting.initialSettings.orUndefined,
    creationLimit = posting.creationLimit.map(ThreadCreationCodec.encodeRequest).orUndefined,
    owner = posting.owner.map(ThreadOwnerCodec.encode).orUndefined,
    rateLimit = posting.rateLimit.map(PostRateCodec.encodeCheck).orUndefined)

  /** null と undefined は投稿情報なし。初期スレ設定・スレ立て規制・スレ主は create が真で値が真のときだけ、連投規制は値が真のときだけ読む */
  def decode(value: js.UndefOr[js.Dynamic]): Option[ThreadPosting] = {
    val raw = value.asInstanceOf[js.Any]
    if (isNullish(raw)) None
    else {
      val p = raw.asInstanceOf[js.Dynamic]
      val create = truth(p.create)
      def created(field: js.Dynamic): Option[js.Dynamic] = if (create && truth(field)) Some(field) else None
      Some(ThreadPosting(
        bbs = if (js.isUndefined(p.bbs)) None else Some(str(p.bbs)),
        key = if (js.isUndefined(p.key)) None else Some(str(p.key)),
        title = if (isNullish(p.title)) None else Some(str(p.title)),
        sage = truth(p.sage),
        maxThreads = if (isNullish(p.maxThreads)) None else Some(js.Dynamic.global.Number(p.maxThreads).asInstanceOf[Double]),
        create = create,
        initialSettings = created(p.initialSettings).map(str),
        creationLimit = created(p.creationLimit).map(ThreadCreationCodec.decodeRequest),
        owner = created(p.owner).map(ThreadOwnerCodec.decode),
        rateLimit = if (truth(p.rateLimit)) Some(PostRateCodec.decodeCheck(p.rateLimit)) else None))
    }
  }

  /** addRes の戻り値 */
  def encodeResult(count: Int, swallowed: Boolean, observed: Seq[PostRateFinding]): ThreadAddResult =
    js.Dynamic.literal(count = count, swallowed = swallowed, observed = observed.map(_.code).toJSArray).asInstanceOf[ThreadAddResult]

  private def isNullish(value: js.Any): Boolean = value == null || js.isUndefined(value)
  private def truth(value: js.Any): Boolean = js.Dynamic.global.Boolean(value).asInstanceOf[Boolean]
  private def str(value: js.Any): String = js.Dynamic.global.String(value).asInstanceOf[String]
}
