# AI エージェント用のスキル

このリポジトリでは、AI エージェント（Claude Code / Codex CLI / Gemini CLI）が読むスキルを `.agents/skills/` に置いています。

| スキル     | 用途                                                       | 取得元                                                              |
| :--------- | :--------------------------------------------------------- | :------------------------------------------------------------------ |
| `yomiyasu` | AI が書いた日本語を、意味を保ったまま読みやすく推敲する | [nanaism/yomiyasu](https://github.com/nanaism/yomiyasu) v3.4.1（commit `3979c75`）の `skills/yomiyasu/`。MIT ライセンス |

## 置き場所

- Codex CLI と Gemini CLI は `.agents/skills/` を直接読みます。
- Claude Code は `.claude/skills/` しか読まないため、`npm ci` の postinstall で `scripts/sync-skills.mjs` が `.agents/skills/` の中身を `.claude/skills/` に写します。写しは Git に入れません。
- `npm ci` をせずに写すときや、`.agents/skills/` を書き換えたあとは `npm run skills:sync` を実行します。

## yomiyasu の使い方

日本語の文書・PR の説明文・コミットメッセージ・画面の文言を書いたり直したりするときに使います（`AGENTS.md` の 3.5）。`/yomiyasu` で呼ぶか、依頼の文に「yomiyasu で推敲して」と書きます。

同梱の検査スクリプトには Python 3.9 以降が要ります。外部ライブラリは要りません。

```bash
python .agents/skills/yomiyasu/scripts/yomiyasu_lint.py docs/admin.md
python .agents/skills/yomiyasu/scripts/yomiyasu_check.py 推敲前.md 推敲後.md
```

lint と check が出すのは見直しの候補で、合否ではありません。直すかどうかは前後の文脈で決めます。

## 更新

上流の `skills/yomiyasu/` を取り出して `.agents/skills/yomiyasu/` を丸ごと置き換え、`npm run skills:sync` を実行します。スキルの中のファイルは書き換えません。プロジェクト固有の決まりは `AGENTS.md` に書きます。更新したら、上の表の版と commit も直します。
