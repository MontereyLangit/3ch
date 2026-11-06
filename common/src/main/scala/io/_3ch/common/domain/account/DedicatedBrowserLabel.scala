package io._3ch.common.domain.account

/** 専ブラ UA から製品名・版と既知の OS 名だけを表示用に抜き出す */
object DedicatedBrowserLabel {
  private val Product = "([A-Za-z][A-Za-z0-9_-]{1,31})[/ ]([0-9][0-9.]{0,15})".r
  def of(userAgent: String): String = {
    val remainder = userAgent.trim.stripPrefix("Monazilla/1.00")
    val product = Product.findFirstMatchIn(remainder).map(found => found.group(1) + " " + found.group(2)).getOrElse("専用ブラウザ")
    val os = Seq("Windows" -> "Windows", "Android" -> "Android", "iPhone" -> "iOS", "iPad" -> "iPadOS", "Macintosh" -> "macOS", "Linux" -> "Linux")
      .collectFirst { case (marker, name) if remainder.contains(marker) => name }
    product + os.fold("")(" · " + _)
  }
}
