# Scala からの Islands SSR

`@islands` を付けた client export は、ブラウザ用 TSX とサーバー用 Scala API の両方を生成する。
SSR 側は `app/islands/*.tsx` を import せず、生成 API が raw な client export を読み込み、
`honox/vite/components` の `HonoXIsland` を React 要素として組み立てる。

## 宣言と利用

`bbs-client/.../Main.scala` で、JS props を受け取る React コンポーネントを export する。
props の形は `common/src/main/scala-js/io/_3ch/common/islands/` に非ネイティブ JS trait として置き、
`@islands` の型引数に完全修飾名で書く。サーバーとクライアントが同じ型を参照するので、キー名や型の食い違いはコンパイルエラーになる。
ScalaComponent の builder / Scala 側ラッパー自体ではなく、JS props から React 要素を返す JS 関数などを export する。

```scala
// common/src/main/scala-js/io/_3ch/common/islands/ThreadViewProps.scala
trait ThreadViewProps extends js.Object {
  val threadId: String
  val title: js.UndefOr[String]
}

// bbs-client/.../Main.scala
import io._3ch.common.routes.islands
import scala.scalajs.js.annotation.JSExportTopLevel

@islands[io._3ch.common.islands.ThreadViewProps]("thread-view.tsx")
@JSExportTopLevel("ThreadViewJs", "client")
val threadView = ThreadView.component   // ScalaFnComponent[ThreadViewProps] など
```

型引数を省いた `@islands("x.tsx")` は従来どおり props を `js.Object` として扱う（props の無い island 向け）。
型引数は完全修飾名（`_root_.` 付きも可）に限る。単純名や型エイリアスは codegen が解決できないのでエラーにする。

生成するものは次のとおり。

| 生成先 | 生成のきっかけ | 内容 |
| --- | --- | --- |
| `app/islands/thread-view.tsx` | `npm run v6:generate`（`npm run v6:build` も含む） | `scalajs:client.js` の `ThreadViewJs` を default export。ブラウザ側の探索・読み込み入口。 |
| `bbs/target/.../src_managed/main/io/_3ch/bbs/generated/IslandComponents.scala` | bbs のコンパイル（sbt の sourceGenerators） | `IslandComponents.ThreadViewJs(props: ThreadViewProps)`。raw な `ThreadViewJs` と `/app/islands/thread-view.tsx` を結び付ける。 |

サーバーの Scala ページでは次のように使う。

```scala
import io._3ch.bbs.generated.IslandComponents
import io._3ch.common.islands

val island = IslandComponents.ThreadViewJs(new islands.ThreadViewProps {
  val threadId = "123"
  val title = js.undefined
})
context.renderPromise(island.rawElement)
```

戻り値は `VdomElement` なので、scalajs-react のページの子要素にもできる。
props の trait に使ってよい型は `String`・`Double`・`Int`・`Boolean`・`js.UndefOr[...]`・`js.Array[...]`・他の props trait。
`Double` には `JsKind.Num`、`Int` には `JsKind.Int` を対応させる。
`Option`・Scala のコレクション・関数は HonoX のシリアライズで失われるので使わない。値が無いときは `js.undefined` にする（JSON ではキーごと省かれる）。
React 要素や要素配列の props は HonoX が別扱いする。

## クライアントの入口での検証

trait はシリアライズ後の値の欠落を検出できないので、props を持つ island（`AuthModalJs` 以外の 11 個）はすべて入口で
`io._3ch.common.validation.JsShape` を使って確かめる。各 island の `JsComponent` が検証してから `Component` を描画し、
`bbs-client/.../Main.scala` は `JsComponent` を export する。各 props の companion に `fields`（キー名・種類・省略可否）を置き、
island の props には `validate` も置く。
失敗したら `console.error` に island 名と誤りのあるキーを出し、例外にせず何も描画しない。
SSR でも同じ `JsComponent` が同じ props で動くので、SSR とクライアントの描画は食い違わない（不正なら両方とも空）。
数値は `JsKind.Num`（有限の数）と `JsKind.Int`（Int の範囲の整数）を使い分ける。`NaN`・無限大は JSON で `null` になり、
SSR とクライアントで値が変わるため、どちらも拒否する。
`JsShape` は 3ch の型に依存しない汎用の補助で、後で hx4s へ移す。

`js.UndefOr` はサーバーが実際に省くキー（未ログイン時の `session`、新規スレッドの `threadKey`、キーの `boundAt` など）だけに使う。
サーバー側は trait の抽象メンバをすべて定義しないとコンパイルできないので、送り忘れは起きない。
イベントハンドラはクライアントコンポーネント内部で定義し、サーバーから関数を props として送らない。

export 名と生成メソッド名は同じ。ファイル名を変更した場合は再生成すれば SSR 側の識別子も追従する。
1 つのメンバに付けられる `@islands` は 1 つまでで、export 名はメンバをまたいで一意なので、SSR API のメソッド名は重複しない。
生成 Scala は Islands の宣言がない場合も空の `IslandComponents` として出力する。生成の規則・設定・エラーの形式は `docs/codegen.md`。

## 描画と hydration

`Island` facade は `HonoXIsland` を通常関数として呼ばず、`React.createElement` で包む。
これにより HonoX 内部の Hook / Context が React の描画中に動作する。
`vite.config.ts` の `reactApiImportSource: "react"` が HonoX 側の API を React に合わせる。

SSR の `component-name` は生成 TSX の絶対形式の識別子（`/app/islands/...tsx`）であり、Scala の export 名ではない。
ブラウザは既存の `createClient` が探索した TSX の default export と対応付けて hydrate する。
SSR 用 Scala は `scalajs:client.js` を直接 import し、既存の Vite alias により独立リンクした client 成果物を読む。
サーバープロジェクトをクライアントの依存にはしない。

通常の入れ子は親の hydration 境界に含め、React 要素の props は `<template>` から復元する。
この判定・シリアライズを独自に実装せず、HonoX の Context と template 処理に任せる。
利用している HonoX 0.1.62（`package-lock.json` の版）の挙動に依存するため、更新時は下記のテストで確認する。

## 検証

`npm test` の前処理で `bbs/Test/fastLinkJS` を実行し、テスト専用 module `island-test.js` を生成する。
本番 module へテスト用 export や診断ルートは追加しない。

`tests/islands-hydration.test.js` は Vite で実際の Scala facade と HonoX を接続し、jsdom 上で
React の hydration を実行する。通常の Island・通常の入れ子・React 要素配列を含む props を確認する。
日本語・絵文字・U+2028・`</script>`・`<!--`・引用符を含む文字列、配列や入れ子の props、同じ island の複数インスタンスが
安全にシリアライズされ（HTML の構造として出ない）、hydration 後も同じ値で読めることも確かめる。
React が属性値を `&quot;` などに置き換え、HonoX の `createClient` が `JSON.parse` で戻すので、独自のエスケープは足していない。
クライアントのコンポーネント探索は HonoX の `import.meta.glob` を使い、識別子の対応も検証する。
SSR DOM の再利用、hydration エラーがないこと、クリックによる状態更新を確認する。
レイアウトや実ブラウザ固有の挙動を検証するテストではない。
