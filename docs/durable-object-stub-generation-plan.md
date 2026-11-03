# Durable Object の Scala RPC Stub 自動生成計画

作成日: 2026-10-16。状態: 将来実施する計画。実装・ビルド設定の変更は未実施。

## 目的

DO に RPC メソッドを追加・変更した際、`Bindings.scala` の `BoardDoStub` /
`ThreadDoStub` に同じシグネチャを手書きする作業をなくす。
DO 本体を原本とし、通常のコンパイルで Scala の native facade を生成する。
公開プロトコル、DO の処理、保存形式は変更しない。

## 現状と利用する仕組み

- DO 本体: `bbs/src/main/scala/ch3s/bbs/durable/`。
- 手書き Stub と Namespace: `bbs/src/main/scala/ch3s/bbs/external/cloudflare/Bindings.scala`。
- 既存生成器: `codegen/src/main/scala/ch3s/codegen/GenerateDurableObjectTypes.scala`。
  Scalameta で Scala 3 のソースを解析するが、現在は TS のクラス名とコンストラクタだけを生成する。
- 既存エントリ: `codegen/src/main/scala/ch3s/codegen/GenerateAppFiles.scala`。
  TS 生成の現行仕様は [durable-object-types.md](durable-object-types.md) を参照。

既存の構文解析を再利用し、Scala Stub 用の生成器を追加する。
TS 宣言の生成用途と Scala RPC facade の生成用途は区別する。

## 方針

### 生成対象

`@JSExportTopLevel(..., "server")` を持ち、`DurableObject` を直接継承するクラスを対象とする。
クラス直下の公開インスタンスメソッドから `@js.native trait ...DoStub` を生成し、
本体は `js.native` とする。private / protected メソッド、フィールド、コンストラクタ、
メソッド内のローカル定義は対象にしない。

初期対象は Board、Thread、V6PlaceholderDurableObject。
既存 Stub 名を維持する。Placeholder のような命名例外は生成側で明示してよいが、
メソッド一覧やシグネチャの別管理は導入しない。

公開メソッドを抽出する方針が Cloudflare RPC と Scala.js の出力に合致することを実装時に確認する。
`Thread.getPublicData` と `deleteRes` は現状の手書き Stub にないため、対象に含めるかを確認し、
生成した型によって実行時に新たな公開処理が追加されるわけではないことも検証する。
継承メソッド、overload、型パラメータ、複数引数リスト、可変長引数、`@JSName` 等は
初期対応範囲を明示し、未対応の公開宣言は黙って落とさず生成エラーにする。

### 型とデフォルト引数

引数名・型・戻り値型を DO 本体から取得する。明示的な型を必要とし、
構文解析だけで解決できない型を `js.Dynamic` や `js.Any` に置き換えて通さない。
生成先から参照できる import / 完全修飾名の扱いを定め、別名 import や型エイリアスなどの
未対応構文は対象ファイルとメソッド名付きでエラーにする。

本体のデフォルト式は Stub に複製しない。省略可能な引数を facade では
`js.UndefOr[T] = js.native` として表現する案を検証する。
既に `js.UndefOr[T]` である場合は二重に包まない。
特に `Thread.getDat(bbs: String = "", key: String = "0")` は、
引数省略時にサーバー側のデフォルト値が引き続き適用されることを実 workerd で確認する。

`Thread.inspect` は本体が `js.Promise[js.Dynamic]`、手書き Stub が
`js.Promise[ThreadInspectResult]` であり、単純な転記では型が弱くなる。
本体の戻り値を実際のオブジェクト形状に対応する facade 型へ揃えてから生成へ移す。
境界の型付けだけを変更し、欠落プロパティを含め戻り値の実体は維持する。
結果オブジェクト用の facade は必要に応じて手書きで残す。

### ビルドへの組み込み

`bbs / Compile / sourceGenerators` から生成を実行し、
`Compile / sourceManaged` 配下に出力する。生成 Scala ファイルは Git 管理しない。
`sbt compile` / `sbt test` だけで生成され、事前の `codegen/run` を不要にする。

JVM の codegen は bbs のソースを入力として読むだけとし、bbs のコンパイル成果物に依存させない。
既存の `codegen -> commonJVM` 依存を含め、sbt のタスクグラフが循環しないように配線する。
出力順を固定し、内容が同じなら書き直さず、削除・改名後の古い宣言を残さない。
既存の TS / ルート生成は継続して動くようにする。

### Namespace の型付け

Stub 生成後、`DurableObjectNamespace[S <: DurableObjectStub]` のように
Namespace に型引数を持たせ、`get` が `S` を返す構成へ移す。
`WorkerEnv` の各 binding に対応する生成 Stub 型を指定し、handler / DO / テストの
`asInstanceOf[...DoStub]` を除去する。テスト用 Namespace の実装も追従させる。
DO クラス自体へのキャストで代用しない。

## 実装手順

1. 実装時点の DO 宣言と Stub の差分を確認する。上記の戻り値型・省略引数・公開範囲を確定する。
2. Scalameta による Stub 生成器と、その対応範囲・拒否条件の単体テストを追加する。
3. `sourceGenerators` に組み込み、手書き Stub を削除する。既存名で呼び出し側をコンパイルする。
4. Namespace を型付けし、呼び出し側とテストから不要なキャストを除去する。
5. テスト・ビルド・レビューを実施し、生成仕様と運用手順を docs に反映する。

## 完了条件と検証

- DO に対応範囲内の公開メソッドを追加すると、Stub の手編集なしで型付き呼び出しがコンパイルできる。
- 引数型の変更・メソッド削除が呼び出し側のコンパイルエラーに反映される。
- private 宣言やコメント内の疑似宣言を抽出しない。未対応の公開宣言・重複名は明示的に失敗する。
- メソッドの削除・改名・入力順の変更・連続生成・クリーンビルドでも宣言が正しく再生成される。
- `inspect` の結果型、`getDat` の省略引数、Promise の戻り値が維持される。
- `sbt codegen/test`、`sbt test`、`npm run v6:typecheck`、`npm run v6:build`、
  ビルド後の `npm test` を実施する。省略引数を含む RPC の実 workerd 検証を用意する。
- AGENTS.md §3.2 に従い、セルフレビュー → Codex CLI → Gemini CLI の順でレビューし、
  指摘と採否を docs に記録する。外部 CLI へのコード送信は事前の利用者許可を得てから行う。

## 今回の記録

計画文書のみ追加。実装・テスト実行・外部 CLI レビューは行っていない。
実装時にはその時点のソースと Scala.js / Cloudflare RPC の仕様を再確認する。
