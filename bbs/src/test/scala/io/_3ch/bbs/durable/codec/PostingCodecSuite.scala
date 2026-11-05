package io._3ch.bbs.durable.codec

import io._3ch.bbs.domain.posting.{ClientIdentity, ThreadOwner, ThreadPosting}
import io._3ch.bbs.domain.posting.moderation.{ThreadCreationLimit, ThreadCreationRateLimit, ThreadCreationRequest}
import munit.FunSuite
import scala.scalajs.js

class PostingCodecSuite extends FunSuite {
  private val limit = ThreadCreationLimit(ThreadCreationRateLimit.Settings(2, 3600, 1, 60), ThreadCreationRequest(ClientIdentity("ip", "ua", "net"), "title"))
  private val owner = ThreadOwner("iph", "JP.x", "")
  private val create = ThreadPosting(Some("sample"), Some("1700000000"), Some("スレタイ"), sage = false, Some(700), create = true,
    Some("BBS_TITLE=板<>"), Some(limit), Some(owner))

  test("encode した値を decode すると元に戻る") {
    assertEquals(PostingCodec.decode(PostingCodec.encode(create)), Some(create))
    val reply = ThreadPosting(Some("sample"), Some("1700000000"), Some("スレタイ"), sage = true, Some(50d))
    assertEquals(PostingCodec.decode(PostingCodec.encode(reply)), Some(reply))
  }

  test("RPC の外形は従来のプレーンオブジェクト") {
    val encoded = PostingCodec.encode(create)
    assertEquals(js.Object.keys(encoded.asInstanceOf[js.Object]).toList,
      List("bbs", "key", "title", "sage", "maxThreads", "create", "initialSettings", "creationLimit", "owner"))
    assertEquals(js.JSON.stringify(encoded.creationLimit),
      """{"ip":"ip","userAgent":"ua","networkGroup":"net","title":"title","tatesugi":2,"tatesugiResetSeconds":3600,"junban":1,"junbanResetSeconds":60}""")
    assertEquals(js.JSON.stringify(encoded.owner), """{"ip":"iph","shikibetsu":"JP.x","account":""}""")
    val reply = PostingCodec.encode(ThreadPosting(Some("sample"), Some("1"), None))
    assert(js.isUndefined(reply.initialSettings) && js.isUndefined(reply.creationLimit) && js.isUndefined(reply.owner) && js.isUndefined(reply.title))
  }

  test("null と undefined は投稿情報なし") {
    assertEquals(PostingCodec.decode(js.undefined), None)
    assertEquals(PostingCodec.decode(null.asInstanceOf[js.Dynamic]), None)
  }

  test("旧形式: 欠けた項目は None、真偽は JavaScript の真偽判定で読む") {
    assertEquals(PostingCodec.decode(js.Dynamic.literal()), Some(ThreadPosting()))
    val legacy = js.Dynamic.literal(bbs = "board1", key = 1700000000, title = null, resNum = 1, sage = 1, maxThreads = "500", create = "yes")
    assertEquals(PostingCodec.decode(legacy), Some(ThreadPosting(Some("board1"), Some("1700000000"), None, sage = true, Some(500), create = true)))
    val falsy = js.Dynamic.literal(sage = 0, create = "", maxThreads = null)
    assertEquals(PostingCodec.decode(falsy), Some(ThreadPosting()))
  }

  test("初期スレ設定・スレ立て規制・スレ主は create が真で値が真のときだけ読む") {
    val reply = PostingCodec.encode(create).asInstanceOf[js.Dynamic]
    reply.create = false
    assertEquals(PostingCodec.decode(reply), Some(create.copy(create = false, initialSettings = None, creationLimit = None, owner = None)))
    val empty = js.Dynamic.literal(create = true, initialSettings = "", creationLimit = null, owner = 0)
    assertEquals(PostingCodec.decode(empty), Some(ThreadPosting(create = true)))
  }
}
