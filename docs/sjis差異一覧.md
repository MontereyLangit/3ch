# Shift_JIS 変換の差異一覧（実装確定版・2026-09-23 最終決定）

> **注（2026-10-02）:** v5 との差分テスト（`http-differential.test.mjs`）と v5 の `src/` は廃止済み（タグ `v5-final`）。以下の検証結果は移植時点の記録。

> 2026-09-23、「案A: `encoding-japanese` + NEC重複符号の双方向互換マッピング」を破棄し、
> 「v5と同じ構築手順（`TextDecoder`ランタイム構築）+ KB-17/KB-18の2件のみ意図的差異」へ
> 差し戻した。本ファイルはこの最終実装に基づく差異一覧で、案A時代の版を完全に置き換える。
> 実装は `bbs/src/main/scala/ch3s/bbs/util/SjisEncoding.scala`、検証は
> `bbs/src/test/scala/ch3s/bbs/util/SjisEncodingSuite.scala`。
> 案A時代の記録（NEC重複符号11件の機械抽出等）は git 履歴
> （`fc42b56` 〜 `b9ea910` の間のこのファイルの版）を参照。

## 結論

- v5（`src/common.js` の `sjis` クラス）と**同じ構築手順**（`new TextDecoder('sjis')` を
  同じ範囲・同じ順序・同じ「単一文字判定」で走査）でテーブルを作るため、v5が正しく
  変換できていた文字は encode / decode ともバイト単位・文字単位で**完全に一致する**
  （重複符号の互換マッピングのような後付けの補正は一切不要）。
- 許容される差異は **KB-17（encode）と KB-18（decode）の2件のみ**。それ以外の差異が
  1件でもあれば `SjisEncodingSuite` が検知して失敗する。
- 実測で確認した範囲（後述）に「説明のつかない差異（UNEXPECTED）」は **0件**。

## 1. なぜ重複符号の互換マッピングが不要になったか

案A（`encoding-japanese`採用）では、v5とは独立の固定テーブルを使うライブラリを使ったため、
CP932重複符号（同じ文字が2箇所にエンコードされる）の採用側が食い違い、11件の手動互換
マッピングが必要だった。

本実装はv5自身のテーブル構築手順をそのまま再現するため、この問題が**構造的に発生しない**。
v5が`≒`を`0x8790`で符号化するなら、本実装も同じ手順・同じ走査順で構築した結果、
必然的に`0x8790`を選ぶ。「別ライブラリの語彙を借りて後から補正する」必要がそもそも無い。

## 2. 検証結果

### 2.1 golden データとの突き合わせ（`SjisEncodingSuite`）

| 検証内容 | 件数 | 結果 |
| :-- | --: | :-- |
| `tests/golden/sjis-table.json`（v5実表）の decode 全件 | 9,027 | **完全一致（0件差異）** |
| `tests/golden/sjis-encode.json` 全ケース（KB-17を含む） | 全件 | 一致（KB-17ケースはNCR規則で一致） |
| `tests/golden/sjis-decode.json` 全ケース（KB-18を含む） | 全件 | 一致（KB-18ケースは`?`フォールバック規則で一致、例外なし） |
| golden データのみから独立に構築した参照実装との BMP全域(U+0000-U+FFFF) encode 突き合わせ | 65,536 | **完全一致** |
| 同、1/2バイト全数(256 + 65,536) decode 突き合わせ | 65,792 | **完全一致** |
| 絵文字・補助面・孤立サロゲートの encode(KB-17) | 6 + 2 | 一致（全てNCR） |

`sjis-table.json`（9,027件）との decode 完全一致は、案A時代には「Node実測の`sjis-table.json`と
本実装の実行環境(Node)のズレ」を懸念して制御コード3件を例外として許容していたが、
実際に実行してみると**その3件も含めて完全一致した**（本実装がv5と同じ`TextDecoder('sjis')`を
同じNode上で叩くため、当然の結果）。本番(workerd)上での一致は下記2.2で別途確認している。

### 2.2 実機 `wrangler dev`（workerd）上の直接突き合わせ（R-06の閉鎖）

`tests/golden/http-differential.test.mjs` PART 1 が、v5-on-workerdとv6-on-workerdを
実機`wrangler dev`（本物の`workerd`、Node上のポリフィルではない）上で直接突き合わせる:

- BMP全域(65,536件)の encode 往復
- 1バイト全数(256件) / 2バイトの有効リード全域の decode
- v5-on-workerd と Node実測の `sjis-table.json` の差異が、記録済みの443件
  （`tests/golden/sjis-runtime-differences.json`）と厳密に一致すること

3回連続で実行し、**いずれも `unexpectedEncode` / `unexpectedDecodeSingle` /
`unexpectedDecodePairs` が0件**（`.test-state/http-differential/sjis-real-workerd-diagnostics.json`）。
Node/workerd間の443件の差異は、v5とv6の**両方に同じように**影響するため、
v5とv6の相対的な一致には影響しない。

## 3. 許容される差異の分類（2件のみ）

### KB-17 — v5が変換できなかった文字（NULバイト → NCRフォールバック）

v5のテーブルに存在しないUnicodeコードポイント（絵文字・補助面文字・孤立サロゲート・IBM拡張行
`0xF0`-`0xFC`に対応する文字等）。v5は`undefined` → `Uint8Array.from`で**NULバイト1個**になるが、
本実装は**NCR（`&#codepoint;`）へフォールバック**する。

