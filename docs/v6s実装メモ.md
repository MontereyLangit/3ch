# v6s 実装メモ（Scala.js 側の設計背景）

Scala.js 実装（`bbs/`）の doc コメントから移した背景情報。コメント側は「何をするか」だけを書き、
経緯・理由はこの文書の節を参照する（AGENTS.md 3.5）。Shift_JIS 変換は [sjis差異一覧.md](sjis差異一覧.md) 5章。

## 1. エントリポイントと export

### 1.1 `Main`（`bbs/.../Main.scala`）

HonoX のルート（`app/routes` 配下、codegen 生成の TypeScript）から `scalajs:server.js` 経由で
import されるエントリポイント関数群。TS 側はここで export した関数を1行呼ぶだけの薄い配線にする
（参照実装 ch3s-migration の `ch3s.bbs.Main` と同じ方針）。

`extends DurableObject` した DO クラスはクラス自体が JS 向けクラスになるため、公開メソッドが
`@JSExport` なしで素の名前のまま export される。`Main` のような独立関数にはこの仕組みが働かないため
`@JSExportTopLevel` が必要。

### 1.2 `V6PlaceholderDurableObject`

v6s ビルド骨格の動作確認専用 DO で、BBS ロジックは持たない。次の2点を実機（`wrangler dev` ローカル）で
証明するために存在する。

1. Scala.js で定義した `DurableObject` サブクラスが `@JSExportTopLevel(..., "server")` で
   `scalajs:server.js` から export され、`app/server.ts` の named re-export ワークアラウンド
   （`vite.config.ts` の `entryContentAfterHooks`）を経て `wrangler.jsonc` の
   `durable_objects.bindings` から解決できること。
2. 追加したファサードメンバ `DurableObjectState.blockConcurrencyWhile` が実際に排他制御として
   機能すること（`Thread.addRes` の移植がこれに依存する）。

Scala 側のメソッド名（例: `state()`）がそのまま RPC の呼び出し名になる。
これは参照実装 v6-s / ch3s-migration で実測済み。

### 1.3 `HealthHandler`（`GET /_v6/health`）

骨格がリクエストを処理できることを示すだけの固定文字列レスポンスで、BBS ロジックは持たない。
`V6PlaceholderDurableObject.ping()` を RPC 経由で呼んで結果を含めることで、named export ワークアラウンドと
DO バインディングが、一覧登録だけでなく実インスタンス化・実 RPC 呼び出しまで機能することを実機で証明する。

## 2. Cloudflare facade（`external/cloudflare/Bindings.scala`）

ScalablyTyped は使わず、必要なメンバだけを手書きで宣言する（step 3 方針）。参照実装 ch3s-migration の
同名ファイルがベースで、参照実装にない次の2点を追加した
（[移植方針_step3_ScalaJS.md](移植方針_step3_ScalaJS.md) 「2. 参照実装に無く、今回作る必要があるもの」）。

- `blockConcurrencyWhile`: v5 `Thread.addRes` が前提とする排他制御。参照実装は `isLoaded` フラグで
  代用していたが、それでは同時実行時の競合を防げない。
- R2（`ch3www`）/ KV（`CAP`）バインディングの facade。

### 2.1 `R2Bucket.get` の戻り値を `js.UndefOr` にしない理由

実際の R2 は未存在キーに `null` を返す（`undefined` ではない）。`thread.js` の旧形式移行ロジックは
`object !== null` で判定しており、`js.UndefOr`（`undefined` の有無だけを見る）で受けると `null` を
「値あり」と誤認する。そのため null を取りうる生の `R2ObjectBody` として宣言し、呼び出し側で
`!= null` 比較する。

### 2.2 `blockConcurrencyWhile` が必要な理由

v5 `Thread.addRes` は、複数の投稿が同時に届いても「読み込み〜書き込み」が1つの排他区間で実行されることを
前提にしている。参照実装の `isLoaded` フラグだけでは、`storage.get` の await 中に別の呼び出しが割り込み、
初期値を2回読む競合が起こり得る。Workers 組み込みの `blockConcurrencyWhile` はコールバック完了まで同じ
DO インスタンスへの他のリクエスト処理をブロックするので、この競合が起きない。
`V6PlaceholderDurableObject.ensureLoaded` はこれを実機で確かめるための最小例。

### 2.3 `DurableObjectStorage` の複数キー版

実 Storage API は単体キー版と同名のオーバーロードとして次を提供する。

