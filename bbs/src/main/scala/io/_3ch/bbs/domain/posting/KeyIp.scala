package io._3ch.bbs.domain.posting

/** スレ主判定の IP キー。IPv4 はそのまま、IPv6 は展開した先頭 4 ブロック（/64）を小文字 16 進で返す。 */
object KeyIp {
  def of(ip: String): String =
    if (!ip.contains(":")) ip
    else expand(ip.toLowerCase).fold(ip.toLowerCase)(_.take(4).map(Integer.toHexString).mkString(":"))

  private def groups(text: String): Option[List[Int]] =
    if (text.isEmpty) Some(Nil)
    else {
      val parts = text.split(":", -1).toList
      if (parts.forall(_.matches("[0-9a-f]{1,4}"))) Some(parts.map(Integer.parseInt(_, 16))) else None
    }

  private def expand(ip: String): Option[List[Int]] = ip.split("::", -1) match {
    case Array(all) => groups(all).filter(_.length == 8)
    case Array(head, tail) =>
      for {
        h <- groups(head)
        t <- groups(tail)
        fill = 8 - h.length - t.length
        if fill >= 1
      } yield h ++ List.fill(fill)(0) ++ t
    case _ => None
  }
}
