# Scala.js handler からの描画

ルートの TS は `bbs/.../Main.scala` の `@route` から codegen が生成し、`export default createRoute((c) => handler(c));`
の形になる（規則は `docs/codegen.md`）。データ取得、パラメータ解釈、描画方式の選択は Scala.js の handler が担当する。
`IslandRender` は廃止し、`app/routes/**/_middleware.ts` は手動管理する。
ファイルルートに一致しないリクエストは、`app/server.ts` の `app.all("*")` が既存の公開ハンドラ `publicHandler` に渡す。

## 通常画面

`VdomElement.rawElement` は scalajs-react の `React.Element`（実体は React の要素）を返す。
文字列化や JSON 変換は不要。`HonoContextFacade.render` にそのまま渡せる。
Hono の戻り値は `Response | Promise<Response>` なので、Promise を返す handler では
`js.Promise.resolve[Response](c.render(...))` または `c.renderPromise(...)` を使う。

```scala
import io._3ch.bbs.external.hono.{HonoContextFacade, RenderProps}
import io._3ch.bbs.external.hono.HonoContextFacade.*
import io._3ch.bbs.external.web.Response
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

@route("boards.ts")
@JSExportTopLevel("pageHandler", "server")
def pageHandler(c: HonoContextFacade): js.Promise[Response] = {
  val props = new RenderProps {}
  props.title = "板一覧"
  val page = <.main(<.h1("板一覧"))
  c.renderPromise(page.rawElement, props)
}
```

`@route` は `io._3ch.common.routes.route`。`props` は省略可能。`RenderProps` は `app/renderer.d.ts` の Props と対応させる。
通常画面は既存の `_renderer.tsx` と `RendererShell` により UTF-8 のレイアウト付き応答になる。
Hono の API は [render / setRenderer](https://hono.dev/docs/api/context#render-setrenderer) を参照。

## Shift_JIS の応答

```scala
import io._3ch.bbs.handler.RendererShell.createSjisResponse

js.Promise.resolve(createSjisResponse(page.rawElement, "text/html"))
js.Promise.resolve(createSjisResponse("<html><!-- 2ch_X:error -->本文</html>", "text/html"))
```

`contentType` は charset を含まない MIME 型を渡す。Shift_JIS の charset とバイト長を設定する。
React 要素は `renderToStaticMarkup` で HTML 化し、文字列はそのままエンコードする。
レイアウトや doctype は自動では加えない。既存の Range / 304 / 投稿文字コード選択が必要な経路は
`Responses.plain` / `RendererShell.legacyHtml` / `legacyReact` を引き続き使う。

## Islands

Island は `bbs-client/.../Main.scala` の export に `@islands[P]("x.tsx")` を付けて宣言する。codegen がブラウザ用の
`app/islands/x.tsx` と、SSR 用の `io._3ch.bbs.generated.IslandComponents` を生成する。handler は生成 API を呼び、
戻り値の `VdomElement` をページの子要素にするか `c.renderPromise(island.rawElement)` に渡す。
SSR 側は `app/islands/*.tsx` を import せず、生成 API が HonoX の `HonoXIsland` で包むので、hydration 用の識別情報と props が付く。
宣言・props の型・検証は `docs/islands-ssr.md` を参照。

props は HonoX が JSON 化できる JS オブジェクトにする。Scala のケースクラスや関数は渡さない。
サーバーとクライアントは別々にリンクするため、`bbs` のコードを `bbs-client` に依存させない。
ブラウザ側は `app/client.ts` の HonoX `createClient` が React の `hydrateRoot` で hydration する。