例: `😀`(U+1F600) → v5: `00`(NUL) / 新実装: `26 23 31 32 38 35 31 32 3B`(ASCII `&#128512;`)

### KB-18 — v5が例外を投げた/無音で捨てた不正バイト列（`?`へフォールバック）

v5の状態遷移で以下に該当する箇所:
- 未知のリードバイト（IBM拡張行`0xF0`-`0xFC`を含む、テーブルに一切存在しないバイト） →
  v5は`TypeError`を送出。本実装は`?`を1文字追加し、そのバイトだけを消費する。
- 既知のリードバイトに対する未知の第2バイト → v5は`TypeError`を送出。本実装は`?`を1文字
  追加し、リードバイト・第2バイトの両方を消費する。
- 末尾に不完全な（リードバイトだけの）データが残る → v5は無音で捨てる（例外を投げず、
  その文字を単に出力しない）。本実装はv5が実際に変換できた部分は完全に温存し、末尾の
  捨てられる位置だけを`?`で埋める（`SjisEncodingSuite`の`sjis-decode.json`ケース
  `[65, 130, 160, 130]`: v5は`"Aあ"`、新実装は`"Aあ?"`）。

置換文字`?`は、破棄した`encoding-japanese`ベース実装が実際に使っていたフォールバック文字を
継承したもの（ダウンストリームの出力を変えないための選択）。

**判断が必要だった点（1件）**: IBM拡張行`0xF0`-`0xFC`のリードバイトに続く、そのリードバイトの
有効な第2バイトではないバイト（例: `[0xFA, 0x40]`）に対する新実装の出力は、v5自身の状態遷移
規則をそのまま適用すると「リードバイト単体で`?`、続くバイトを独立して再処理」という2文字の
結果になる（`"?@"` 等）。これに対し、破棄した`encoding-japanese`ベース実装は、その内部実装が
`0xF0`-`0xFC`も2バイトリードとして認識してしまうため、2バイトをまとめて1個の`?`に収める
挙動だった。この特定の組み合わせ（v5には存在しないIBM拡張行のリードバイト + そのリードバイト
にとって無効な第2バイト）は、そもそもKB-04（IBM拡張行自体の非対応）が絡む入力であり、
`encoding-japanese`を撤去した以上その挙動を模倣する意味は無いと判断し、v5自身の状態遷移
規則をそのまま適用する（1バイトずつ独立に判定する）ことを選んだ。実運用では専ブラが送信する
正常なShift_JISバイト列にこの組み合わせは現れない。

## 4. テスト

- `npm test`: 134件 green（`src/` 無変更）。
- `sbt test`: 全件 green。うち `ch3s.bbs.util.SjisEncodingSuite`（6テスト）が本対応の検証を担う。
- `npm run v6:test:characterization`: 99件 green。
- `npm run v6:test:http`: 実`wrangler dev`（workerd）上の差分テスト。3回連続実行して green
  （Shift_JIS全数スイープの`unexpected*`は毎回0件）。
- `npm run v6:bench:sjis`: v5比で encode 0.2〜0.9倍（=同等〜大幅高速）、decode 0.2〜1.3倍
  （テーブルを一度だけ構築するため、大半のケースでv5より高速）。v5/v6は全サイズでバイト
  単位完全一致。

## 5. 実装メモ（`SjisEncoding.scala`）

### 5.1 テーブル構築（`buildTables`）

`src/common.js` の `sjis` クラスのコンストラクタを逐語的に再現している。

```js
((td = new TextDecoder('sjis'), f = w =>
  (_ => _.length == 1 &&
    (this.e[_] = w).reduce((a, x, i, { length: l }) =>
      (i == l - 1 ? a[x] = _ : a[x] ||= {}), this.d)
  )(td.decode(new Uint8Array(w)))
) => [...Array(238)].forEach((_, i) =>
  i < 191
    ? f([i + (i < 128 ? 0 : 0xa1 - 128)])
    : [...Array(188)].forEach((_, j) =>
      f([i - 191 + (i - 191 < 31 ? 0x81 : 0xe0 - 31),
        j + 0x40 + (62 < j ? 1 : 0)]))
))();
```

- `i < 191`: 単バイト側。`0x00-0x7F` と `0xA1-0xDF`（半角カナ）を走査する。
- それ以外（47 通り）: 2バイト側。リードバイトは `0x81-0x9F`（31通り）と `0xE0-0xEF`（16通り）。
  IBM拡張行 `0xF0-0xFC` はそもそも走査対象外（KB-04）。第2バイトは `0x40-0x7E`・`0x80-0xFC`（188通り）。
- 各バイト列を `TextDecoder('sjis').decode` し、結果が長さ1（単一の UTF-16 コード単位）の場合のみ登録する。

v5 は呼び出しごとに `new sjis()` していたが、本実装は `object` の初回参照時に一度だけ構築して再利用する
（v5 より速い理由。`tests/golden/sjis-bench.mjs` 参照）。

### 5.2 decode テーブルを JS 配列にした理由

decode 側（`rootTable`）はバイト値(0-255)を添字とする密な JS 配列で、要素は葉（文字列）か
枝（第2バイト用の同形配列）。v5 の `this.d[byte]` と同じく1回のルックアップで葉/枝を判別できる。
`Map[Int, Char]` と `Map[Int, Map[Int, Char]]` を分けて持つと、2バイト文字1つにつき
「単バイト側で失敗 → 2バイト側で成功」の2回引きになり、実測で decode が v5 より遅くなった。