- `get(keys[])` → `Map<string, any>`（存在しないキーは Map に含まれない）
- `put(entries)` → 複数キーを単一トランザクションで書き込む

`thread.js` の `addRes`（`storage.put({dat, log, modifiedAt, ...})`）や `inspect`（`storage.get(['dat','log',...])`）は
この形を前提にしているため、Thread / Board DO の移植で追加した。

### 2.4 `ThreadDoStub`

`thread.js` 自身が兄弟スレッド（旧デュアル DO の移行経路）や移動先スレッドを `THREAD_DO` 越しに呼ぶ箇所で使う。

- `getDat` / `getLog` / `getSettings` は引数なしで呼ぶ箇所があるため、引数を `js.UndefOr` の既定値付きにした。
- `inspect` の戻り値はプレーンオブジェクト（`Object.fromEntries` 相当）なので `ThreadInspectResult` で受ける。

### 2.5 Hono / Fetch API の facade

どちらも参照実装 ch3s-migration の同名ファイルの方針を踏襲し、ScalablyTyped や scalajs-dom などの依存を
増やさず、実際に使うメンバだけを手書きで宣言する。`Response` は Workers / ブラウザ双方のグローバルに
実在するコンストラクタを `@JSGlobal` で直接参照する。Hono facade は骨格段階では `healthHandler` が
使わないメンバも、後続タスクで必要になる分だけ先に宣言してある。

## 3. `WebHash`（`hash/WebHash.scala`）

`src/common.js` の `sha1Base64` / `hash` の移植。

### 3.1 `common` ではなく `bbs` に置いた理由

この2関数は Web Crypto（`crypto.subtle.digest`）に依存する非同期処理で、`common`（cross project、
純粋関数のみ・I/O 禁止）には置けない。JVM 側に Web Crypto は無く、`java.security.MessageDigest` で
代替すると「プラットフォームごとに別の実装」になり、「本番コードは実際に動く一本の実装」という
step 3 方針から外れる。

### 3.2 KB-14 の二重エンコードを保持する

`sha1Base64` は内部で `new TextEncoder().encode(str)` を使う。呼び出し元が Shift_JIS バイト列を
文字列化したものを渡すと、それを再度 UTF-8 としてバイト化してから SHA-1 を取る「二重エンコード」になる。
本家 2ch と異なる 3ch 独自のトリップ値を生む既知の挙動なので、元の JS と同じく無条件に `TextEncoder` へ
委譲して保持する（「正しい」変換に直してはいけない。[移植方針_step3_ScalaJS.md](移植方針_step3_ScalaJS.md) R-02）。

### 3.3 MD5 / crypt(3) は npm ライブラリに委譲する（`hash/Md5.scala`, `hash/UnixCrypt.scala`）

2026-11-01 に、`common/` にあった自作の MD5（`Md5.scala`）と Unix crypt(3) DES（`UnixCrypt.scala`）を
npm ライブラリへの薄い facade に置き換えた。自作実装は v5 の JS をビット演算まで 1:1 で写したもので、
保守の対象として残す価値が無い。

- MD5: `@noble/hashes`（`@noble/hashes/legacy.js` の `md5`, `utils.js` の `bytesToHex`）。
  v2 系は文字列入力を受け付けないので、v5 と同じく `TextEncoder` で UTF-8 化してから渡す
  （孤立サロゲートが U+FFFD になる挙動はこれで保たれる）。
- crypt(3): `unix-crypt-td-js`（Tim Dumol 版）。v5 の `src/common.js` に同梱されていたのは同じ実装なので、
  KB-26（2文字未満の salt で NUL が混じる）を含めて出力は同一。**2026-11-02 のトリップ廃止で削除した**
  （`hash/UnixCrypt.scala`・`hash/Hex.scala`・golden `crypt.json` / `crypt-bytes.json` も削除。[トリップ・ID廃止とスレ主表示.md](トリップ・ID廃止とスレ主表示.md)）。
- 置き場所は `common/` ではなく `bbs/`: npm モジュールは Scala.js からしか呼べず、cross project の JVM 側では
  リンクできない。`bbs/` の他の `@JSImport` facade（`react-dom/server` 等）と同じ扱い。
- 検証: `tests/golden/md5.json` の全ケースが一致することを `bbs/src/test/.../hash/Md5Suite` で固定している（`common` から移した）。

同日、残っていた `common/` の補助実装も置き換えた。

- `text/Utf8.scala`（`TextEncoder` の手書き再現）→ 各呼び出し元（`AdminHandler` / `Responses` / `Board`）で
  `bbs/external/web` の `TextEncoder` facade を直接使う。`common` に置いていた理由は JVM 側でも動かすためだったが、
  利用者は全て `bbs/` だった。
