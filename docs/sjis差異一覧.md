# Shift_JIS 変換の差異一覧（実装確定版）

> 2026-09-23、案A（`encoding-japanese` + NEC重複符号の双方向互換マッピング）を実装。
> 本ファイルは `docs/移植方針_step3_ScalaJS.md` R-01 の受け入れ基準に基づく最終結果をまとめる。
> 実装は `bbs/src/main/scala/ch3s/bbs/util/SjisEncoding.scala`、検証は
> `tests/golden/generate-sjis-compat.mjs` / `bbs/src/test/scala/ch3s/bbs/util/SjisEncodingSuite.scala`。
> それ以前の調査過程（一時停止していた頃の記録）は git 履歴を参照。本ファイルはその後の
> 実装確定版で置き換えた。

## 結論

- v5（`src/common.js` の `sjis` クラス）が**正しく変換できていた文字**は、encode / decode とも
  **バイト単位・文字単位で完全一致する**ことを、v5の実測データに対する全数照合で確認した。
- 残る差異は KB-04 / KB-17 / KB-18（意図的な仕様変更）と、Node と本番 Workers(workerd) の
  `TextDecoder('sjis')` 実装差（制御コード3件、R-06 関連）の**2種類のみ**であり、
  いずれも列挙済みかつ根拠を確認済みである。
- 全数照合で「説明のつかない差異（UNEXPECTED）」は **0件**。

## 1. 重複符号の双方向互換マッピング（機械抽出）

CP932 では同じ文字が2箇所にエンコードされる「重複符号」がある。v5 のテーブルと
`encoding-japanese` は採用する側が食い違うため、素のまま置き換えると新規投稿のバイト列が
過去ログと食い違い（encode）、既存 dat 中の `0x87xx` 系バイト列が `?` に化ける（decode）。

### 抽出方法

**9文字を決め打ちにせず**、`tests/golden/generate-sjis-compat.mjs` が
`tests/golden/sjis-table.json`（v5 の実測テーブル、9,027件）と v5 の実際の encode 表
（`src/common.js` の `sjis` インスタンスが持つ `this.e`、全エントリ）を `encoding-japanese` と
全数突き合わせて機械的に抽出した。

### 結果: 11件（10件 + 1件）

**genuine な CP932 重複符号: 10件。** `docs/移植方針_step3_ScalaJS.md` R-01 に載る9文字は
この10件の**部分集合**であり、全件ではなかった（`￢` が抜けていた）。

| 文字 | Unicode | v5 が使うバイト位置 | encoding-japanese 既定のバイト位置 |
| :-: | :-- | :-- | :-- |
| ≒ | U+2252 | `87 90` | `81 E0` |
| ≡ | U+2261 | `87 91` | `81 DF` |
| ∫ | U+222B | `87 92` | `81 E7` |
| √ | U+221A | `87 95` | `81 E3` |
| ⊥ | U+22A5 | `87 96` | `81 DB` |
| ∠ | U+2220 | `87 97` | `81 DA` |
| ∵ | U+2235 | `87 9A` | `81 E6` |
| ∩ | U+2229 | `87 9B` | `81 BF` |
| ∪ | U+222A | `87 9C` | `81 BE` |
| ￢ | U+FFE2 | `EE F9` | `81 CA` |

**追加で見つかった1件（U+FFFD 置換文字の救済）:**

| 文字 | Unicode | v5 が使うバイト位置 | encoding-japanese 既定 |
| :-: | :-- | :-- | :-- |
| （置換文字） | U+FFFD | `EF FC` | `3F`（`?` 1バイト、往復不可） |

これは JIS 規格上の「重複符号」ではなく、v5 のテーブル構築（Node `TextDecoder('sjis')` が
JIS X0208 未定義の第2バイト位置を軒並み Unicode 置換文字へ丸め込む副産物）に由来する。
構築ループの走査順（リードバイト昇順）により、置換文字にマップされる全ポジションのうち
**最後に登録される `0xEF 0xFC` が encode 側の代表として決定的に勝つ**ことを、Node 実測
（`tests/golden/sjis-table.json`）と本番 Workers(workerd) 実測
（`tests/golden/sjis-runtime-differences.json` を適用したパッチ後テーブル）の両方で
再計算し、**一致することを確認済み**（後述の「検証できなかったこと」参照）。
v5 はこの文字を「正しく往復変換できていた」ため、同じ上書き機構で救済対象にした
（`tests/golden/sjis-decode.json` の既存 golden ケース `"input": "�", "output": [239, 252]` とも一致）。

