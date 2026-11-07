package io._3ch.admin

import io._3ch.common.admin.RuleName
import munit.FunSuite

class AdminPageSuite extends FunSuite {
  private val pages = List(AdminPage.Top, AdminPage.Board("sandbox"), AdminPage.Threads("news_2"), AdminPage.Thread("a-b", "1800000000"),
    AdminPage.Rules(RuleName.Access), AdminPage.Rules(RuleName.Ngwords), AdminPage.Menu)

  test("画面の URL を読むと同じ画面に戻る") {
    pages.foreach(page => assertEquals(AdminPage.parse(page.path), page, page.path))
  }

  test("末尾の / が無い URL も同じ画面") {
    pages.foreach(page => assertEquals(AdminPage.parse(page.path.stripSuffix("/")), page, page.path))
  }

  test("板名・key・ルール名が不正な URL と知らない URL はページなし") {
    List("/admin/boards/../", "/admin/boards/a%2Fb/", "/admin/boards/sandbox/threads/123/", "/admin/rules/caps/", "/admin/board", "/adminx/", "/")
      .foreach(path => assertEquals(AdminPage.parse(path), AdminPage.NotFound(path), path))
    assertEquals(AdminPage.parse("/admin/boards/" + "a" * 81 + "/"), AdminPage.NotFound("/admin/boards/" + "a" * 81 + "/"))
  }

  test("板に属する画面だけが板名を持つ") {
    assertEquals(pages.map(_.board), List(None, Some("sandbox"), Some("news_2"), Some("a-b"), None, None, None))
  }
}