- `hash/HexToBinary.scala`（16進 → 1バイト1文字の文字列）→ `hash/Hex.scala`（`@noble/hashes` の `hexToBytes`。トリップ廃止で後に削除）。
  `unix-crypt-td-js` は鍵をバイト配列でも受け取るので、`UnixCrypt.crypt(Uint8Array, String)` に直接渡す。
  旧実装の「奇数長は末尾を切り捨てる」挙動は、呼び出し元が正規表現で 16 桁を保証しているため到達しない。
  `tests/golden/hex-to-binary.json` はこの不到達の挙動だけを固定していたので削除した（`v5-final` タグに残る）。
- `crypt/SaltChar.scala`（KB-19 の `translateSaltChar` 再現）→ 削除。v6 の `PostingHandler.processTrip` は
  これを呼ばず、JS `RegExp` と文字対応表でソルトを作っているため本番では未使用だった。
  `tests/golden/translate-salt-char.json` も同時に削除した（v5 の実測値は `v5-final` タグで参照できる）。

## 4. テストスイート（`bbs/src/test/`）

### 4.1 `DurableTestKit`

`tests/thread.test.js` の `state()` / `setup()` の Scala 移植。本番の `Thread` / `Board` のコードは変えず、
`DurableObjectState.storage` / `blockConcurrencyWhile` / `THREAD_DO` / `BOARD_DO` / `ch3www`(R2) の
最小実装だけを与えて実際の DO メソッドを動かす。

値を `structuredClone` で複製するのも JS 版と同じ理由で、呼び出し側が受け取ったオブジェクトを書き換えても
ストレージ内部の値を汚さないため（`inspect()` が返す `Map` はテストから直接読むことがある）。

### 4.2 `ThreadSuite`

`tests/thread.test.js` は差分テストの replay ハーネス経由でしか Scala.js 版を検証していない。
本スイートはネイティブ munit で次の角度から補う。

- dat / log / settings / subjectList を「含む」ではなく**完全一致**で確認する（replay ハーネスは元の JS
  アサーション、主に `assert.match` の正規表現や `before` との同値比較をそのまま使っている）。
- replay ハーネスが踏まないコーナーを追加する: 999→1000件目の境界、stop/resume の settings 文字列の
  組み立て、editRes / deleteRes のフィールド単位のエスケープ、standalone `deleteRes(Int)` の
  `resNum < lines.length` 境界。

### 4.3 `BoardSuite`

`tests/main.characterization.test.js` は `updateThread` を新規スレ1件の追加経路でしか使っておらず
（KB-05 / KB-06 のヘッダ検証が主目的）、次を踏んでいない。本スイートはこれらを subjectList の完全一致で固定する。

- 既存スレへの sage（その場で更新）/ age（先頭へ移動）、新規スレは常に先頭
- タイトル省略時の既存行からの抽出
- `maxThreads` による件数打ち切り、負の `maxThreads` での `Array.slice(0, negative)` の巻き戻り
- 512KB のバイト上限

### 4.4 `WebHashSuite`

golden JSON の読み込みに `common` のテスト専用ユーティリティ（`ch3s.common.golden.{Json, GoldenFile}`）を
`"test->test"` 依存で再利用している（`build.sbt` の `bbs` プロジェクト設定）。

### 4.5 `SjisEncodingSuite`

[移植方針_step3_ScalaJS.md](移植方針_step3_ScalaJS.md) R-01 の受け入れ基準を検証する。検証は2種類。

1. `tests/golden/sjis-table.json`（v5 実表 9,027件）・`sjis-encode.json`・`sjis-decode.json` との突き合わせ。
2. golden データからゼロから再構築した参照実装（`referenceEncode` / `referenceDecode`。`SjisEncoding` 本体は
   呼ばない）との、BMP 全域の encode 往復と1/2バイト全数の decode スイープ。テーブル構築・状態遷移そのものの
   バグを独立実装との比較で検出する。

参照 encode 表（`refEncodeTable`）は golden の並び順（単バイト昇順 → 2バイトはリードバイト昇順・第2バイト昇順。
`sjis-table.json` の `note` に明記された構築順）で走査し、後から出たエントリで上書きする。
同じ文字に複数のバイト列が対応する場合、JS の `this.e[_] = w` と同じ「後勝ち」になり、CP932 重複符号の
採用側はこれで決まる。
