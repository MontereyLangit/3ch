# Step 3 移植方針 — JS → Scala.js + HonoX

対象: v5 バックエンドの Scala 3 / Scala.js 移植。**仕様は step 2 で固定したものから変えない。**
フロントは `3chiko.html` のまま。DO / R2 / KV のスキーマ変更なし。

参照実装: `C:/project/3ch/ch3s-migration`（以下「参照実装」）。

---

## 1. 参照実装から引き継げるもの

調査の結果、参照実装は単なる雛形ではなく**実際に動く部分移植**だった。以下はほぼそのまま流用する。

| 領域 | 内容 |
| :--- | :--- |
| ビルド構成 | `common`（cross JVM/JS, 純粋）/ `bbs`（JS, Workers 本体）/ `bbs-client`（JS, islands）/ `codegen`（JVM）の sbt 構成。client と server を**単一 sbt プロジェクト**に載せ `@JSExportTopLevel(name, moduleID)` の moduleID `"server"` / `"client"` で分ける（sbt の二重起動＝`ServerAlreadyBootingException` 回避） |
| Vite 連携 | `@scala-js/vite-plugin-scalajs` が `scalajs:server.js` / `scalajs:client.js` を仮想モジュールとして解決 |
| ルーティング生成 | `common` の `RouteTable.scala` を唯一の真実とし、`codegen`（JVM）が `app/routes/**`・`app/islands/*`・`_middleware.ts` を生成。**生成先は手編集禁止** |
| Durable Object | `@js.native @JSImport("cloudflare:workers","DurableObject")` のファサードを継承し、サブクラスに `@JSExportTopLevel("...","server")` を付ける。ネイティブ JS クラス継承によりメソッドは個別 `@JSExport` 不要 |
| **バイト応答** | `Uint8Array` をそのままネイティブ `Response` のボディに渡す。Range は `bytes.asInstanceOf[js.Dynamic].slice(start, end+1)`（標準ファサードに `Uint8Array.slice` が無いため）。`Content-Range` / `Content-Length` / `Accept-Ranges` は手で設定 |
| Range / 304 判定 | `common` 側の純粋ロジック（`RangeHeader`, `HttpDate`, `DatDelta`）で 200/206/304/416 を決め、ハンドラは結果に従うだけ |
| 境界型の制約 | `Long` → 10進文字列、`Option` → `js.UndefOr`、`List` → `js.Array`、case class / enum → `js.Dynamic.literal` + 文字列タグ |

## 2. 参照実装に無く、今回作る必要があるもの

| 項目 | 備考 |
| :--- | :--- |
| **MD5 / Unix crypt(3) DES / SHA ヘルパ** | 参照実装にトリップ・認証ハッシュ系は**一切無い**。v5 `common.js` からの移植が必要。移植量・リスクともに最大 |
| **R2 / KV バインディング** | 参照実装の `wrangler.jsonc` は DO と静的アセットのみ。R2 (`ch3www`) と KV (`CAP`) のファサードを追加する |
| **`blockConcurrencyWhile`** | DO ファサードに**未定義**。参照実装は `isLoaded` フラグで代用しているが、v5 の `Thread.addRes` は `blockConcurrencyWhile` による排他が前提なのでファサードメンバを追加する |
| **JS/TS レベルのテスト基盤** | 参照実装には `test` スクリプトも vitest も無く、Wrangler を実際に起動する統合テストも無い。step 2 の差分テスト基盤はこちらで用意する |
| 管理画面 `/admin/*` の全機能 | 参照実装はログイン + スレ一覧まで。残りは移植 |

## 3. 等価性リスク（最重要）

### R-01 Shift_JIS エンコーダの実装差 — **最大のリスク**

参照実装は npm の `encoding-japanese` で Shift_JIS 変換している。
一方 v5 は**自前テーブル**で、次の独自挙動を持つ（[既知不具合台帳](既知不具合台帳.md) KB-04 / KB-17 / KB-18）。

- IBM 拡張行 0xF0–0xFC と絵文字が未対応（`①`・`Ⅲ` 等の NEC 特殊文字は**変換できる**。仕様書の記述は誤り）
- 未対応文字を `encode()` すると **NUL バイト `0x00`** が出力される（KB-17）
- 未対応バイトを `decode()` すると **`TypeError` を送出**する（KB-18）

