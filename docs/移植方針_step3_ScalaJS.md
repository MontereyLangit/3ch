# Step 3 移植方針 — JS → Scala.js + HonoX

対象: v5 バックエンドの Scala 3 / Scala.js 移植。**仕様は step 2 で固定したものから変えない。**
フロントは `3chiko.html` のまま。DO / R2 / KV のスキーマ変更なし。

> **完了（2026-10-02）:** 移植と本番切替は完了。v5 の `src/` と v5 との差分テストは廃止した（v5 はタグ `v5-final`）。
> 以下は移植時の方針記録で、`src/*.js` / `tests/golden/http-differential.test.mjs` への言及はタグ上のもの、または廃止済み。

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

### R-01 Shift_JIS エンコーダ — **最終決定: v5と同じ構築手順（`TextDecoder`ランタイム構築）+ KB-17/KB-18の2件のみ意図的差異（2026-09-23、案Aを破棄して再確定）**

> **これが現在の確定方針。** 直前の「案A: `encoding-japanese` + NEC重複符号の双方向互換
> マッピング」は、その後の実測により前提が崩れたため**破棄**した。実装は
> `bbs/src/main/scala/ch3s/bbs/util/SjisEncoding.scala`、検証は
> `bbs/src/test/scala/ch3s/bbs/util/SjisEncodingSuite.scala`、
> 差異の集計は [sjis差異一覧.md](sjis差異一覧.md) 参照。

#### 決定内容

`src/common.js` の `sjis` クラスのコンストラクタ（`new TextDecoder('sjis')` を同じ範囲・
同じ順序・同じ「単一文字判定」で走査してテーブルを構築する処理）を**逐語的に再現する**。
実行環境が持つ実際のShift_JIS実装に由来する点も含めてv5と完全に同一のテーブルになる。
v5は呼び出しごとに `new sjis()` していたが、本実装は初回参照時に一度だけ構築し以後再利用する。

その上で、以下**2件のみ**を意図的な差異として層を重ねる:

- **KB-17（encode）**: テーブルに存在しない文字（IBM拡張行・絵文字等）は、v5のNULバイト
  `0x00`の代わりに数値文字参照（NCR、`&#codepoint;`）へフォールバックする。
- **KB-18（decode）**: v5が`TypeError`を送出する箇所・無音で捨てる箇所は、例外を投げずに
  置換文字`?`へフォールバックする。

これ以外は全てv5とバイト単位・文字単位で完全に一致する。ライブラリ由来の互換マッピングは
不要（v5自身のテーブルを直接使うため、そもそもズレる余地がない）。

#### なぜ案A（`encoding-japanese`）を破棄したか

案Aを実装・運用した後の実測で、採用の根拠だった3つの前提がいずれも崩れた。

1. **「v5のテーブルは不完全」（KB-04）は仕様調査の誤りが大半だった。** v5のテーブルは
   NEC特殊文字行（`0x87xx`、`①`・`Ⅲ`等）やNEC選定IBM拡張（`0xED40`→`纊`）を**正しく**
   変換できる。実際に無いのはIBM拡張行 `0xF0`–`0xFC` と絵文字のみ。
2. **「ランタイム構築はNodeとworkerdで表が違うから危険」（R-06）は実測で否定された。**
   Node/workerd間で443箇所（制御文字3件・未割当位置440件）差異はあるが、
   v5-on-workerdとv6-on-workerdを実機`wrangler dev`上で直接突き合わせた結果、
   **説明のつかない差異は0件**（`tests/golden/http-differential.test.mjs` PART 1、
   `tests/golden/sjis-runtime-differences.json`）。ランタイム由来のリスクは杞憂だった。
3. **`encoding-japanese`は独自の固定テーブルを持ち、v5とは重複符号の採用側が食い違う**
   （`≒`をv5は`0x8790`、`encoding-japanese`は`0x81E0`でエンコードし、`0x8790`を単体では
   デコードできず`?`に化ける）。これを補うために**11件の手動互換マッピングが必要**だった
   （下記「検討過程の記録」参照）。v5互換を保つために別ライブラリの語彙を後から補正する、
   という構図そのものが本末転倒だった。

加えて、素の`encoding-japanese`呼び出しはテーブル参照より2.8〜5.4倍遅く（最適化後）、
かつプロジェクトに存在しなかったランタイム依存を1つ増やす。「v5互換を保つために苦労して
ライブラリを飼い慣らす」より「v5と同じ手順でテーブルを作る」方が単純かつ高速だった。

#### 受け入れ基準

- **v5 が正しく変換できていた文字は、encode / decode ともバイト単位・文字単位で完全一致すること。**
  ここが崩れたら移植の失敗。
- 差異が許されるのは **KB-17 / KB-18 の2件のみ**。それ以外の差異が1件でも見つかれば
  テストが落ちる（`SjisEncodingSuite`、明示的なデータとして持つ）。
- 性能はv5と同等以上（`npm run v6:bench:sjis` で計測。テーブルを一度だけ構築するため、
  実測ではv5より高速）。

#### 副次的効果

**R-06（Node と workerd で表が異なる問題）は、上記2.の実測により解消済みとみなす。**
v5・v6の両方が実行環境の`TextDecoder('sjis')`を直接叩く以上、Node/workerd間の443件の差異は
両者に**同じように**影響するため、v5とv6の相対的な一致には影響しない。

---

以下は検討過程の記録であり、現在の受け入れ基準ではない（2026-09-23、案Aとして一度実装され、
その後この節の内容へ差し戻された）。

