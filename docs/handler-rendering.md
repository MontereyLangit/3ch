# Scala.js handler からの描画

ルートの TS は `export default createRoute((c) => handler(c));` を生成する。
データ取得、パラメータ解釈、描画方式の選択は Scala.js の handler が担当する。
`IslandRender` は廃止し、`app/routes/**/_middleware.ts` は手動管理する。
既存の全メソッド用 `LegacyDispatch` は公開ルートの互換性維持のため残す。

## 通常画面

`VdomElement.rawElement` は scalajs-react の `React.Element`（実体は React の要素）を返す。
文字列化や JSON 変換は不要。`HonoContextFacade.render` にそのまま渡せる。
Hono の戻り値は `Response | Promise<Response>` なので、Promise を返す handler では
`js.Promise.resolve[Response](c.render(...))` または `c.renderPromise(...)` を使う。

```scala
import ch3s.bbs.external.hono.{HonoContextFacade, RenderProps}
import ch3s.bbs.external.hono.HonoContextFacade.*
import ch3s.bbs.external.web.Response
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("pageHandler", "server")
def pageHandler(c: HonoContextFacade): js.Promise[Response] = {
  val props = new RenderProps {}
  props.title = "板一覧"
  val page = <.main(<.h1("板一覧"))
  c.renderPromise(page.rawElement, props)
}
```

`RouteTable.routes` には `RouteEntry("boards.ts", RouteKind.PassThrough("pageHandler"), Nil)`
を登録する。`props` は省略可能。`RenderProps` は `app/renderer.d.ts` の Props と対応させる。
通常画面は既存の `_renderer.tsx` と `RendererShell` により UTF-8 のレイアウト付き応答になる。
Hono の API は [render / setRenderer](https://hono.dev/docs/api/context#render-setrenderer) を参照。

## Shift_JIS の応答

```scala
import ch3s.bbs.handler.RendererShell.createSjisResponse

js.Promise.resolve(createSjisResponse(page.rawElement, "text/html"))
js.Promise.resolve(createSjisResponse("<html><!-- 2ch_X:error -->本文</html>", "text/html"))
```

`contentType` は charset を含まない MIME 型を渡す。Shift_JIS の charset とバイト長を設定する。
React 要素は `renderToStaticMarkup` で HTML 化し、文字列はそのままエンコードする。
レイアウトや doctype は自動では加えない。既存の Range / 304 / 投稿文字コード選択が必要な経路は
`Responses.plain` / `RendererShell.legacyHtml` / `legacyReact` を引き続き使う。

## Islands

`RouteTable.islands` の登録と `renderIslandFile` の形式は維持する。
サーバー側は Scala コンポーネントの元の export ではなく、HonoX がラップする
`app/islands/*.tsx` を import する。これにより SSR に hydration 用の識別情報と props が付く。

```scala
import japgolly.scalajs.react.{Children, JsComponent}
import scala.scalajs.js.annotation.JSImport

trait ThreadViewProps extends js.Object {
  val title: String
}

@js.native
@JSImport("/app/islands/thread-view.tsx", "default")
object ThreadViewIsland extends js.Object

val ThreadView = JsComponent[ThreadViewProps, Children.None, Null](ThreadViewIsland)

def threadHandler(c: HonoContextFacade): js.Promise[Response] = {
  val props = new ThreadViewProps { val title = "スレッド" }
  c.renderPromise(ThreadView(props).rawElement)
}
```

例のコンポーネントを実装する際は `IslandEntry("thread-view.tsx", "ThreadViewJs", "client.js")`
を登録し、`bbs-client` から React コンポーネントを `ThreadViewJs` として export する。
`bbs-client` でも scalajs-react の DSL を使う場合は、`bbs` と同じ `core` 依存を追加する。
props は HonoX が JSON 化できる JS オブジェクトにする。Scala のケースクラスや関数は渡さない。
サーバーとクライアントは別々にリンクするため、`bbs` のコードを `bbs-client` に依存させない。
ブラウザ側は `app/client.ts` の React `hydrateRoot` で hydration する。

この変更自体は新しい画面・Island コンポーネントを登録しない。既存の公開エンドポイントの
Shift_JIS 出力、dat、ストレージ形式は変更しない。