**`encoding-japanese` をそのまま使うと、v5 より「正しく」変換されてしまい、出力バイト列が一致しない。**
これは KB-04 を意図せず修正してしまうことと同義で、step 3 の目標（バイト単位一致）に反する。

対応方針:
- **v5 の自前テーブル・走査ロジックをそのまま Scala へ移植する。** 変換ライブラリは使わない。
- `encoding-japanese` への置き換えは KB-04 の修正として **step 3 完了後**に行う。
- step 2 で記録する「変換できない文字に対する実際の出力バイト」が、この移植の受け入れ基準になる。

### R-02 ハッシュ実装の差

`sha1Base64` は Shift_JIS バイト列を文字列化したものを**再度 UTF-8 でバイト化**して SHA-1 を取っている
（KB-14）。Scala 側で素直に実装すると値が変わり、既存利用者のトリップ・ID が全て変わる。
**この二重エンコードも含めて忠実に移植する。**

同様に `translateSaltChar` は演算子優先順位のバグでソルト補正が機能しておらず、対象文字に対して
`undefined` を返している（KB-19）。JS では `undefined` が文字列連結で `"undefined"` になるなど
後続の挙動に影響するため、**Scala 側でもこの経路の出力を実測に合わせる**こと。
「正しい変換表」を書いてはいけない。

### R-03 `String.replace` の単発置換

JS の `String.prototype.replace(string, string)` は最初の1件のみ置換する。
Scala の `String.replace` は**全件置換**で意味が異なる。KB-03（`<br>` のスペース付加）をはじめ、
`replace` を使っている箇所は一つずつ挙動を確認し、単発置換が必要な箇所は明示的にそう書く。

### R-04 文字数カウントの単位

v5 の長さ検証は JavaScript の `.length`（UTF-16 コード単位）。Scala の `String.length` も UTF-16
コード単位なので一致するが、`codePointCount` 等に置き換えないこと（サロゲートペアの扱いが変わる）。

### R-05 DO の二重更新

KB-01（`addRes` 内からの Board DO 再更新で強制 sage が打ち消される）は**そのまま移植する**。
「整理したくなる」典型箇所なので注意。

## 4. モジュール対応表（v5 → Scala）

| v5 | 移植先 | 配置 |
| :--- | :--- | :--- |
| `src/common.js`（Shift_JIS, MD5, crypt, SHA） | 純粋関数 → `common`、`Response` 組み立て → `bbs` | cross / JS |
| `src/moderation.js` | `common`（正規表現評価は純粋） | cross |
| `src/durable_objects/board.js` | `bbs` の `BoardDurableObject` | JS |
| `src/durable_objects/thread.js` | `bbs` の `ThreadDurableObject` | JS |
| `src/bbs.cgi.js` | 検証・整形・ID生成の純粋部分 → `common`、I/O → `bbs` のハンドラ | cross / JS |
| `src/main.js` | `RouteTable.scala` + `bbs` のハンドラ群 | cross / JS |
| `src/admin.js` | `bbs` のハンドラ + `_middleware.ts`（生成） | JS |

## 5. 進め方

純粋な層から積み上げ、各層を step 2 のテストで検証してから次へ進む。

1. `common` 相当（Shift_JIS 変換・MD5・crypt(3)・SHA）— 純粋関数のみ。golden 入出力表で照合
2. Durable Objects（`Board`, `Thread`）— `blockConcurrencyWhile` ファサード追加を含む
3. `moderation`
4. `bbs.cgi` 投稿パイプライン
5. `main` ルーティング + HonoX 配線
6. `admin`

各層の完了後にレビューチェーン（セルフ → Codex CLI → Gemini CLI）を回す。

## 6. 受け入れ基準

- 純粋関数: `tests/golden/` の入出力表に対し、JS 版と Scala 版が同一の出力を返す。
- HTTP: v5 と v6 を別ポートの `wrangler dev` で同時起動し、同一リクエスト列に対する
  ステータス・ヘッダ・**ボディのバイト列**が一致する（時刻とスレッドキーのみ正規化）。
- 既知不具合（KB-01〜16）が**再現されている**こと。直っていたら移植の失敗として扱う。