> **この項は当初「自前テーブルを忠実に移植する」としていたが、利用者の指示により方針を変更した。**
> Shift_JIS 変換は移植と同時に `encoding-japanese`（参照実装が採用している npm パッケージ）へ移行する。

v5 は**自前テーブル**で、次の独自挙動を持つ（[既知不具合台帳](既知不具合台帳.md) KB-04 / KB-17 / KB-18）。

- IBM 拡張行 0xF0–0xFC と絵文字が未対応（`①`・`Ⅲ` 等の NEC 特殊文字は**変換できる**）
- 未対応文字を `encode()` すると **NUL バイト `0x00`** が出力される（KB-17）
- 未対応バイトを `decode()` すると **`TypeError` を送出**する（KB-18）

`encoding-japanese` に移行すると、これら3件は**移植と同時に解消される**。
未対応文字は NCR（数値文字参照）にフォールバックする — これは KB-17 で我々自身が定めた対応方針と一致する。

#### この決定が等価性検証に与える影響

**「バイト単位で完全一致」は Shift_JIS 変換に関してのみ成立しなくなる。** 受け入れ基準を次のように改める。

- v5 の表が**正しく変換できていた文字**（通常の日本語・ASCII・半角カナ・NEC 特殊文字など大多数）は、
  移行後も**バイト単位で一致しなければならない**。ここが崩れたら移植の失敗。
- v5 が**変換できなかった文字**（0xF0–0xFC 行・絵文字）と**不正バイトの decode**のみ、
  意図的な差異として許容する。
- **差異の集合を曖昧なまま残さない。** `tests/golden/sjis-table.json`（v5 の実表 9,027 件）を使い、
  `encoding-japanese` と突き合わせて**差異が出る入力を全件列挙し、文書化する**。
  「たぶんここだけ違う」ではなく、確定した有限のリストとして持つ。
- 差分テスト（v5 と v6 の HTTP レスポンス比較）では、この列挙済みの差異のみを既知の除外として扱う。

#### 副次的な効果

R-06（変換表が `TextDecoder` 由来でランタイム依存）は、**この移行により解消する**。
`encoding-japanese` は自前の固定テーブルを持つため、Node と Workers で表が変わる余地がない。

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

### R-06 Shift_JIS 変換表の出自 — **実測により解消（懸念は杞憂だった）**

**2026-09-23、最終決定。** R-01の通り、Scala側もv5と同じ`new TextDecoder('sjis')`を実行時に
叩いてテーブルを構築する（案Aで一時採用した固定テーブル方式は破棄）。「ランタイム由来だと
Node と workerd で表が変わって危険」という当初の懸念は、実機`wrangler dev`（workerd）上で
v5-on-workerdとv6-on-workerdを直接突き合わせる検証（`tests/golden/http-differential.test.mjs`
PART 1）により**否定された**: 両者は同じ実行環境の`TextDecoder('sjis')`を同じ手順で叩くため、
Node/workerd間の443件の差異（制御文字3件・未割当位置440件、`tests/golden/sjis-runtime-differences.json`）が
存在しても、v5とv6の間には**説明のつかない差異が0件**。「ランタイム依存を残すと不確実になる」
という設計判断そのものが誤りだった。

> 以下は経緯の記録として残す。

v5 の `sjis` クラスは、コンストラクタ内で **`TextDecoder('sjis')` を走らせて変換表を構築している**。
つまり変換表の中身は「JS のコードが定義したもの」ではなく「**実行環境が持つ Shift_JIS 実装**」に由来する。

Scala 移植では、この表を実行時に再構築するのではなく、**Node 上で実際に構築された表をダンプして
`tests/golden/sjis-table.json`（9,027 件）に固定し、Scala 側へ埋め込む**方式を採った。
JVM には同等の `TextDecoder` が無く、ホストの ICU 実装に依存すると移植先で表が変わりうるため。

**残るリスク**: ダンプ元は Node の `TextDecoder`。本番の **Cloudflare Workers ランタイムの
`TextDecoder('sjis')` が Node と異なる表を返す場合、v5 の本番実動作と移植版がずれる。**
同じ理由で、step 2 のキャラクタリゼーションテスト（Node 上で実行）にも同じ前提が入っている。

対応: Workers 上で実際に表をダンプして Node 版と突き合わせる検証を、差分テスト基盤の構築時に行う。
一致すれば本項はクローズ。不一致なら Workers 側の表を正とする。

### R-04 文字数カウントの単位

v5 の長さ検証は JavaScript の `.length`（UTF-16 コード単位）。Scala の `String.length` も UTF-16
コード単位なので一致するが、`codePointCount` 等に置き換えないこと（サロゲートペアの扱いが変わる）。

### R-07 `hexToBinary` の不正入力時の差異 — **既知の未再現**

JS の `hexToBinary` は 16進以外の文字を渡されると `parseInt` が `NaN` を返し、
`String.fromCharCode(NaN)` が `\x00` になるという経路でフォールバックする。
**Scala 版はこの挙動を再現していない。**

現状この経路は呼び出し元から到達しない（入力は常に正規表現で 16進 16 文字に限定されている）ため
実害はないと判断しているが、**意図的に残した差異**として記録する。
呼び出し元を移植する際に到達可能性が変わらないか確認すること。到達しうるなら再現が必要。

### R-05 DO の二重更新

**2026-09-23 訂正:** 現行ソースには二重更新がない。`Thread.addRes` が計算済み `posting.sage` で Board を1回だけ更新する。この動作を移植し、以下の古い説明を再導入しない（KB-01訂正参照）。

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
