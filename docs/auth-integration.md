# 認証の開発・検証設定

段Bの実装範囲と未実装部分は [引き継ぎ](claude-handoff.md) と [レビュー記録](auth-implementation-review.md) を参照する。本番への反映は別作業。

## 認証の有効化

認証は次の4つがすべて揃い、`AUTH_SECRET` が32文字以上のときだけ有効になる。1つでも欠けていれば、既存の投稿処理と画面を維持し、`/auth/` は認証導入前と同じ404を返す。板トップに認証モーダルも置かない。

| 設定 | 内容 |
| --- | --- |
| `AUTH_SECRET` | Cookie署名と索引HMACに使う秘密鍵。32文字以上の乱数を環境ごとに用意する |
| `AGREEMENT_VERSION` | 現在の利用規約・法的確認の版。画面に渡した版とPOSTの同意版を照合する |
| `TURNSTILE_SITE_KEY` | 認証ページに渡す公開サイトキー。検証するホストをTurnstile側でも登録する |
| `TURNSTILE_SECRET_KEY` | Turnstileのサーバー検証用秘密鍵 |

ローカルの値はGit管理外の `.dev.vars` に設定する。秘密鍵を `wrangler.jsonc`、文書、ログに書かない。

手動確認には検証用のTurnstile設定を用意し、対象のホストを登録する。サーバーは検証応答のhostnameと要求先を完全一致で照合する。[Cloudflareのテスト用キー](https://developers.cloudflare.com/turnstile/troubleshooting/testing/) を使う場合も、その応答仕様を確認し、本番のキーと混ぜない。自動テストは外部認証の応答だけを差し替え、実際のHTTP処理とDOを呼ぶ。本番の秘密鍵は必要ない。

## ローカルで確認する

```bash
npm ci
npm run v6:build
npm run dev
```

ブラウザでは `http://localhost:8787/` を開く。セッションは `__Host-ch3s_sid`、`Secure; HttpOnly; SameSite=Lax; Path=/` で発行する。開発時のHTTP発行を許すのは `localhost` だけで、`127.0.0.1` や任意のHTTPホストでは発行しない。本番はHTTPSを使う。

`/auth/` で同意とTurnstile確認を行い、認証後のヘッダー、投稿の認証モーダル、`/auth/account/` のキー発行・失効とログアウトを確認する。未ログインで `/auth/account/` を開くと `/auth/?return=/auth/account/` へ移る。キーの平文は発行直後に一度だけ表示する。GET、SSR、ブラウザの保存領域には残さない。

## Durable Objects

段Bの追加バインディングは `ACCOUNT_DO`、`ACCOUNT_INDEX_DO`、`AUTH_RATE_DO`。`wrangler.jsonc` の移行タグ `v2` は `new_sqlite_classes: ["Account", "AccountIndex", "AuthRate"]` の3クラスを追加する。既存のBoard・Threadの保存形式は変えない。

`AccountDirectory` は後続の管理画面の段で追加する。適用済みの `v2` に追記せず、別の移行タグを使う。`AdminAudit` も後続の管理画面の対象。

## 検証コマンド

```bash
npm run v6:build
sbt test
npm test
npm run v6:typecheck
```

`npm test` は事前にScala.jsをリンクし、実workerdの統合テストとSSR・hydrationを検証する。本番のストレージは使わない。最新の実行結果は [引き継ぎ](claude-handoff.md) に記録する。
