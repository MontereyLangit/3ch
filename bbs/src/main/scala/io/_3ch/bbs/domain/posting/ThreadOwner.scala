package io._3ch.bbs.domain.posting

/** スレ立て人の照合値。`ip`・`account` は SHA-256 済み、`shikibetsu` は識別子そのもの。空の項目は照合に使わない */
final case class ThreadOwner(ip: String, shikibetsu: String, account: String) {
  /** 空でない項目が 1 つでも `poster` と一致するか */
  def matches(poster: ThreadOwner): Boolean =
    same(ip, poster.ip) || same(shikibetsu, poster.shikibetsu) || same(account, poster.account)

  /** アカウントの照合値が一致するか。スレ立て人だけに許すコマンドに使い、IP と識別子は見ない */
  def ownedBy(accountHash: String): Boolean = same(account, accountHash)

  private def same(a: String, b: String): Boolean = a.nonEmpty && a == b
}
