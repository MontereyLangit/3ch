# Scala からの Islands SSR

`@islands` を付けた client export は、ブラウザ用 TSX とサーバー用 Scala API の両方を生成する。
SSR 側は `app/islands/*.tsx` を import せず、生成 API が raw な client export を読み込み、
`honox/vite/components` の `HonoXIsland` を React 要素として組み立てる。

## 宣言と利用

`bbs-client/.../Main.scala` で、JS props を受け取る React コンポーネントを export する。
次は JS 向け React コンポーネント値を `ThreadView.component` とした利用例。
ScalaComponent の builder / Scala 側ラッパー自体ではなく、JS props から React 要素を返す JS 関数などを export する。

```scala
import ch3s.common.routes.islands
import scala.scalajs.js.annotation.JSExportTopLevel

@islands("thread-view.tsx")
@JSExportTopLevel("ThreadViewJs", "client")
val threadView = ThreadView.component
```

`npm run v6:generate` または `npm run v6:build` で次を生成する。

| 生成先 | 内容 |
| --- | --- |
| `app/islands/thread-view.tsx` | `scalajs:client.js` の `ThreadViewJs` を default export。ブラウザ側の探索・読み込み入口。 |
| `bbs/src/main/scala/ch3s/bbs/generated/IslandComponents.scala` | `IslandComponents.ThreadViewJs(props)`。raw な `ThreadViewJs` と `/app/islands/thread-view.tsx` を結び付ける。 |

サーバーの Scala ページでは次のように使う。

```scala
import ch3s.bbs.generated.IslandComponents
import scala.scalajs.js

val props = js.Dictionary[js.Any]("threadId" -> "123")
val island = IslandComponents.ThreadViewJs(props)
context.renderPromise(island.rawElement)
```

戻り値は `VdomElement` なので、scalajs-react のページの子要素にもできる。
`props` は JS オブジェクトとして渡し、通常の Scala case class やコレクションを直接渡さない。
普通のデータ props は JSON で受け渡せる値にする。React 要素や要素配列の props は HonoX が別扱いする。
イベントハンドラはクライアントコンポーネント内部で定義し、サーバーから関数を props として送らない。

export 名と生成メソッド名は同じ。ファイル名を変更した場合は再生成すれば SSR 側の識別子も追従する。
同じ export 名を異なる island ファイルへ割り当てる宣言は、SSR API が曖昧になるため生成時に拒否する。
生成 Scala は現在 Islands の宣言がない場合も空の `IslandComponents` として出力する。

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
利用している HonoX 0.1.61 の挙動に依存するため、更新時は下記のテストで確認する。

## 検証

`npm test` の前処理で `bbs/Test/fastLinkJS` を実行し、テスト専用 module `island-test.js` を生成する。
本番 module へテスト用 export や診断ルートは追加しない。

`tests/islands-hydration.test.js` は Vite で実際の Scala facade と HonoX を接続し、jsdom 上で
React の hydration を実行する。通常の Island・通常の入れ子・React 要素配列を含む props を確認する。
クライアントのコンポーネント探索は HonoX の `import.meta.glob` を使い、識別子の対応も検証する。
SSR DOM の再利用、hydration エラーがないこと、クリックによる状態更新を確認する。
レイアウトや実ブラウザ固有の挙動を検証するテストではない。
