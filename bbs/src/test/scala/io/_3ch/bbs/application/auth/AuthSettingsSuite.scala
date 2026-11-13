package io._3ch.bbs.application.auth

class AuthSettingsSuite extends munit.FunSuite {
  private val secret = "s" * AuthSettings.minimumSecretLength

  test("秘密鍵・同意の版・Turnstile の鍵が揃ったときだけ有効にする") {
    assertEquals(AuthSettings.of(secret, "v1", "site", "server"), Some(AuthSettings(secret, "v1", "site", "server")))
    assertEquals(AuthSettings.of(secret, "", "site", "server"), None)
    assertEquals(AuthSettings.of(secret, "v1", "", "server"), None)
    assertEquals(AuthSettings.of(secret, "v1", "site", ""), None)
    assertEquals(AuthSettings.of("", "v1", "site", "server"), None)
  }
  test("短すぎる秘密は設定なしと同じに扱う") {
    assertEquals(AuthSettings.of(secret.drop(1), "v1", "site", "server"), None)
  }
}
