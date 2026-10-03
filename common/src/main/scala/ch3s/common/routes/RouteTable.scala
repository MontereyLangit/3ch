package ch3s.common.routes

/**
 * `app/routes/配下` を機械生成するための唯一の真実（参照実装 ch3s-migration の
 * common/.../routes/RouteTable.scala を踏襲）。
 *
 * このファイルは cross-build（JVM/JS両対応。`common` は Pure cross project）で、
 * ファイルI/Oを一切行わない純粋なデータ + 文字列生成関数のみを持つ。
 * I/O は JVM専用の `codegen` プロジェクト（`ch3s.codegen.GenerateAppFiles`）が担当する。
 *
 * v6s骨格（インフラのみ、docs/移植方針_step3_ScalaJS.md）の時点では、生成器と生成パイプラインが
 * 実際に動くことを示すための最小ルート（`GET /_v6/health`）を1件だけ持つ。
 * TODO: BBS ロジック（dat / subject.txt / bbs.cgi 等）のルートは次タスク以降で追加する。
 */
object RouteTable {
  // Preserve v5 prefix matches (including trailing path characters) and evaluation order.
  val publicPatterns: List[(String, String)] = List(
    "dat" -> "^/([a-zA-Z0-9\\-_]+)/dat/([0-9]{10})\\.dat",
    "subject" -> "^/([a-zA-Z0-9\\-_]+)/subject\\.txt",
    "archive" -> "^/([a-zA-Z0-9\\-_]+)/kako/([0-9]{4})/subject\\.txt",
    "archives" -> "^/([a-zA-Z0-9\\-_]+)/kako/subject\\.txt",
    "head" -> "^/([a-zA-Z0-9\\-_]+)/head\\.txt",
    "settings" -> "^/([a-zA-Z0-9\\-_]+)/SETTING\\.TXT",
    "board" -> "^/([a-zA-Z0-9\\-_]+)/(index\\.html)?$"
  )

  /** 生成する route ファイルの「中身の形」（参照実装の `RouteKind` から今回使う分だけを残した）。 */
  enum RouteKind {

    /**
     * `createRoute((c) => exportName(c));` だけの素通しルート。
     * `method` が `"GET"`（既定）なら `export default`、それ以外は `export const $method =`
     * という named export になる（HonoXの規約: `export default` = GETのみ登録）。
     */
    case PassThrough(exportName: String, method: String = "GET")
    case LegacyDispatch
  }

  /**
   * @param filePath `app/routes` からの相対パス（例: `"_v6/health.ts"`）。
   *                 HonoXのファイルベースルーティング規約がそのままURLに落とす。
   * @param kind      生成するファイルの中身の形
   * @param comment   ファイル冒頭（import群の後）に入れる説明コメント行（1行1要素、`// `込み）
   */
  final case class RouteEntry(filePath: String, kind: RouteKind, comment: List[String])

  val routes: List[RouteEntry] = List(
    RouteEntry(
      filePath = "_v6/health.ts",
      kind = RouteKind.PassThrough("healthHandler"),
      comment = List(
        "// GET /_v6/health -- v6s ビルド骨格の動作確認用エンドポイント（固定文字列を返すだけ）。",
        "// BBSロジックの移植はこの骨格の後続タスクで行う（docs/移植方針_step3_ScalaJS.md）。"
      )
    ),
    RouteEntry("index.ts", RouteKind.LegacyDispatch, Nil)
  )

  private val generatedBanner: List[String] = List(
    "// このファイルは codegen（ch3s.codegen.GenerateAppFiles）が RouteTable から自動生成した。",
    "// 手で編集しない（`npm run v6:generate` / `npm run v6:build` を実行するたびに上書きされる。",
    "//  真実の source は common/src/main/scala/ch3s/common/routes/RouteTable.scala）。"
  )

  /** [[RouteKind.PassThrough]] の1メソッド分の export 行を生成する。 */
  private def methodExportLine(method: String, exportName: String): String = {
    if (method == "GET") s"export default createRoute((c) => $exportName(c));"
    else s"""export const $method = createRoute((c) => $exportName(c));"""
  }

  /** route 1件分のファイル内容を生成する。 */
  def renderRouteFile(entry: RouteEntry): String = {
    val lines = scala.collection.mutable.ListBuffer.empty[String]
    lines ++= generatedBanner
    lines += "import { createRoute } from \"honox/factory\";"

    entry.kind match {
      case RouteTable.RouteKind.LegacyDispatch =>
        lines += "import { createHono } from \"honox/factory\";"
        lines += "// @ts-expect-error scalajs:server.js is resolved by the Scala.js Vite plugin."
        lines += "import { publicHandler, healthHandler } from \"scalajs:server.js\";"
        lines += "const app = createHono();"
        lines += "app.all('*', (c) => c.req.path === '/_v6/health' ? healthHandler(c) : publicHandler(c));"
        lines += "export default app;"
      case RouteTable.RouteKind.PassThrough(exportName, method) =>
        lines += "// @ts-expect-error scalajs:server.js は仮想モジュール（vite.config.ts参照）。"
        lines += s"""import { $exportName } from "scalajs:server.js";"""
        lines += ""
        lines ++= entry.comment
        lines += methodExportLine(method, exportName)
    }

    lines.mkString("\n") + "\n"
  }
}