### 実装

`bbs/src/main/scala/ch3s/bbs/util/SjisEncoding.scala` の `dupCodeOverrides`（11件、上表と同一データ、
生成元は `tests/golden/sjis-dup-code-table.json`）を:
- **encode**: 該当文字は `encoding-japanese` を経由せず、v5 のバイト位置を直接出力する。
- **decode**: 該当バイト位置に遭遇したら、`encoding-japanese` を経由せず対応する文字を直接返す
  （genuine な重複符号の場合、`encoding-japanese` 標準側のバイト位置は上書きしておらず、
  そちらは引き続き素の `encoding-japanese` で正しく復元できる＝一方向だけの上書きで足りる）。

## 2. 全数照合の結果

`tests/golden/generate-sjis-compat.mjs` が、本実装と同一のアルゴリズムを JS でミラーし、
以下を全数照合した（`tests/golden/sjis-compat-exceptions.json` に生データ）。

| 照合対象 | 件数 | 差異 | 内訳 |
| :-- | --: | --: | :-- |
| 上書き11ペアの往復（encode/decode） | 11 | 0 | — |
| 上書きペアの隣接文字列（チャンク境界チェック） | 66 (encode) + 11 (decode) | 0 | — |
| v5 実表 `sjis-table.json` の decode 全件 | 9,027 | 1,502 | KB-18 1,499 / 制御コード3 |
| BMP全域 (U+0000–U+FFFF) の encode 往復 | 65,536 | 58,021 | KB-17 58,018 / 制御コード3 |
| 絵文字等補助面サンプルの encode 往復 | 3,106 | 3,106 | KB-17 3,106（全件） |
| decode 2バイト全数スイープ | 12,360 | 4,835 | KB-18 4,780 / KB-04+KB-18 52 / 制御コード3 |

**説明のつかない差異（UNEXPECTED）: 0件。**

## 3. 許容される差異の分類（全て列挙済み）

### KB-17 — v5 が変換できなかった文字（NULバイト → NCRフォールバック）

v5 のテーブルに存在しない Unicode コードポイント（絵文字・補助面文字・孤立サロゲート等）。
v5 は `undefined` → `Uint8Array.from` で **NULバイト1個**になるが、本実装は
**NCR（`&#codepoint;`）へフォールバック**する。

例: `😀`(U+1F600) → v5: `00`(NUL) / 新実装: `26 23 31 32 38 35 31 32 3B`(ASCII `&#128512;`)

BMP全域で 58,018件、補助面サンプルで 3,106件（サンプル全件）該当。

### KB-18 — v5 が例外を投げた/無音で捨てた不正バイト列（フォールバック）

v5 が未知の第2バイトに遭遇すると `TypeError` を送出し、末尾の不完全なリードバイトは無音で
捨てる。本実装は例外を投げず、`encoding-japanese` 既定の代替文字（`?`）にフォールバックする。

- v5実表のdecode不一致 1,499件は、JIS X0208 未定義の第2バイト位置が Node の
  `TextDecoder('sjis')` で Unicode置換文字(U+FFFD) に丸め込まれていた「穴」（上書き対象の
  `0xEF 0xFC` を除く）。新実装は同じ位置を `?` にする。
- decodeの2バイト全数スイープでの不一致 4,780件（うち52件はKB-04と重複、後述）も同じ理由。
- 末尾の不完全なリードバイト（例: `[0x41, 0x82]` の末尾 `0x82`）は、v5は無音で捨てて
  `"A"` を返すが、新実装は `"A?"` を返す（`bbs/src/test/scala/.../SjisEncodingSuite.scala`
  の `sjis-decode.json` ケース `[65, 130, 160, 130]` で実測・固定）。

