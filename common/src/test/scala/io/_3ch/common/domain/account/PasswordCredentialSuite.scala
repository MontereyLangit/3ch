package io._3ch.common.domain.account

import munit.FunSuite

class PasswordCredentialSuite extends FunSuite {
  private val credential = PasswordCredential(PasswordAlgorithm.Pbkdf2Sha256, 100000, Vector.fill(16)(1.toByte),
    Vector.fill(32)(2.toByte), 2000000000d)

  test("保存値は計算方式・反復数・ソルトとハッシュの長さ・更新時刻を検証する") {
    assert(credential.valid)
    assertEquals(PasswordAlgorithm.parse("pbkdf2-sha256"), Some(PasswordAlgorithm.Pbkdf2Sha256))
    assertEquals(PasswordAlgorithm.parse("unknown"), None)
    Seq(credential.copy(algo = null), credential.copy(iterations = 0), credential.copy(iterations = 100001), credential.copy(salt = Vector.empty),
      credential.copy(hash = Vector.fill(31)(0.toByte)), credential.copy(updatedAt = Double.NaN),
      credential.copy(updatedAt = 1.5), credential.copy(throttle = LoginThrottle(locks = Map("raw-ip" -> 2000000000d))))
      .foreach(value => assert(!value.valid))
  }

  test("以前の正しい反復数を読めるようにしログイン成功時の再計算を判定する") {
    assert(!credential.needsRehash)
    val old = credential.copy(iterations = 10000)
    assert(old.valid)
    assert(old.needsRehash)
    assertEquals(credential.throttle, LoginThrottle())
  }

  test("診断表示にはソルトとハッシュの内容を出さない") {
    assertEquals(credential.toString, "PasswordCredential(pbkdf2-sha256,100000,<redacted>)")
  }
}
