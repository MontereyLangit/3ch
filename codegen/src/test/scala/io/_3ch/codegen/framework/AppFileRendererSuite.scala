package io._3ch.codegen.framework

import munit.FunSuite
import scala.meta.*

class AppFileRendererSuite extends FunSuite {
  private val config = TestConfig()
  private val bodyAfterBanner: String => String = _.linesIterator.drop(config.banner.size).mkString("\n") + "\n"

  test("route ファイルは GET を export default、ほかを named export にし、import を重複させない") {
    val entry = RouteEntry("test/bbs.cgi.ts", List(
      RouteHandler(HttpMethod.PUT, "putHandler"), RouteHandler(HttpMethod.POST, "bbsHandler"), RouteHandler(HttpMethod.GET, "bbsHandler")
    ))
    val expected =
      """import { createRoute } from "honox/factory";
        |// @ts-expect-error scalajs:server.js は仮想モジュール（vite.config.ts参照）。
        |import { bbsHandler, putHandler } from "scalajs:server.js";
        |
        |export default createRoute((c) => bbsHandler(c));
        |export const POST = createRoute((c) => bbsHandler(c));
        |export const PUT = createRoute((c) => putHandler(c));
        |""".stripMargin.replace("\r\n", "\n")
    assertEquals(bodyAfterBanner(AppFileRenderer.route(entry, config)), expected)
  }

  test("island ファイルは仮想モジュールの export を default export に再公開する") {
    val rendered = AppFileRenderer.island(IslandEntry("thread-view.tsx", "ThreadViewJs", "client.js"), config)
    val expected =
      """// @ts-expect-error scalajs:client.js は @scala-js/vite-plugin-scalajs が解決する仮想モジュール（.d.ts無し）。
        |import { ThreadViewJs } from "scalajs:client.js";
        |export default ThreadViewJs;
        |""".stripMargin.replace("\r\n", "\n")
    assertEquals(bodyAfterBanner(rendered), expected)
  }

  test("生成ファイルはすべて設定のバナーで始まり、改行で終わる") {
    List(
      AppFileRenderer.route(RouteEntry("x.ts", List(RouteHandler(HttpMethod.GET, "x"))), config),
      AppFileRenderer.island(IslandEntry("x.tsx", "X", "client.js"), config),
      AppFileRenderer.islandComponents(Nil, config)
    ).foreach { out =>
      assert(out.startsWith(config.banner.mkString("", "\n", "\n")), out)
      assert(out.endsWith("\n"), out)
    }
  }

  private val entries = List(
    IslandEntry("thread/counter.tsx", "CounterJs", "client.js", Some("_root_.pkg.CounterProps")),
    IslandEntry("panel.tsx", "match", "client.js")
  )

  test("SSR 用 API は export と HonoX の識別子を共有し、ファイルパス順に並ぶ") {
    val source = AppFileRenderer.islandComponents(entries, config)
    assertEquals(AppFileRenderer.islandComponents(entries.reverse, config), source)
    val tree = dialects.Scala3(source).parse[Source].get
    assertEquals(tree.collect { case pkg: Pkg => pkg.ref.syntax }, List("example.generated"))
    val api = tree.collect { case obj: Defn.Object if obj.name.value == "Islands" => obj }.head
    val methods = api.templ.body.stats.collect { case method: Defn.Def => method }
    assertEquals(methods.map(_.name.value), List("match", "CounterJs"))
    assertEquals(methods.map(_.body.collect { case Lit.String(value) => value }),
      List(List("/app/islands/panel.tsx"), List("/app/islands/thread/counter.tsx")))
    assertEquals(methods.map(_.paramClauses.head.values.head.decltpe.get.syntax),
      List("_root_.scala.scalajs.js.Object", "_root_.pkg.CounterProps"))
    assert(methods.forall(_.body.syntax.contains("_root_.example.bridge.Island(")))
    assert(source.contains("@JSImport(\"scalajs:client.js\", \"CounterJs\")"))
    assert(source.contains("extends js.Function1[_root_.pkg.CounterProps, React.Node]"))
    assert(!source.contains("@JSImport(\"/app/islands"))
  }

  test("island が無ければ空の object だけを出す") {
    val source = AppFileRenderer.islandComponents(Nil, config)
    val tree = dialects.Scala3(source).parse[Source].get
    assertEquals(tree.collect { case obj: Defn.Object => obj.name.value }, List("Islands"))
    assert(!source.contains("@JSImport"))
  }
}
