package io._3ch.bbs.application.auth

import io._3ch.common.domain.device.*
import munit.FunSuite
import scala.scalajs.js

class DeviceEvidenceSuite extends FunSuite {
  private val hints = ClientHints.fromHeaders("Android", "15", "Pixel 9", "arm", "?1")
  private val ua = "Mozilla/5.0 Linux Android Mobile"
  private def decode(raw: String): Either[DeviceRejection, DeviceEvidence] = DeviceEvidence.fromJson(raw, hints, ua)

  test("型へ変換し受信モデルから粒度付き18桁を作る") {
    val raw = s"""{"os":"$ua","screen":"393x852","dpr":3,"cores":8,"touch":true,"arch":"Linux armv8l","model":"forged","hints":{"model":"forged"}}"""
    val evidence = decode(raw).toOption.get
    val fp = ClientFingerprint(os = ua, screen = "393x852", dpr = 3, cores = 8, touch = true, arch = "Linux armv8l")
    assertEquals(evidence.fingerprint, Some(fp))
    assertEquals(evidence.hints, hints)
    assertEquals(evidence.deviceHash, "p" + DeviceFingerprintHasher.hash(fp, "Pixel 9"))
    assertEquals(evidence.deviceIdentifier.length, 18)
    assert(!evidence.deviceHash.contains(":"))
  }

  test("欠損の既定値は原本通りで全欠損は値を作らない") {
    assertEquals(decode("{}").toOption.get.fingerprint, Some(ClientFingerprint()))
    assertEquals(decode("{}").toOption.get.deviceHash, "")
    assertEquals(decode("null").toOption.get.fingerprint, None)
    assertEquals(decode("").toOption.get.deviceHash, "")
  }

  test("型違い・範囲外・非有限値・過大な文字列を拒否する") {
    val invalid = Seq("[]", "true", "42", "invalid", "{\"cores\":1.5}", "{\"cores\":-1}", "{\"cores\":65536}",
      "{\"ram\":\"8\"}", "{\"cores\":null}", "{\"os\":null}", "{\"touch\":null}", "{\"dpr\":1e999}", "{\"dpr\":-1}", "{\"dpr\":101}", "{\"touch\":1}",
      "{\"arch\":[]}", "{\"screen\":\"393 x 852\"}", "{\"colorDepth\":129}", "{\"colorDepth\":24.5}",
      "{\"webgl\":\"" + ("a" * 4097) + "\"}", " " * 20001)
    invalid.foreach(raw => assertEquals(decode(raw), Left(DeviceRejection.Fingerprint), raw.take(70)))
    assertEquals(DeviceEvidence.decode(js.Dynamic.literal(dpr = Double.NaN), hints, ua), Left(DeviceRejection.Fingerprint))
    assertEquals(DeviceEvidence.decode(js.Dynamic.literal(cores = Double.PositiveInfinity), hints, ua), Left(DeviceRejection.Fingerprint))
  }

  test("受信UA・Client Hints・論理破綻を照合する") {
    assertEquals(decode("{\"os\":\"Windows\",\"screen\":\"1920x1080\"}"), Left(DeviceRejection.DeclarationMismatch))
    assertEquals(decode("{\"arch\":\"iPhone\"}"), Left(DeviceRejection.DeclarationMismatch))
    assertEquals(decode("{\"os\":\"Macintosh\",\"webgl\":\"NVIDIA RTX\"}"), Left(DeviceRejection.Fingerprint))
    assertEquals(DeviceEvidence.fromJson("{\"os\":\"UA\"}", ClientHints(), "ua"), Left(DeviceRejection.DeclarationMismatch))
    assert(DeviceEvidence.fromJson("{\"os\":\" UA \"}", ClientHints(), "UA").isRight)
  }

  test("拒否ログには照合した規則名だけを返す") {
    assertEquals(DeviceEvidence.declarationRuleFromJson("{\"arch\":\"iPhone\"}", hints, ua), Some("R4"))
    assertEquals(DeviceEvidence.declarationRuleFromJson("{\"os\":\"private-device-value\"}", ClientHints(), "UA"), Some("UserAgent"))
    assertEquals(DeviceEvidence.declarationRuleFromJson("{}", ClientHints(), "UA"), None)
    assertEquals(DeviceEvidence.declarationRuleFromJson("invalid", hints, ua), None)
  }

  test("既知プロファイルの表だけを読み照合する") {
    val profiles = DeviceEvidence.profilesFromJson("""[{"screen":"393x852","dpr":3,"minIosMajor":16}]""")
    assertEquals(profiles, Vector(IosDeviceProfile("393x852", 3, 16)))
    val ua = "iPhone; CPU iPhone OS 15_0 like Mac OS X"
    assertEquals(DeviceEvidence.fromJson(s"""{"os":"$ua","screen":"393x852","dpr":3}""", ClientHints(), ua, profiles),
      Left(DeviceRejection.Fingerprint))
    Seq("not JSON", "{}", "[{\"screen\":\"393x852\",\"dpr\":0,\"minIosMajor\":16}]", "[{\"screen\":\"393x852\",\"dpr\":3}]")
      .foreach(raw => assertEquals(DeviceEvidence.profilesFromJson(raw), Vector.empty))
  }
}
