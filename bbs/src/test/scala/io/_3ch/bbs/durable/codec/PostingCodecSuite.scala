package io._3ch.bbs.durable.codec

import io._3ch.bbs.domain.posting.{ClientIdentity, ThreadOwner, ThreadPosting}
import io._3ch.bbs.domain.posting.moderation.{PostRateCheck, PostRateHistory, PostRateIdentity, PostRateKey, PostRateLevel, PostRateLimit, PostRateRecord, PostRateStatus, ThreadCreationLimit, ThreadCreationRateLimit, ThreadCreationRequest, ThreadRecentPosts}
import io._3ch.common.domain.posting.ThreadPhoebeRequest
import munit.FunSuite
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

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
      List("bbs", "key", "title", "sage", "maxThreads", "create", "initialSettings", "creationLimit", "owner", "rateLimit", "commands"))
    assertEquals(js.JSON.stringify(encoded.creationLimit),
      """{"ip":"ip","userAgent":"ua","networkGroup":"net","title":"title","tatesugi":2,"tatesugiResetSeconds":3600,"junban":1,"junbanResetSeconds":60}""")
    assertEquals(js.JSON.stringify(encoded.owner), """{"ip":"iph","shikibetsu":"JP.x","account":""}""")
    val reply = PostingCodec.encode(ThreadPosting(Some("sample"), Some("1"), None))
    assert(js.isUndefined(reply.initialSettings) && js.isUndefined(reply.creationLimit) && js.isUndefined(reply.owner) && js.isUndefined(reply.title))
  }

  test("連投規制の材料は値が真のときだけ読み、往復する") {
    val keys = Set(PostRateKey.Ip, PostRateKey.Account)
    val check = PostRateCheck(PostRateLevel.Enforce(Some(3)), PostRateIdentity("ip", "shiki", ""), PostRateLimit.BoardSettings(18, 10, 10, 1, keys), PostRateLimit.ThreadSettings(40, 3600, keys))
    val reply = ThreadPosting(Some("sample"), Some("1"), None, rateLimit = Some(check))
    assertEquals(PostingCodec.decode(PostingCodec.encode(reply)), Some(reply))
    assertEquals(js.JSON.stringify(PostingCodec.encode(reply).rateLimit),
      """{"level":"3","ip":"ip","shiki":"shiki","aid":"","timecount":18,"timeclose":10,"samba24":10,"fr":1,"threadRentou":40,"threadRentouReset":3600,"keys":"ip,aid"}""")
    assertEquals(PostingCodec.decode(js.Dynamic.literal(rateLimit = null)), Some(ThreadPosting()))
  }

  test("連投規制の材料に fr が無い旧形式は既定の秒数で読む") {
    val legacy = js.JSON.parse("""{"level":"","ip":"ip","shiki":"shiki","aid":"","timecount":18,"timeclose":10,"samba24":10,"threadRentou":40,"threadRentouReset":3600,"keys":"ip"}""")
    assertEquals(PostRateCodec.decodeCheck(legacy).board.fr, 1)
    assertEquals(PostRateCodec.decodeCheck(legacy).level, PostRateLevel.Enforce(Some(1)), "level が空の旧形式は既定")
    val (board, enforce) = PostRateCodec.decodeBoardSettings(js.Dynamic.literal(timecount = 18, timeclose = 10, samba24 = 10, keys = "ip", enforce = true))
    assertEquals((board.fr, board.samba24, enforce), (1, 10, true))
    assertEquals(PostRateCodec.decodeBoardSettings(PostRateCodec.encodeBoardSettings(board.copy(fr = 0), enforce = false)), (board.copy(fr = 0), false))
  }

  test("広域連投規制を判定しない段階は level を 0 で渡して往復する") {
    val keys = PostRateKey.defaults
    val check = PostRateCheck(PostRateLevel.Enforce(None), PostRateIdentity("ip", "shiki", ""), PostRateLimit.BoardSettings(18, 10, 10, 5, keys), PostRateLimit.ThreadSettings(40, 3600, keys))
    val encoded = PostRateCodec.encodeCheck(check)
    assertEquals(encoded.level.asInstanceOf[String], "0")
    assertEquals(PostRateCodec.decodeCheck(encoded), check)
    assertEquals(PostRateCodec.decodeBoardSettings(PostRateCodec.encodeBoardSettings(check.boardSettings, check.enforce)), (check.board.copy(checksFr = false), true))
  }

  test("スレの直近の投稿は短縮して保存し、全長の値で保存した旧形式も短縮して読む") {
    val full = "a" * 64
    val recent = ThreadRecentPosts(Vector(PostRateLimit.Entry(2e9, PostRateIdentity(full, full, ""))))
    val encoded = PostRateCodec.encodeRecent(recent)
    assertEquals(js.JSON.stringify(encoded), """[{"t":2000000000,"ip":"aaaaaaaaaaaa","shiki":"aaaaaaaaaaaa"}]""")
    val legacy = js.JSON.parse(s"""[{"t":2000000000,"ip":"$full","shiki":"$full","aid":""}]""").asInstanceOf[js.Any]
    assertEquals(PostRateCodec.decodeRecent(legacy), PostRateCodec.decodeRecent(encoded))
  }

  test("スレの直近の投稿は上限の件数でも DO の値の上限 128 KiB に十分収まる") {
    val full = "f" * 64
    val identity = PostRateIdentity(full, full, full)
    val settings = PostRateLimit.ThreadSettings(40, 315360000, PostRateKey.defaults)
    val recent = (1 to ThreadRecentPosts.MaxPosts + 10).foldLeft(ThreadRecentPosts.empty)((acc, n) => acc.appended(PostRateLimit.Entry(1999999999.123 + n, identity), settings, 2e9))
    assertEquals(recent.posts.size, ThreadRecentPosts.MaxPosts)
    assert(js.JSON.stringify(PostRateCodec.encodeRecent(recent)).length < 100 * 1000)
  }

  test("板の連投履歴の 1 ページは全長のハッシュでも DO の値の上限 128 KiB に十分収まる") {
    val full = "f" * 64
    val records = (1 to PostRateHistory.PageSize).map { n =>
      PostRateRecord(s"${"9" * 10}:${"u" * 36}", 1999999999.123 + n, PostRateIdentity(full, full, full), PostRateStatus.Completed)
    }
    assert(js.JSON.stringify(records.map(PostRateCodec.encodeRecord).toJSArray).length < 64 * 1024)
  }

  test("スレ Lv のコマンドは往復し、0〜10 の整数でない値は読まない") {
    val reply = ThreadPosting(Some("sample"), Some("1"), None, phoebe = Some(ThreadPhoebeRequest(5, lower = true, 2)))
    assertEquals(PostingCodec.decode(PostingCodec.encode(reply)), Some(reply))
    assertEquals(js.JSON.stringify(PostingCodec.encode(reply).commands), """{"phoebe":{"level":5,"lower":true,"board":2}}""")
    Seq(js.Dynamic.literal(level = 11, lower = false, board = 0), js.Dynamic.literal(level = "3", lower = false, board = 0),
      js.Dynamic.literal(level = 3.5, lower = false, board = 0), js.Dynamic.literal(level = 3, lower = false))
      .foreach(phoebe => assertEquals(PostingCodec.decode(js.Dynamic.literal(commands = js.Dynamic.literal(phoebe = phoebe))), Some(ThreadPosting())))
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
