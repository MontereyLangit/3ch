package ch3s.common.domain

/** bbs.cgi書き込み結果画面から取り出した要素 */
final case class PostingPage(title: String, errorText: Option[String], refreshContent: Option[String], cookieSummary: String, cookieTerms: String)

/** bbs.cgi書き込み結果の分類 */
sealed trait PostingOutcome

object PostingOutcome {

  /**
   * 書きこみました。
   * @param redirect 書き込み先URL。完了画面meta refreshで指定されている。
   */
  final case class Posted(redirect: Option[String]) extends PostingOutcome

  /**
   * ＥＲＲＯＲ！
   * TODO: 認証実装時、認証要求エラーは分離（Webは強制で認証させるため） -> 認証画面モーダル
   * @param message エラーメッセージ。エラー画面h1タグの中に入っている。
   */
  final case class Rejected(message: String) extends PostingOutcome

  /**
   * ■ 書き込み確認 ■
   * @param summary 概要文
   * @param terms 投稿規約
   */
  final case class NeedsCookie(summary: String, terms: String) extends PostingOutcome

  /**
   * 不明なエラー
   */
  case object Unrecognized extends PostingOutcome
}

/** 応答ページの `<title>` で結果を分類 */
object PostingResponse {
  private val PostedTitle = "書きこみました。"
  private val ErrorTitle = "ＥＲＲＯＲ！"
  private val CookieTitle = "■ 書き込み確認 ■"

  private val Refresh = """(?i)^\s*\d+\s*;\s*URL\s*=\s*(\S+)\s*$""".r

  /** 
   * `<meta http-equiv="refresh">` の content から遷移先を取る
   * `/` で始まる同一オリジンのパスだけ許可
   */
  def refreshUrl(content: String): Option[String] = {
    content match {
      case Refresh(url) if url.startsWith("/") && !url.startsWith("//") && !url.startsWith("/\\") => Option(url)
      case _ => None
    }
  }

  def classify(page: PostingPage): PostingOutcome = {
    page.title.trim match {
      case PostedTitle => PostingOutcome.Posted(page.refreshContent.flatMap(refreshUrl))
      case ErrorTitle => page.errorText.map(_.trim).filter(_.nonEmpty).fold[PostingOutcome](PostingOutcome.Unrecognized)(PostingOutcome.Rejected(_))
      case CookieTitle => PostingOutcome.NeedsCookie(page.cookieSummary.trim, page.cookieTerms.trim)
      case _ => PostingOutcome.Unrecognized
    }
  }

  /**
   * CONTENT-TYPEのcharset
   * 無い場合UTF-8
   */
  def charset(contentType: String): String = {
    """(?i)charset\s*=\s*"?([^\s;"]+)""".r.findFirstMatchIn(contentType).fold("utf-8")(_.group(1))
  }
}
