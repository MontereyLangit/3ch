# 板top・板設定（BbsSettings / BoardRepository）テスト追加レビュー

2026-10-20。対象は `feature/board_top` の 0eb8065〜b10b79d に対するテスト追加と、その過程で見つけた不具合の修正。

## セルフレビュー

| 指摘・確認事項 | 判断・対応 |
| --- | --- |
| `sbt test` で 38 件失敗（PostingSuite 37 件、HonoRenderSuite 1 件） | PostingSuite は KB-34、KB-35 が原因。修正し、回帰テストを PostingSuite 15節に追加。HonoRenderSuite は KB-33 の期待値を `<!DOCTYPE html>` 付きに更新。 |
| SETTING.TXT の解析仕様 | 利用者決定により v5 の `parseSettings` 完全互換（JS trim 相当・2つ目の `=` 以降の切捨て等）は採らない。空行と `=` を含まない行は無視、空の値は `""`。 |
| スレ初期設定の保存順 | `g.settings` のキー順で Thread DO に保存されるため、`VectorMap` で記載順を保持。PostingSuite で保存文字列を固定。 |
| 板top | BBS_TITLE の title / h1 出力、head.txt の無エスケープ埋め込み、SETTING.TXT 不在時の既定タイトルを `BoardHandlerSuite` で確認。統合テストの `板top` 期待値を更新。 |
| 管理画面の末尾スラッシュ | `AdminHandlerSuite` で各画面の GET と POST のリダイレクト先を確認。 |
| 板名の妥当性検査（404） | 実装が TODO のため未テスト。 |
| テスト名 | 利用者指示により全テスト名を日本語に統一（中身は不変）。 |

## Codex CLI レビュー

| 指摘 | 判断・対応 |
| --- | --- |
| [P2] 値に `=` を含む板設定が、スレ設定の再読込（`Posting.parseSettings`）で2要素目に切り詰められ、スレ立てと返信で値が変わる | 妥当。KB-36 に記録。利用者の判断でスレ設定の読込も `BbsSettings.parse` にそろえ、スレ立て→返信の回帰テストを追加。 |
| [P3] 修正した2件が台帳に未登録 | 採用。KB-34 / KB-35 として修正済で記録。あわせて KB-33 を修正済に更新。 |
| テスト名の日本語化 | 意味のずれなし。 |

## Gemini CLI レビュー

利用者の判断により実施しない。

## 検証

- `sbt test`: bbs 154、common 23（JVM / JS）、codegen 17、client 1 件すべて成功。`-no-indent` 付き `Test/compile` 成功。
- `npm run v6:build` 後の `npm test`: 8 件成功、skip 0。