### KB-04 + KB-18 — IBM拡張行 (リードバイト0xF0–0xFC)

v5のテーブルに一切存在しない行。v5は未知のリードバイトとして `TypeError` を送出するが、
本実装は `encoding-japanese` の標準テーブル経由でこれらを解釈でき、KB-18のフォールバック方針
に従って文字を返す。decodeスイープで52件該当。

### 制御コード 0x1A / 0x1C / 0x7F — Node/workerd 実装差による見かけ上の差異（実差異ではない）

`tests/golden/sjis-table.json` は Node の `TextDecoder('sjis')` で構築したものだが、
`tests/golden/sjis-runtime-differences.json` の実測により、**本番 Workers(workerd) の同API は
この3バイトについてのみ Node と異なる文字を返す**ことが分かっている:

| バイト | Node (`sjis-table.json`) | workerd（本番） |
| :-: | :-: | :-: |
| `0x1A` | U+001C | U+001A（恒等） |
| `0x1C` | U+007F | U+001C（恒等） |
| `0x7F` | U+001A | U+007F（恒等） |

workerd は制御コード域を素通し（恒等変換）しており、これは `encoding-japanese` の既定動作と
**完全に一致する**。つまり、**本番で実際に動いている v5 と新実装の間には、この3バイトについて
実差異が無い**。見かけ上の差異は「Node実測を正としてダンプした `sjis-table.json`」にのみ現れる
（R-06 参照）。

## 4. 検証できなかったこと・不確実な点

- **workerd 上での全数照合はできていない。** 全ての照合はローカル Node.js 上で行った。
  ただし以下は workerd 側の実測データ（`tests/golden/sjis-runtime-differences.json`、
  Node/workerd 間で443件＝制御文字3件・未割当位置440件の差異）を使って個別に確認済み:
  - **重複符号11件（上記マッピング対象）のバイト位置は、443件の差異のいずれとも重複しない**
    （機械的に突き合わせて確認）。したがって本マッピングは workerd 上でも同じ根拠で成立する
    と考えてよい。
  - U+FFFD 救済ペア（`0xEF 0xFC`）についても、Node実測テーブルと「443件をworkerdの値で
    パッチしたテーブル」の両方で、encode時にこの位置が最後に勝つ（construction順で最終的に
    `this.e[U+FFFD]` を上書きする）ことを再計算し、一致を確認した。
  - 制御コード3件は上記の通り、workerdの方が新実装と一致することを確認済み。
- **440件の未割当位置（workerdでは`null`＝テーブルに存在しない）は、workerd上では実際には
  `decode`が`TypeError`を送出する経路になりうる**（v5のロジック上、リードバイト自体が
  `d`に存在しなければ即座に例外送出）。Node実測の「置換文字(U+FFFD)経由でdecodeできる」という
  前提は、この440件については本番workerdでは成立しない可能性がある。ただしこれはいずれにせよ
  KB-18のフォールバック範囲内（v5が例外を投げていた/投げうる入力を、新実装は`?`にフォールバック
  する）に収まるため、受け入れ基準（v5が正しく変換できていた文字の同一性）には影響しない。
- **本番 Cloudflare Workers 実機での実行確認はしていない。** `npm test`（Node上のcharacterization
  テスト、134件）と `sbt test`（Scala.js、Node上で実行）のみで検証した。

## 5. テスト

- `npm test`: 134件 green（`src/` 無変更）。
- `sbt test`: 全件 green。うち `ch3s.bbs.util.SjisEncodingSuite`（4テスト）が本対応の検証を担う:
  1. 重複符号/置換文字の11上書きペアが双方向でv5と一致する。
  2. v5実表9,027件のdecodeが、説明可能な差異（制御コード3件・置換文字の穴1,499件）を除いて一致する
     （それ以外の不一致は即座にテスト失敗になる）。
  3. `sjis-encode.json` 全件が一致する（KB-17ケースはNCRフォールバックへの変換規則で検証）。
  4. `sjis-decode.json` 全件が一致する（KB-18ケースは例外を投げないことと`?`フォールバックで検証）。
