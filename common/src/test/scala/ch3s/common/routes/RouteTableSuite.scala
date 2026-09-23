package ch3s.common.routes

import munit.FunSuite

class RouteTableSuite extends FunSuite:

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
