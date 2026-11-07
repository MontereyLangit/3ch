package io._3ch.bbs.domain.admin

/** bbsmenu.html のリンク先から板名を拾う */
object BoardLinks {
  private val Href = "(?i)href\\s*=\\s*[\"']?([^\"'\\s>]+)".r
  private val Board = "(?:(?:https?:)?//[^/]+)?/([a-zA-Z0-9_-]{1,80})/?(?:index\\.html?)?".r

  /** /板名/ の形のリンクの板名。出てきた順で重複は除く */
  def fromMenu(html: String): List[String] =
    Href.findAllMatchIn(html).map(_.group(1)).collect { case Board(bbs) => bbs }.toList.distinct
}
