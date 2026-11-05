# DO RPC スタブ自動生成のレビュー記録

2026-11-01。計画は [durable-object-stub-generation-plan.md](durable-object-stub-generation-plan.md)、
生成仕様は [durable-object-stubs.md](durable-object-stubs.md)。

## セルフレビュー

| 指摘・確認事項 | 判断・対応 |
| --- | --- |
| 公開メソッド抽出が Scala.js / Cloudflare RPC の公開範囲と一致するか | DO は `js.Object` 由来の非 native JS クラスなので、`private` でない `def` がそのまま JS メンバーになる（`protected` も露出する。`scala3-compiler` の `PrepJSInterop` / `ExplicitJSClasses` で確認）。生成は public の `def` を写し、`protected def` は生成エラーにする。`getPublicData` / `deleteRes` も含める（既に JS から呼べる。生成で公開範囲は変わらない）。 |
| `Thread.inspect` の戻り値の弱体化 | 本体の戻り値を `js.Promise[ThreadInspectResult]` に揃えた（`Object.fromEntries` の結果をキャストするだけ。欠落プロパティを含む実体は不変）。 |
| `Board.updateThread` の `sage: js.Any` | 手書き Stub と同じ `js.UndefOr[Boolean]` に揃えた。実行時の判定は `Boolean(sage)` の真偽値のまま。 |
| 省略引数で DO 本体の既定値が RPC でも効くか | `tests/rpc.integration.test.js` で実 workerd 上の `getDat()` / `addRes(dat, log)` / `inspect()` / `updateThread(data)` を省略呼び出しして確認。 |
| 型の解決を構文解析だけで行う危うさ | 使える型を `js.*` / プリミティブ / `io._3ch.bbs.external.cloudflare` の facade に限定し、別名 import・他パッケージ・ローカル宣言はエラー。複数の `*` import がある場合は cloudflare 由来とみなして完全修飾名にするので、誤りは `bbs` のコンパイルエラーとして現れる。 |
| sbt のタスクグラフ | `bbs / Compile / sourceGenerators` → `codegen / Compile / runMain`。codegen は `commonJVM` にのみ依存し、bbs のソースをファイルとして読むだけなので循環しない。`sbt codegen/run` は `mainClass` を固定して従来どおり動く。 |
| 再生成の決定性 | クラスは export 名順、メソッドは名前順。内容が同じなら書き直さない。削除・改名は次のコンパイルで反映（単体テストで確認）。 |
| 公開プロトコル・ストレージ | 変更なし。dat / subject.txt / DO の保存形式に触れていない。 |

## 検証

- `sbt codegen/test`: 14 件成功（TS 型生成 5 件、Scala スタブ生成 9 件）。
- `sbt test`: common JVM / JS、bbsClient、codegen、bbs の合計 194 件すべて成功。
  初回実行では `PostingHandlerSuite` 「成功レスポンスは…meta refreshを含む」が 1 件失敗したが、変更前のコミットでも
  同じ箇所で失敗する既存の不一致（55689ca で遷移先から `/l50` を外した後、edd5155 で取り込んだテストが旧 URL を
  期待していた）だったので、AGENTS.md §3.3 に従い実動作を正としてテストの期待値を別コミットで訂正した。
- `sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile`: 成功（生成ファイルも波括弧構文）。
- `npm run v6:build`: 成功。`sbt codegen/run` → client / server の Vite ビルド。生成スタブは Vite 経由の sbt 呼び出しでも再生成される。
- `npm run v6:typecheck`: 成功。
- `npm test`: 3 件成功（管理画面・公開投稿の統合、KB-27、**新規** `tests/rpc.integration.test.js` の省略引数 RPC）。
  管理操作の拒否テストで想定されたエラーログが出るが、アサーションは成功。
- `git diff --check`: 成功。
- 環境メモ: 本セッションの Maven Central は 429 を返すことがあり、`~/.sbt/repositories` に Google のミラーを
  足して解決した（リポジトリには含めない）。

## サブエージェントレビュー（Claude Code）

2026-11-01。実装を担当したセッションとは別の Claude Code サブエージェント（読み取り専用、ビルド・テスト実行なし）に
差分と関連ソース・検証結果を渡してレビューさせた。秘密値・本番設定は渡していない。

| 指摘 | 重要度 | 判断・対応 |
| --- | --- | --- |
| `protected` メソッドは Scala.js の非 native JS クラスでは JS に露出するのに、生成器は黙って除外し docs は「公開範囲と一致」と断言している | medium | 採用。`protected def` を生成エラーにし、docs の記述を訂正。テストを追加。 |
| `PublicRoutes` に `asInstanceOf[js.Dynamic]` 経由の `getPublicData` 呼び出しが残っている | medium | 採用。生成スタブの `getPublicData` で型付き呼び出しにし、キャストを除去。 |
| クラス本体直下の `extension` / `given` / ネストした公開クラス・オブジェクトを黙って落とす | low | 採用。これらは生成エラーにする（公開 `val` / `var` は JS プロパティだが RPC メソッドではないので対象外と docs に明記）。 |
| `Long` / `Option` / `Seq` 等の scala / Predef 型が `*` import により `io._3ch.bbs.external.cloudflare.Long` に書き換わり、bbs のコンパイルで分かりにくく失敗する | low | 採用。既知の型名を生成器で拒否し、RPC 境界で使えない旨を案内。 |
| backquote 付きの名前（`` `type` ``）が `name.value` 経由で無効な Scala になる | low | 採用。`name.syntax` を識別子検査にかけ、backquote 付きは拒否。 |
| `runMain.toTask` の引数が空白区切りで、パスに空白があると壊れる | low | 採用。引数を引用符で囲む。 |
| `bbs/compile` のたびに codegen の `runMain` が走り JVM 側のコンパイル確認が加わる | low（情報） | 現状維持。循環はなく、内容不変なら書き直さないので Zinc の再コンパイルは起きない（2 回目の `bbs/compile` が 3 秒で no-op なことを確認）。 |
| 継承メソッドが対象外である旨が docs に無い | low | 採用。docs に 1 行追加。 |
| AGENTS.md の `npm test` の説明に `client-bundle.test.js` が無い（以前からの記述漏れ） | low | 採用。併記。 |
| 単体テストの網羅漏れ（単一 `using` リスト、`private[durable]`、`override` / `final`、ネスト object 内の def、`@JSExport`） | low | 採用。ケースを追加。 |
| 統合テストのコメントが未検証の主張を含む。`rpc-worker.mjs` の未使用 export | low | 採用。コメントを実際の検証範囲に合わせ、`maxThreads = -1` の 1 ケースを追加。未使用 export を削除。 |
| 生成 Scaladoc の冗長な括弧、`root` のエラーでメンバー名が空になる | low | 採用。両方修正。 |

## 外部 CLI レビュー

未実施。AGENTS.md §3.2 に基づく外部送信許可待ち。
