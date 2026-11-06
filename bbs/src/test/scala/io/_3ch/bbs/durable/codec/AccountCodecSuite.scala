package io._3ch.bbs.durable.codec

import io._3ch.common.domain.account.*
import munit.FunSuite
import scala.scalajs.js

class AccountCodecSuite extends FunSuite {
  private val now = 2000000000d
  private val profile = AccountProfile("abcdefghijklmnop", "user1234", AccountKind.Client, now, None, now, "v1")
  private val state = AccountState.create(profile, now).toOption.get.issueToken("token1", "a" * 64, now).toOption.get
  private val credential = PasswordCredential(PasswordAlgorithm.Pbkdf2Sha256, 100000, Vector.tabulate(16)(i => (i * 17).toByte), Vector.fill(32)(2.toByte), now)
  private val prepared = AccountState.create(profile.copy(kind = AccountKind.Password, loginId = Some(profile.uid)), now).toOption.get

  private def stored(state: AccountState): js.Map[String, js.Any] = {
    val values = js.Map[String, js.Any]()
    AccountCodec.encodeState(state).foreach { case (key, value) => values.update(key, value) }
    values
  }

  test("アカウントの保存項目は型と任意値を保って往復する") {
    val values = js.Map[String, js.Any]()
    AccountCodec.encodeState(state).foreach { case (key, value) => values.update(key, value) }
    assertEquals(AccountCodec.decodeState(values), Some(state))
    assertEquals(AccountCodec.decodeState(js.Map[String, js.Any]()), None)
  }

  test("本人向けの一覧と認証結果にキー照合用ハッシュを含めない") {
    val token = state.tokens.head.copy(uaHash = Some("b" * 64), uaLabel = Some("専ブラ"), boundAt = Some(now))
    val display = AccountCodec.inspect(state.copy(tokens = Vector(token)), Vector.empty, Vector.empty, now)
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

  test("以前のクライアント保存値だけは準備状態のキーが無くても利用できる") {
    val legacy = stored(state)
    legacy.subtractOne("activation")
    legacy.subtractOne("credential")
    val restored = AccountCodec.decodeState(legacy).get
    assertEquals(restored, state)
    assert(restored.usable(now).isRight)
    assertEquals(AccountCodec.encodeState(restored)("credential"), null)
    assertEquals(AccountCodec.stateKeys.toSet, Set("profile", "level", "roles", "tokens", "devices", "caps", "activation", "credential",
      "pending:email", "pending:reset"))
    legacy.update("activation", null)
    intercept[Throwable](AccountCodec.decodeState(legacy))
  }

  test("パスワード方式は準備中と資格情報の有無を別々に保存する") {
    Seq(prepared, prepared.copy(credential = Some(credential))).foreach { state =>
      val restored = AccountCodec.decodeState(stored(state)).get
      assertEquals(restored, state)
      assertEquals(restored.describe(profile.aid, 0, now), Left(AccountRejection.Pending))
    }
    val ready = prepared.copy(credential = Some(credential), readiness = AccountReadiness.Ready)
    val restored = AccountCodec.decodeState(stored(ready)).get
    assertEquals(restored, ready)
    assert(restored.describe(profile.aid, 0, now).isRight)
  }

  test("クライアント以外は準備状態を必須とし不明な値を利用可能に補正しない") {
    val google = AccountState.create(profile.copy(kind = AccountKind.Google), now).toOption.get
    Seq(prepared, google).foreach { state =>
      Seq[js.Any](js.undefined, null, "unknown", true).foreach { activation =>
        val fields = stored(state)
        fields.update("activation", activation)
        intercept[Throwable](AccountCodec.decodeState(fields))
      }
      val missing = stored(state)
      missing.subtractOne("activation")
      intercept[Throwable](AccountCodec.decodeState(missing))
    }
    assertEquals(AccountCodec.decodeState(stored(google)), Some(google))
  }

  test("利用可能なパスワード方式の資格情報欠損とログイン ID の不一致を拒否する") {
    val ready = prepared.copy(credential = Some(credential), readiness = AccountReadiness.Ready)
    Seq[js.Any](js.undefined, null, js.Dynamic.literal()).foreach { missing =>
      val fields = stored(ready)
      fields.update("credential", missing)
      intercept[Throwable](AccountCodec.decodeState(fields))
    }
    val missing = stored(ready)
    missing.subtractOne("credential")
    intercept[Throwable](AccountCodec.decodeState(missing))
    Seq[js.Any](js.undefined, "otherid", "USER1234").foreach { loginId =>
      val fields = stored(ready)
      fields("profile").asInstanceOf[js.Dynamic].loginId = loginId
      intercept[Throwable](AccountCodec.decodeState(fields))
    }
    val clientCredential = stored(state)
    clientCredential.update("credential", PasswordCredentialCodec.encode(credential))
    intercept[Throwable](AccountCodec.decodeState(clientCredential))
  }

  test("本人向け一覧と投稿認証の DTO に資格情報と準備状態を含めない") {
    val ready = prepared.copy(credential = Some(credential), readiness = AccountReadiness.Ready)
    val payloads = Seq(AccountCodec.inspect(ready, Vector.empty, Vector.empty, now), AccountCodec.description(ready.description),
      AccountCodec.describe(ready.describe(profile.aid, 0, now)))
    payloads.foreach { payload =>
      val serialized = js.JSON.stringify(payload)
      Seq("credential", "activation", "salt", "hash", "throttle", "00112233445566778899aabbccddeeff", "02" * 32).foreach { secret =>
        assert(!serialized.contains(secret), secret)
      }
    }
  }
}
