package ch3s.common.routes

import munit.FunSuite

class RouteTableSuite extends FunSuite {

  test("routes include the health route and the all-method legacy dispatcher") {
    assertEquals(RouteTable.routes.map(_.filePath), List("_v6/health.ts", "index.ts"))
  }

  test("renderRouteFile は PassThrough(GET) を export default createRoute(...) として生成する") {
    val entry = RouteTable.routes.head
    val rendered = RouteTable.renderRouteFile(entry)
    assert(rendered.contains("""import { healthHandler } from "scalajs:server.js";"""))
    assert(rendered.contains("export default createRoute((c) => healthHandler(c));"))
  }

  test("renderRouteFile は POST 等の非GETメソッドを named export として生成する") {
    val entry = RouteTable.RouteEntry(
      filePath = "_v6/probe.ts",
      kind = RouteTable.RouteKind.PassThrough("probeHandler", method = "POST"),
      comment = Nil
    )
    val rendered = RouteTable.renderRouteFile(entry)
    assert(rendered.contains("export const POST = createRoute((c) => probeHandler(c));"))
  }

  private val bodyAfterBanner: String => String = _.linesIterator.drop(3).mkString("\n") + "\n"

  test("renderMiddlewareFile は (c: Context, next: Next) を型注釈した配列を default export する") {
    val rendered = RouteTable.renderMiddlewareFile(
      RouteTable.MiddlewareEntry("admin", "adminGuard", excludePathPrefix = None)
    )
    val expected =
      """import type { Context, Next } from "hono";
        |// @ts-expect-error scalajs:server.js は仮想モジュール（vite.config.ts参照）。
        |import { adminGuard } from "scalajs:server.js";
        |
        |export default [
        |  async (c: Context, next: Next) => {
        |    const blocked = await adminGuard(c);
        |    if (blocked) return blocked;
        |    return next();
        |  },
        |];
        |""".stripMargin
    assertEquals(bodyAfterBanner(rendered), expected)
  }

  test("renderMiddlewareFile は excludePathPrefix があれば除外判定を先頭に入れる") {
    val rendered = RouteTable.renderMiddlewareFile(
      RouteTable.MiddlewareEntry("admin", "adminGuard", excludePathPrefix = Some("/admin/public"))
    )
    val expected =
      """  async (c: Context, next: Next) => {
        |    if (c.req.path.startsWith("/admin/public")) return next();
        |    const blocked = await adminGuard(c);""".stripMargin
    assert(rendered.contains(expected))
  }

  test("islandImportPath は routes 配下のディレクトリ階層に応じて ../ の数を変える") {
    assertEquals(RouteTable.islandImportPath("index.ts", "thread-view"), "../islands/thread-view")
    assertEquals(RouteTable.islandImportPath("_v6/health.ts", "thread-view"), "../../islands/thread-view")
    assertEquals(RouteTable.islandImportPath("a/b/c.tsx", "thread-view"), "../../../islands/thread-view")
  }

  test("renderIslandFile は scalajs 仮想モジュールの export を default export に再公開する") {
    val rendered = RouteTable.renderIslandFile(RouteTable.IslandEntry("thread-view.tsx", "ThreadViewJs", "client.js"))
    val expected =
      """// @ts-expect-error scalajs:client.js は @scala-js/vite-plugin-scalajs が解決する仮想モジュール（.d.ts無し）。
        |import { ThreadViewJs } from "scalajs:client.js";
        |export default ThreadViewJs;
        |""".stripMargin
    assertEquals(bodyAfterBanner(rendered), expected)
  }

  test("renderRouteFile は MultiMethodPassThrough を GET=default / 他=named で1ファイルに出し、import は重複させない") {
    val entry = RouteTable.RouteEntry(
      filePath = "test/bbs.cgi.ts",
      kind = RouteTable.RouteKind.MultiMethodPassThrough(List("GET" -> "bbsHandler", "POST" -> "bbsHandler", "PUT" -> "putHandler")),
      comment = List("// bbs.cgi")
    )
    val expected =
      """import { createRoute } from "honox/factory";
        |// @ts-expect-error scalajs:server.js は仮想モジュール（vite.config.ts参照）。
        |import { bbsHandler, putHandler } from "scalajs:server.js";
        |
        |// bbs.cgi
        |export default createRoute((c) => bbsHandler(c));
        |export const POST = createRoute((c) => bbsHandler(c));
        |export const PUT = createRoute((c) => putHandler(c));
        |""".stripMargin
    assertEquals(bodyAfterBanner(RouteTable.renderRouteFile(entry)), expected)
  }

