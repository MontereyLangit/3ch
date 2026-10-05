# 専ブラ用画面と通常画面の出し分け

2026-10-05。利用者の指示により、画面の処理で応答方式を明示する。
利用者は専ブラ向け画面のタグ全体の React 化と、属性の引用符・タグ表記・改行の変更を許容した。
追加指示により、専ブラ用画面にも通常画面と同じクライアント JS を付加する。
BBSMENU は提示された実例に合わせ、小文字タグ・引用符付き属性を許容し、カテゴリと板リンクの並びを重点的に検証する。

## 実装

| 画面                          | 経路                                                                    |
| ----------------------------- | ----------------------------------------------------------------------- |
| 自動生成 BBSMENU              | `BbsMenuPage` → `RendererShell.legacyReact` → Shift_JIS                 |
| 板トップ                      | `BoardPage` → `RendererShell.legacyReact` → Shift_JIS                   |
| bbs.cgi / subbbs.cgi          | `PostingPages` → `RendererShell.legacyReact` → 既存の投稿文字コード判定 |
| R2 に保存したカスタム BBSMENU | `RendererShell.legacyHtml` → HTML をそのまま Shift_JIS 化               |
| 通常の React 画面             | HonoX `_renderer.tsx` → `Main.renderShell` → UTF-8 の通常シェル         |

専ブラ向けページは `renderToStaticMarkup` で静的 HTML にしてから既存の `Responses.plain` を使う。
HTTP 200、文字コードと Content-Length、投稿結果のヘッダー、2ch マーカー、条件付き 304 の判定を維持する。
生成する専ブラ画面にも `/static/client.js` の module script を付加する（wrangler dev もビルド済み Static Assets を使う）。
保存済みカスタム BBSMENU は内容をそのまま返す。dat 等のテキスト API、管理画面、Static Assets の応答経路は変更しない。
同じ板 URL の `webclient=3chiko` 指定も従来どおり維持する。

## HTML 表記の変更

- React による属性の引用符、空要素、改行の変化を許容する。板トップに `tbody` を明示する。
- 板トップの DOCTYPE を維持し、従来の標準モードを保持する。他の専ブラ画面には新しく DOCTYPE を追加しない。
- 板タイトル・メニューの板名は React のテキストとしてエスケープする。設定に HTML タグや実体参照を直接記述していた場合、それらは文字として表示される。
- 投稿の事前エスケープ済みフォーム値・改行を含むエラー文は既存の HTML 断片として維持する。
- `2ch_X:error` と `2ch_X:cookie` は固定コメントを持つ span 内に残す。
- 板トップの非実行コメント（`nobanner` 等）は省略する。既存の実行用 script と CSS は維持する。

## レビュー・検証

### Claude Code レビュー

利用者の指定に従い、秘匿値を除いたコード・差分を `claude` に渡して静的レビューを実施。

| 指摘 | 判断・対応 |
| --- | --- |
| 中: 板トップの DOCTYPE 欠落で quirks モードになる | 採用。セルフレビューでも検出し、板トップのみ DOCTYPE を前置。単体・workerd テストで確認する。 |
| 低〜中: React の `noWrap: true` は出力されない | 採用。実際の React DOM 出力で属性が消えることを再現し、`nowrap: ""` に変更。出力テストを追加。 |
| 要確認: React 19 による head 要素の順序変更 | タグ表記変更は承認済み。メニューのカテゴリ・板リンクの連続した並びと文字コード、投稿マーカーを検証する。head 全体の旧順序は固定しない。 |
| エラー文の改行を含む HTML 断片のテスト不足 | 既存 PostingSuite に `<br>\n` を含むエラー文の完全一致検証があり、React 化後も成功。追加の同等テストは不要と判断。 |

クライアント JS の追加と BBSMENU の最終仕様を反映したコードを再レビューした。
`respondEndHtml` に `2ch_X:true` がないという指摘は不採用。移植前の `PostingText.respondEndHtml` にも存在せず、汎用 `legacyHtml` テストの入力例を旧画面の仕様と誤認した指摘だった。
それ以外の追加指摘はなかった。

### 最終検証

- Scala 単体テスト: bbs 140 件を確認。最終のクライアント JS 追加で古くなった確認画面の「module script なし」期待値を更新し、PostingSuite 50 件を再実行して成功。他のスイートは直前の全体実行で成功。
- `npm run v6:typecheck`: 成功。
- `npm run v6:build`: 成功。
- `npm test`: 2 件成功。実 workerd で板トップの DOCTYPE・Shift_JIS・クライアント JS、JS ファイルの配信、Monazilla の Shift_JIS 投稿エラーとマーカー、通常投稿・管理操作を確認。
- `git diff --check`: 成功。
- 実際の専ブラ製品での読込テストは未実施。BBSMENU のカテゴリと板リンク・改行の連続した構造は単体テストで固定。
