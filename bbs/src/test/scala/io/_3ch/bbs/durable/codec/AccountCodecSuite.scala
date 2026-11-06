package io._3ch.bbs.durable.codec

import io._3ch.common.domain.account.*
import munit.FunSuite
import scala.scalajs.js

class AccountCodecSuite extends FunSuite {
  private val now = 2000000000d
  private val profile = AccountProfile("abcdefghijklmnop", "user1234", AccountKind.Client, now, None, now, "v1")
  private val state = AccountState.create(profile, now).toOption.get.issueToken("token1", "a" * 64, now).toOption.get

  test("アカウントの保存項目は型と任意値を保って往復する") {
    val values = js.Map[String, js.Any]()
    AccountCodec.encodeState(state).foreach { case (key, value) => values.update(key, value) }
    assertEquals(AccountCodec.decodeState(values), Some(state))
    assertEquals(AccountCodec.decodeState(js.Map[String, js.Any]()), None)
  }

  test("本人向けの一覧と認証結果にキー照合用ハッシュを含めない") {
    val token = state.tokens.head.copy(uaHash = Some("b" * 64), uaLabel = Some("専ブラ"), boundAt = Some(now))
    val display = AccountCodec.inspect(state.copy(tokens = Vector(token)), Vector.empty, now)
    val serialized = js.JSON.stringify(display)
    assert(!serialized.contains("a" * 64))
    assert(!serialized.contains("b" * 64))
    assert(serialized.contains("専ブラ"))
    assertEquals(display.tokens.asInstanceOf[js.Array[js.Dynamic]].head.tokenId.asInstanceOf[String], "token1")
    assertEquals(js.JSON.stringify(AccountCodec.describe(Left(AccountRejection.Generation))), "{\"code\":\"auth:session:invalid\"}")
  }

  test("不正な保存済みの世代・役割・索引・計数履歴を拒否する") {
    val invalidProfile = AccountCodec.encodeProfile(state.profile)
    invalidProfile.generation = 0.5
    intercept[Throwable](AccountCodec.decodeProfile(invalidProfile))
    val fields = js.Map[String, js.Any]()
    AccountCodec.encodeState(state.copy(tokens = Vector(state.tokens.head.copy(uaHash = Some("a" * 16))))).foreach {
      case (key, value) => fields.update(key, value)
    }
    intercept[Throwable](AccountCodec.decodeState(fields))
    intercept[Throwable](AccountIndexCodec.decode(js.Dynamic.literal(aid = profile.aid, createdAt = "2000000000", state = "confirmed")))
    intercept[Throwable](AuthRateCodec.decode(js.Dynamic.literal(kind = "verify", t = now)))
    intercept[Throwable](AuthRateCodec.decode(js.Array(js.Dynamic.literal(kind = "verify", t = "2000000000"))))
  }

  test("索引の未確定値を lookup に公開せず token ID は確定時に返す") {
    val entry = AccountIndexEntry(profile.aid, now, tokenId = Some("token1"))
    assertEquals(AccountIndexCodec.decode(AccountIndexCodec.encode(entry)), Some(entry))
    assertEquals(AccountIndexCodec.lookup(Some(entry)).code.asInstanceOf[String], "auth:index:missing")
    val lookup = AccountIndexCodec.lookup(Some(entry.copy(confirmed = true)))
    assertEquals(lookup.tokenId.asInstanceOf[String], "token1")
  }

  test("保存プロフィールの状態・世代・同意・作成時刻の欠損を初期値で補わない") {
    val frozen = state.profile.copy(status = AccountStatus.Frozen, generation = 7)
    Seq("status", "generation", "agreedAt", "createdAt").foreach { field =>
      Seq[js.Any](js.undefined, null).foreach { missing =>
        val value = AccountCodec.encodeProfile(frozen)
        value.updateDynamic(field)(missing)
        intercept[Throwable](AccountCodec.decodeProfile(value))
        val fields = js.Map[String, js.Any]()
        AccountCodec.encodeState(state.copy(profile = frozen)).foreach { case (key, entry) => fields.update(key, entry) }
        fields.update("profile", value)
        intercept[Throwable](AccountCodec.decodeState(fields))
      }
    }
    assertEquals(AccountCodec.decodeProfile(AccountCodec.encodeProfile(frozen)), frozen)
  }
}
