# ルート・Islands アノテーション化レビュー

2026-10-12。

## セルフレビュー

| 指摘・確認事項 | 判断・対応 |
| --- | --- |
| 生成対象の一覧と JS export の宣言が別々 | `bbs.Main` の `@route`、`bbsclient.Main` の `@islands` と同じ宣言の `@JSExportTopLevel` を読み取る。 |
| 既存 fetch の全メソッド振り分け | `app/routes/index.ts` を手書きに変更。health の例外分岐を含め、振り分けは維持する。`LegacyDispatch` による生成を廃止する。 |
| rawElement だけで Islands の SSR を置き換えられるか | React SSR は可能。ただし HonoX の Islands 変換を通らず、hydration 用の要素・属性が付かない。`tests/islands-bridge.test.js` で直接 import と生成 TSX 経由の描画を比較する。 |
| 公開仕様への影響 | 投稿・dat・Shift_JIS・ID・ストレージ処理は変更しない。 |
| 出力パスの衝突と古い生成物 | 同一ファイル・メソッドの重複と大文字小文字だけ異なるパスを拒否する。生成バナーのある不要ファイルだけ削除し、手書きファイルとの衝突は書き込み前に拒否する。 |
| Islands のファイル名 | HonoX のクライアント探索に合う basename に制限する。`@islands` は未使用の ping 関数には付けない。 |
| health の実 workerd 疎通が 500 | 現設定に診断用 DO binding がない既存不整合。KB-32 に記録。成功を前提とした追加テストは取り下げ、今回の変更で設定やハンドラを修正しない。 |

アノテーション引数は文字列リテラルを宣言順に指定する。関数の引数・戻り値や React props の型を
Scala.meta で意味解析するものではなく、Scala/TypeScript のコンパイルと実際の描画検証で確認する。

## SSR と rawElement の調査

`scalajs-react` の `rawElement` は React 要素なので、既存の `HonoContextFacade.renderPromise` に渡せる。
`HonoRenderSuite` の UTF-8 renderer テストでも描画を確認している（同期 Shift_JIS テストには既存の doctype 期待値不一致、KB-33 がある）。ただし「React が HTML を描画できる」と「HonoX がブラウザで hydrate できる」は別の条件。

導入済み HonoX 0.1.61 の `dist/vite/island-components.js` は `app/islands/*.tsx` の export を
`HonoXIsland` でラップする。`dist/vite/components/honox-island.js` が `honox-island`、
`component-name`、`data-serialized-props` を出力し、`dist/client/client.js` がその情報と
`import.meta.glob` のファイル一覧を対応づけて hydrate する。React 設定自体は
[HonoX の BYOR ドキュメント](https://github.com/honojs/honox#byor---bring-your-own-renderer)に従う。

従って、Scala コンポーネントの `rawElement` をそのまま渡す方法では静的 HTML のみとなる。
SSR 側から TSX の import をなくすこと自体は、`honox/vite/components` の `HonoXIsland` を
Scala facade 経由で使用し、コンポーネント・props・生成ファイル名を渡す橋渡しを用意すれば可能。
その場合もクライアント側の生成 TSX と識別子を一致させる必要があり、ネストした Islands や
React 要素を含む props の取り扱いを保つ必要がある。単なる `rawElement` への置換としては採用しない。
2026-10-12 時点では実利用する Islands がないため、生成方式の変更と比較検証までとした。
2026-10-13、利用者の指示により facade 方式を実装した。利用方法は [Scala からの Islands SSR](islands-ssr.md)、
検証・判断は [SSR レビュー](islands-ssr-review.md)を参照。

## 検証

- `npm run v6:build`: 成功。クライアント・Workers ともビルド済み。
- `sbt codegen/run`: 最終変更で再実行し成功。health 1件、Islands 0件。手書き index.ts を保持。
- `npm run v6:typecheck`: 成功。
- `npm test`: 4件成功。実 workerd の管理・投稿、クライアントバンドル、SSR の接続と直接描画の比較。
- `sbt test`: common JVM / JS 各14件、client 1件、codegen 13件成功。bbs は145件中144件成功、既存の doctype 期待値不一致（KB-33）で1件失敗。
- Scala.meta の非推奨 `templ.stats` は `templ.body.stats` に変更し、最終コンパイルで警告なし。sbt 起動時の JDK Unsafe 警告は既存ツール由来。

## 外部レビュー

Codex CLI → Gemini CLI は未実施。AGENTS.md §3.2 の「外部 CLI にコードを送信する前に、利用者の許可を取ること」に従い、許可待ち。