  private def islandEntry(filePath: String, hasRangeParam: Boolean) = RouteTable.RouteEntry(
    filePath = filePath,
    kind = RouteTable.RouteKind.IslandRender(
      "threadProps",
      RouteTable.IslandRef("thread-view", "ThreadView"),
      hasRangeParam
    ),
    comment = List("// read.cgi")
  )

  test("renderRouteFile は IslandRender を props 取得 → c.render の形で生成する（range なし）") {
    val expected =
      """import { createRoute } from "honox/factory";
        |import ThreadView from "../../islands/thread-view";
        |// @ts-expect-error scalajs:server.js は仮想モジュール（vite.config.ts参照）。
        |import { threadProps } from "scalajs:server.js";
        |
        |// read.cgi
        |export default createRoute(async (c) => {
        |  const props = await threadProps(c, undefined);
        |  if (!props) return c.notFound();
        |  return c.render(<ThreadView {...props} />, { title: props.title });
        |});
        |""".stripMargin
    assertEquals(bodyAfterBanner(RouteTable.renderRouteFile(islandEntry("read/[key].tsx", hasRangeParam = false))), expected)
  }

  test("renderRouteFile は IslandRender の hasRangeParam=true で c.req.param(\"range\") を渡す") {
    val rendered = RouteTable.renderRouteFile(islandEntry("read/[key]/[range].tsx", hasRangeParam = true))
    assert(rendered.contains("""const props = await threadProps(c, c.req.param("range"));"""))
    assert(rendered.contains("""import ThreadView from "../../../islands/thread-view";"""))
  }

  test("renderRouteFile は LegacyDispatch で health 以外を publicHandler へ流す app を default export する") {
    val rendered = RouteTable.renderRouteFile(RouteTable.RouteEntry("index.ts", RouteTable.RouteKind.LegacyDispatch, Nil))
    assert(rendered.contains("""import { publicHandler, healthHandler } from "scalajs:server.js";"""))
    assert(rendered.contains("app.all('*', (c) => c.req.path === '/_v6/health' ? healthHandler(c) : publicHandler(c));"))
    assert(rendered.contains("export default app;"))
  }

  test("生成ファイルはすべて自動生成バナーで始まり、改行で終わる") {
    val outputs = List(
      RouteTable.renderRouteFile(RouteTable.routes.head),
      RouteTable.renderRouteFile(RouteTable.routes.last),
      RouteTable.renderIslandFile(RouteTable.IslandEntry("x.tsx", "X", "client.js")),
      RouteTable.renderMiddlewareFile(RouteTable.MiddlewareEntry("admin", "adminGuard", None))
    )
    outputs.foreach { out =>
      assert(out.startsWith("// This file is generated by codegen"), out)
      assert(out.linesIterator.take(3).forall(_.startsWith("//")), out)
      assert(out.endsWith("\n"), out)
    }
  }

  test("publicPatterns は v5 の評価順（archive が archives より先）で、代表パスにマッチする") {
    assertEquals(
      RouteTable.publicPatterns.map(_._1),
      List("dat", "subject", "archive", "archives", "head", "settings", "board")
    )
    def firstKind(path: String): Option[String] =
      RouteTable.publicPatterns.collectFirst { case (kind, re) if path.matches(s"(?s)(?:$re).*") && re.r.findPrefixOf(path).isDefined => kind }
    assertEquals(firstKind("/news/dat/1234567890.dat"), Some("dat"))
    assertEquals(firstKind("/news/subject.txt"), Some("subject"))
    assertEquals(firstKind("/news/kako/2020/subject.txt"), Some("archive"))
    assertEquals(firstKind("/news/kako/subject.txt"), Some("archives"))
    assertEquals(firstKind("/news/SETTING.TXT"), Some("settings"))
    assertEquals(firstKind("/news/"), Some("board"))
    assertEquals(firstKind("/news/index.html"), Some("board"))
    assertEquals(firstKind("/news/unknown"), None)
  }

  test("renderMiddlewareFile の型 import は @ts-expect-error の対象行（scalajs import）を隠さない") {
    val lines = RouteTable
      .renderMiddlewareFile(RouteTable.MiddlewareEntry("admin", "adminGuard", None))
      .linesIterator
      .toList
    val directive = lines.indexWhere(_.startsWith("// @ts-expect-error"))
    assert(lines(directive + 1).contains("scalajs:server.js"))
  }
}
