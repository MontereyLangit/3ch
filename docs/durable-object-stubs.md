# Durable Object の Scala RPC スタブ生成

`bbs` のコンパイル（`sbt compile` / `sbt test` / `npm run v6:build`）のたびに、
`bbs / Compile / sourceGenerators` が DO クラスの公開メソッドから `@js.native` の RPC スタブ
（`BoardDoStub` / `ThreadDoStub`）を生成する。
計画は [durable-object-stub-generation-plan.md](durable-object-stub-generation-plan.md)。

- 生成器: `codegen/src/main/scala/io/_3ch/codegen/GenerateDurableObjectStubs.scala`
  （入口 `GenerateDurableObjectStubsMain`。`build.sbt` の `bbs` 設定から `codegen / Compile / runMain` で呼ぶ）。
- 入力: `bbs/src/main/scala/io/_3ch/bbs/durable/` 配下の `.scala`（Scalameta の Scala 3 パーサで構文解析）。
- 出力: `bbs/target/scala-*/src_managed/main/io/_3ch/bbs/external/cloudflare/DurableObjectStubs.scala`。
  Git 管理しない。手編集しない。内容が同じなら書き直さない（Zinc の再コンパイルを避ける）。
- 利用側: `WorkerEnv` の `THREAD_DO: DurableObjectNamespace[ThreadDoStub]` 等（`Bindings.scala`）。
  `get` が型付きスタブを返すので、呼び出し側の `asInstanceOf[...DoStub]` は不要。
- `ThreadInspectResult` のような結果オブジェクトの facade は `Bindings.scala` に手書きのまま残す。
- `npm run v6:generate`（`sbt codegen/run`）はルートと TS 型の生成のみ。Scala スタブは生成しない。

## 生成仕様

- 対象クラス: `@JSExportTopLevel("名前", "server")` を持ち `DurableObject` を直接継承するクラス
  （TS 型生成 [durable-object-types.md](durable-object-types.md) と同じ抽出）。
- スタブ名: export 名の末尾 `DurableObject` を除いて `DoStub` を付ける
  （`Board` → `BoardDoStub`。`V6PlaceholderDurableObject` のように `DurableObject` で終わる名前は末尾を除く）。
- 対象メンバー: クラス本体直下の `private` でない `def`。
  フィールド（`val` / `var`）、コンストラクタ、メソッド内のローカル定義、コメント・文字列内の疑似宣言は対象外。
  継承メンバーは走査しない（DO は `DurableObject` を直接継承し、親に Scala のメソッドを持たない前提）。
  Scala.js の非 native JS クラスでは `private` でないメンバーが JS から見える（`protected` も見える）ので、
  `protected def` は「JS から呼べるのにスタブに現れない」罠にならないよう生成エラーにする。
  公開 `val` / `var` は JS プロパティとして見えるが RPC メソッドではないのでスタブには含めない。
  生成によって DO の公開範囲は変わらない。
- 出力順: クラスは export 名順、メソッドは名前順。入力ファイルの順序やメソッドの並び替えに依存しない。
- 戻り値: `js.Promise[T]` のみ対応し、そのまま写す。
- 引数: 名前と型を写す。デフォルト式は複製せず、省略可能な引数は `js.UndefOr[T] = js.native` にする。
  元の型が既に `js.UndefOr[...]` なら二重に包まない。省略時は DO 本体の既定値が RPC でも適用される
  （`tests/rpc.integration.test.js` で実 workerd 上で確認）。
- 型の解決（生成先から参照できる形に写す。構文解析だけで解決できない型を `js.Any` 等に置き換えない）:
  - `String` / `Boolean` / `Int` / `Double` / `Unit`: そのまま。
  - `js.X`（`import scala.scalajs.js` が必要）、`scala.scalajs.js.X`: そのまま。
  - 単純名の facade 型（`ThreadInspectResult` 等）: その DO ファイルの `import io._3ch.bbs.external.cloudflare.*`
    または `{...}` で取り込まれていることを確認し、完全修飾名で出力する。
  - `io._3ch.bbs.external.cloudflare.X` の完全修飾名: そのまま。
  - import は DO ファイル内の全 `import` 文を見る。

## 生成エラーになるもの

黙って落とさず、ファイル名とメンバー名付きで失敗する（ビルドが止まる）。

- 同名メソッド（overload）、型パラメータ、複数引数リスト、`implicit` / `using`、可変長引数、by-name 引数。
- `protected def`、`@JSName` 等の annotation 付きメソッド・引数、`inline` / `implicit` / `transparent` 修飾子、抽象メソッド。
- クラス本体直下の `extension` / `given`、`private` でないネストしたクラス・トレイト・オブジェクト・enum・型エイリアス。
- backquote 付きの名前（`` def `type`() `` 等）。
- 括弧なしの `def foo: T`（JS プロパティになり RPC メソッドではない）。
- 戻り値型の省略、`js.Promise[T]` 以外の戻り値型（`Future` 等）、引数型の省略。
- 別名 import（`{X => Y}`）で来た型、`io._3ch.bbs.external.cloudflare` 以外のパッケージの型、
  DO ソース内で宣言された型・型エイリアス、関数型・タプル型など。
- `Long` / `Float` / `Option` / `Seq` / `Array` / `Future` など scala / Predef 由来で RPC 境界に向かない型。
- export 名の重複。

## 運用手順

- DO に RPC メソッドを追加・変更・削除したら、そのまま `sbt compile` する。スタブは自動で追随し、
  引数型の変更やメソッド削除は呼び出し側のコンパイルエラーとして現れる。
- 上記の対応範囲外のシグネチャが必要になったら、生成器と本書を更新する（スタブを手書きで足さない）。
- 生成器の単体テスト: `sbt codegen/test`（`GenerateDurableObjectStubsSuite`）。
- 省略引数 RPC の実 workerd 検証: `npm run v6:build` 後の `npm test`（`tests/rpc.integration.test.js`。
  `tests/support/rpc-worker.mjs` が `dist/index.js` の DO クラスを束ね、テスト専用の `wrangler.rpc.jsonc` で起動する）。
