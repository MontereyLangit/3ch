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

  test("renderMiddlewareFile の型 import は @ts-expect-error の対象行（scalajs import）を隠さない") {
    val lines = RouteTable
      .renderMiddlewareFile(RouteTable.MiddlewareEntry("admin", "adminGuard", None))
      .linesIterator
      .toList
    val directive = lines.indexWhere(_.startsWith("// @ts-expect-error"))
    assert(lines(directive + 1).contains("scalajs:server.js"))
  }
}
