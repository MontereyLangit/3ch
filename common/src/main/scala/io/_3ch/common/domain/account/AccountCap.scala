package io._3ch.common.domain.account

/** 所有者に結び付いたキャップ。hash は従来の KV 照合値をそのまま持つ */
final case class AccountCap(capId: String, name: String, hash: String, strong: Boolean, issuedBy: String, issuedAt: Double, revokedAt: Option[Double] = None) {
  def valid: Boolean = DedicatedBrowserTokens.validId(capId) && name.nonEmpty && hash.nonEmpty &&
    issuedBy.matches("[a-z0-9]{16}") && AccountProfile.validTime(issuedAt) &&
    revokedAt.forall(t => AccountProfile.validTime(t) && t >= issuedAt)
}
