# Durable Object の TypeScript 型生成

`npm run v6:generate`（`sbt codegen/run`）で、ルートとともに
`app/scalajs-durable-objects.generated.d.ts` を生成する。`npm run v6:build` からも実行される。
生成ファイルは Git に含める。手編集せず、DO の変更時に再生成する。
`app/scalajs-server.d.ts` は `renderShell` の手書き宣言を保持する。

## 生成元と対応範囲

`bbs/src/main/scala/ch3s/bbs/durable/` 以下の Scala ファイルを Scalameta の Scala 3
パーサで構文解析し、`@JSExportTopLevel("名前", "server")` を持つクラスを抽出する。
annotation の export 名と、コンストラクタの引数名・型を使用する。
コメント・文字列内の疑似宣言は対象にしない。出力は export 名順で固定し、
追加・改名・削除は次の生成に反映される。

TS 側の用途に合わせ、クラスとコンストラクタのみを生成する。
RPC メソッドや facade のメンバー定義は展開しない。
`DurableObjectState` / `WorkerEnv` は `object`、`js.Any` は `unknown` に変換する。
`String` / `Boolean` / `Int` / `Double` も対応する TS のプリミティブ型へ変換する。
対応外の引数型、非リテラルの export 名、重複 export、デフォルト引数などは生成エラーになる。

現行コードと同じ、`DurableObject` の直接継承・単一コンストラクタ引数リストを前提にする。
型・annotation の import 別名やユーザー定義の型エイリアスの名前解決は行わない。
対応する名前は現行の短縮名または完全修飾名を使用すること。
`app/server.ts`、`vite.config.ts` の再 export と Wrangler の binding の更新は対象外。

## セルフレビュー（2026-10-06）

| 確認事項 | 判断・対応 |
| --- | --- |
| 宣言の原本 | Scala のクラス宣言と export annotation を使用。生成側に DO 名一覧を持たせない。 |
| 既存型との互換性 | 以前のクラス宣言の `object` / `unknown` を維持。Placeholder の第1引数名は Scala と同じ `ctx`。 |
| 公開仕様・ストレージ | 実行コード・公開プロトコル・ストレージスキーマの変更なし。 |
| 宣言の取りこぼし | import 別名の解決は対象外として明記。未対応の引数型などは明示的に拒否する。 |

## 検証

- `sbt codegen/test codegen/run`: 5 件成功、実ソースから 3 クラスを生成。
- `npm run v6:typecheck`: 成功。
- `npm run v6:build`: 成功。通常のビルド経路でも型定義を再生成。
- `npm test`: 2 件成功（実 workerd の統合テストを含む）。
- `git diff --check`: 成功。

## 外部 CLI レビュー

未実施。AGENTS.md §3.2 に基づく外部送信許可待ち。
許可後に Codex CLI → Gemini CLI の順で実施する。
