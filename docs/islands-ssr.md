# Scala からの Islands SSR

island の仕組み（`@islands[P]` の宣言、生成する TSX と `IslandComponents`、`hx4s.react.Island` による HonoX の境界、
props のシリアライズ、`IslandExport[P]` と hydrate 前の検証）は hx4s が持つ。仕様と保証の範囲は hx4s の文書を参照する
（hx4s の checkout の `docs/hx4s/rendering-model.md`、`docs/hx4s/adr/0007-props-validation-and-typed-export.md`）。
生成の設定は `docs/codegen.md`。ここには 3ch の約束だけを書く。

## 宣言と利用

props の形は `common/src/main/scala-js/io/_3ch/common/islands/` に非ネイティブ JS trait として置き、companion に `fields`
（`hx4s.props.JsField` の一覧。キー名・種類・省略可否）と `validate` を置く。`@islands` の型引数には完全修飾名で書く。

```scala
// bbs-client/.../Main.scala
@islands[io._3ch.common.islands.ThreadListProps]("thread-list")
@JSExportTopLevel("ThreadListJs", "client")
final val threadList: IslandExport[ThreadListProps] = IslandExport("ThreadListJs", ThreadListProps.fields)(ThreadListIsland.render)
```

サーバーの Scala ページでは `io._3ch.bbs.generated.IslandComponents.ThreadListJs(props)` を使う。戻り値は `VdomElement`。
props の trait に使ってよい型は `String`・`Double`・`Int`・`Boolean`・`js.UndefOr[...]`・`js.Array[...]`・他の props trait。
`Double` には `JsKind.Num`、`Int` には `JsKind.Int` を対応させる。`Option`・Scala のコレクション・関数・`Long` は使わない。
`js.UndefOr` はサーバーが実際に省くキー（未ログイン時の `session`、新規スレッドの `threadKey`、キーの `boundAt` など）だけに使う。
イベントハンドラはクライアントコンポーネント内部で定義し、サーバーから関数を props として送らない。

板名・スレキーのような値の妥当性まで確かめる island（`ThreadListIsland`・`BoardDetailsIsland`・`PostingFormIsland`）は、
`render`（`Props.fromJs` で Scala の値に変換し、変換できなければ何も描画しない）を `IslandExport` に渡す。

## 不正な props のとき

| 場面 | 挙動 |
| --- | --- |
| SSR（サーバーが組み立てた props が `fields` に合わない） | `IslandExport` が `console.error` に island 名と誤りのあるキーを出し、何も描画しない（hx4s 移行前の `JsComponent` と同じ） |
| hydrate の前（HTML と client の世代ずれなど） | `app/client.ts` が `hx4s-vite/client` の `islandPropsProblem` で確かめ、合わなければ `console.error` を出して hydrate せず、SSR の markup を残す |

hx4s 移行前は hydrate 前の確かめが無く、client の `JsComponent` が空を描画して SSR の内容を消していた。
props を持たない `AuthModalJs` は検証しない（`IslandExport` を使わず、`toJsComponent.raw` を export する）。

## 検証

`npm test` の前処理で `bbs/Test/fastLinkJS` を実行し、テスト専用 module `island-test.js`（`renderBoardTopTest`）を生成する。
本番 module へテスト用 export や診断ルートは追加しない。

- `tests/board-top-hydration.test.js`: 板top の実 SSR HTML を HonoX の `createClient` と `app/client.ts` と同じ検証で hydrate する。
  3 つの island がすべて hydrate され DOM を再利用すること、props の形が古い island だけが SSR のまま残ることを確かめる
- `tests/board-islands.test.js`: 各 island の描画・操作、欠けた・型が違う props での `console.error` と空描画、
  11 個の export が検証関数を持つこと
- HonoX との接続（入れ子・要素 props・危険な文字列の往復・複数インスタンス）の汎用の検証は hx4s のテストが持つ
