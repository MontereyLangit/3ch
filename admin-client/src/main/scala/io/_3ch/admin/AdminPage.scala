package io._3ch.admin

import io._3ch.common.admin.RuleName

/** 管理画面の画面。path は画面の URL で、末尾は / */
enum AdminPage {
  case Top
  case Board(bbs: String)
  case Threads(bbs: String)
  case Thread(bbs: String, key: String)
  case Rules(name: RuleName)
  case Menu
  case NotFound(requested: String)

  def path: String = this match {
    case Top => "/admin/"
    case Board(bbs) => s"/admin/boards/$bbs/"
    case Threads(bbs) => s"/admin/boards/$bbs/threads/"
    case Thread(bbs, key) => s"/admin/boards/$bbs/threads/$key/"
    case Rules(name) => s"/admin/rules/${name.value}/"
    case Menu => "/admin/menu/"
    case NotFound(requested) => requested
  }

  /** 板に属する画面なら板名 */
  def board: Option[String] = this match {
    case Board(bbs) => Some(bbs)
    case Threads(bbs) => Some(bbs)
    case Thread(bbs, _) => Some(bbs)
    case _ => None
  }

  /** ブラウザーのタイトル */
  def title: String = (this match {
    case Top => "管理画面"
    case Board(bbs) => s"板設定: $bbs"
    case Threads(bbs) => s"スレ一覧: $bbs"
    case Thread(bbs, key) => s"スレ管理: $bbs/$key"
    case Rules(RuleName.Access) => "アクセス規制"
    case Rules(RuleName.Ngwords) => "NGワード"
    case Menu => "板一覧"
    case NotFound(_) => "ページがありません"
  }) + " - 3ch 管理画面"
}

object AdminPage {
  /** 板名。英数字・_・- の 1〜80 文字（サーバの AdminService.validBoard と同じ） */
  def validBoard(value: String): Boolean = value.matches("[a-zA-Z0-9_-]{1,80}")

  /** スレの key。10 桁の数字 */
  def validKey(value: String): Boolean = value.matches("[0-9]{10}")

  /** URL のパスから画面を引く。末尾の / は無くてもよい */
  def parse(path: String): AdminPage = {
    val segments = if (path == "/admin" || path.startsWith("/admin/")) Some(path.stripPrefix("/admin").split("/").toList.filter(_.nonEmpty)) else None
    segments match {
      case Some(Nil) => Top
      case Some(List("boards", bbs)) if validBoard(bbs) => Board(bbs)
      case Some(List("boards", bbs, "threads")) if validBoard(bbs) => Threads(bbs)
      case Some(List("boards", bbs, "threads", key)) if validBoard(bbs) && validKey(key) => Thread(bbs, key)
      case Some(List("rules", name)) => RuleName.fromValue(name).fold(NotFound(path))(Rules(_))
      case Some(List("menu")) => Menu
      case _ => NotFound(path)
    }
  }
}
