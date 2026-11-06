package io._3ch.common.domain.account

/** 保存したパスワードハッシュの計算方式 */
enum PasswordAlgorithm(val code: String) {
  case Pbkdf2Sha256 extends PasswordAlgorithm("pbkdf2-sha256")
}

object PasswordAlgorithm {
  def parse(code: String): Option[PasswordAlgorithm] = values.find(_.code == code)
}

/** 保存するパスワード資格情報。平文を含めず、計算と照合はインフラ層で行う */
final case class PasswordCredential(algo: PasswordAlgorithm, iterations: Int, salt: Vector[Byte], hash: Vector[Byte],
  updatedAt: Double, throttle: LoginThrottle = LoginThrottle()) {
  def valid: Boolean = algo != null && iterations > 0 && iterations <= PasswordCredential.currentIterations && salt.size == 16 && hash.size == 32 &&
    AccountProfile.validTime(updatedAt) && throttle.valid

  /** 以前の反復数なら、ログイン成功時に現在の設定で計算し直す */
  def needsRehash: Boolean = iterations < PasswordCredential.currentIterations

  override def toString: String = s"PasswordCredential(${algo.code},$iterations,<redacted>)"
}

object PasswordCredential {
  val currentIterations: Int = 100000
}
