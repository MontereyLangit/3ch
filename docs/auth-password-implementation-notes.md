# ID・パスワード認証の実装メモ

この文書は§3.9の実装前レビュー用に書いた。E-1の実装（2026-11-07）で採った登録回復の方式は§7に記録する。
認証Bの予約期限・索引は変更していない。メール・方式移行はE-2で扱う。

## 1. 小さく実装する順序

1. 純粋モデル：LoginId、Password、LoginThrottle、PasswordCredential（実装済み）。
2. 実WebCryptoのPBKDF2プロバイダ（実装済み）。生成・照合と暗号APIの異常系を検証する。
3. 資格情報のcodecとPending/Readyの準備状態、作成・照合RPC、原子的な失敗計数とdummy照合（実装済み）。
4. 登録回復方式（実装済み。§7）。
5. BのOrigin・Turnstile・端末確認・AuthRateを再利用したHTTP登録／ログイン（実装済み）。
6. UIの登録・ログイン方式（実装済み）。

## 2. 先行する純粋モデルの契約

- LoginIdは小文字化後に `[a-z0-9_]{3,16}` を検査し、trimしない。登録時は予約語と板名も拒否する。
- PasswordはNFC後のUTF-8で8〜128バイト。空白を保持し、孤立サロゲートを拒否する。
  正規化済みIDをASCIIの大文字小文字を区別せず含むものを拒否する。
- PasswordCredentialは `{algo, iterations, salt, hash, updatedAt, throttle}`。
  新規は `pbkdf2-sha256`、100,000回、salt16バイト、hash32バイト。時刻は整数UNIX秒。
  既存の1〜100,000回を読めるが、低い反復数は成功時の再ハッシュ対象とする。
- LoginThrottleの窓は `t > now - 3600`。同一IPの10回目から3600秒ロックする。
  全IP合計の5回目から1秒、12回目から上限8秒の待機を返す。
  ロック中は増分・延長せず、成功は当該IPの失敗だけを消す。時計逆行時も未来履歴を捨てない。

## 3. 登録完了の不変条件

公開UIDとログインIDは同じ正規化済み文字列だが、索引HMACは `uid:` と `login:` で別になる。
両索引が同じaidで確定し、Accountの利用開始が保存されるまで、ログイン・投稿・通常Cookie発行を許可しない。
片方の索引からaidが引けることを、利用開始の根拠にしない。
全ての予約・確定・取消・再開はexpectedAidを確認する。別の所有者を上書き・削除しない。
確定済みUIDは永久保持する。通信結果が不明なまま新しいaidでやり直さない。

| 失敗箇所・観測 | 必要な判断 |
| --- | --- |
| 予約の応答消失 | 実際の所有者を読み直す。同じaidの予約と他人の衝突を区別する |
| 一方の予約が衝突 | 自分の未確定予約だけを取消可能か確認する。他人の予約には触れない |
| 確定の保存後に応答消失 | 確定済みlookupのaidを確認し、同じaidならその段は成功として再開する |
| uidだけ確定 | uidは取消不可。loginを同じaidへ確定できる回復経路が必要 |
| loginだけ確定 | Accountは利用不可のまま。uid確定と利用開始まで回復させる |
| 利用開始の保存後に応答消失 | 再create・世代更新をしない。再Cookie発行には現在の本人確認が必要 |
| 全DO通信停止 | 成功も盲目的な取消もしない。復旧後に同じ試行を識別して状態確認する |

現行Bのpendingは10分後に取り直せる。uid確定後にlogin予約が失効・再割当てされると、
任意指定のUIDを元の利用者が使えなくなる。confirm順の変更と補償だけでは、この問題は解消しない。

## 4. 登録回復方式の候補と未解決事項

候補の一つは、Accountに非公開の準備・確定要求・完了の記録を置き、両予約の回収を確定中だけ抑える方式である。
索引の確定結果を再照合し、確定要求後は取消ではなく完了へ進める。登録試行を識別する短期Cookieも候補になる。
いずれも追加プロトコルであり、このメモでは採用しない。次の点を決めてから実装する。

- 10分の通常予約と確定中予約の期限・回収条件。無期限に保持する前提にしない。
- 片方だけ保持された孤立予約、利用者が戻らない試行、保存前後の停止をどう回収・回復するか。
- 中止・確定・期限切れの競合と、応答消失で結果不明の操作を再実行する条件。
- Cookieの消失・期限切れ後の本人確認。UIDの知識だけで再開せず、完了後の試行Cookieだけでログインさせない。
- DO間の呼出し方向。Accountがロック中にIndexを待つなら、IndexからAccountへの所有確認を待たせない。
  相互待機する呼出し経路を作らず、保留・回収・alarmも含めて確認する。

## 5. 資格情報RPCと信頼境界

最小の照合ポートは `lookupLogin(hash)` と `verifyPassword(aid, password, keyIpHash, now)` を想定する。
登録側のprepare・advance等のRPCは、第4節の方式を選んでから固定する。
keyIpHashは受信IPをKeyIp.ofで正規化し、Bと同じ `HMAC(AUTH_SECRET, "key-ip:" + keyIp)` で作る。
時刻・失敗数・ロック期限・salt・反復数・照合結果をHTTP入力から採用しない。
Accountのロック内で資格情報を読んでPBKDF2を実計算し、全32バイトの比較と失敗計数・保存を一回のRPCで行う。
ロック判定は計算を省略する指示ではない。応答待機は内部結果として返し、DOのロック外で行う。
未知IDも固定の `Account("dummy")` に同じRPCを送り、実際に100,000回計算して必ず失敗させる。
dummyは通常の16文字aid規則に合わないため、通常profileを作らない経路が必要である。
不一致・ロック・未知IDは同じ文言にし、明示した認証が失敗しても既存Cookieで代用しない。

第3段では並行失敗・窓の境界・別IP・dummyの実計算回数を検証する。
HTTP公開前には、各保存直後の応答消失、10分境界、全通信停止と再送で、第3節の不変条件を確認する。

## 6. SQLite DOの同期KVとSQLの原子性

2026-11-07（日本時間）、Miniflare 5.20261012.0-beta／workerd 1.20261012.1、compatibility_date 2026-08-04、追加フラグなしのローカルSQLite DOでstorage.kvとtransactionSyncの動作を確認した。独立したテスト用DOをtests/support/storage-probe-worker.mjsに置き、本番Accountの実装・保存領域は使わない。

[SyncKvStorage](https://github.com/cloudflare/workerd/blob/main/src/workerd/api/sync-kv.c++)と[非同期Storage](https://github.com/cloudflare/workerd/blob/main/src/workerd/api/actor-state.c++)は同じV8シリアライザを使う。SQLite DOの同一データベースで、transactionSyncはSAVEPOINTを作り、例外ではROLLBACK TOを行う。実装の参照先は[ActorSqlite](https://github.com/cloudflare/workerd/blob/main/src/workerd/io/actor-sqlite.c++)。

永続化テストでは、非同期putを同期KVから読めること、同期KVとSQLの同時確定、JS例外とSQL制約違反によるprofile・credential・SQL全体の取消を確認する。温めた非同期getのキャッシュも同期書込後に更新され、Date・Uint8Array・null・undefinedは両方向で保持される。これはローカルランタイムの能力検証であり、Scalaの資格情報RPCの原子性や本番環境での動作確認を済ませたことにはしない。

## 7. 採った登録回復の方式（E-1）

§4の候補（Accountに確定要求を記録する方式）は採らず、既存の10分の予約期限を変えない次の方式にした。

- 確定の順は「Accountを登録準備中で作成（資格情報付き）→ login（Googleならgoogle）索引の確定 → uid索引の確定 → `activate`」。取り消せない uid の確定を、取り消せる login の確定より後に置く。
- uid の確定が明示的に拒否されたら、login と uid の自分の予約・確定を外す。応答だけが失われたときは uid を `lookup` し、自分の aid なら続け、そうでなければ外す。`lookup` も失敗したら何も外さず利用不可を返す。
- `activate` の前で止まったアカウントは、本人確認（正しいパスワード、Googleなら検証済みのIDトークン）の後に再開する。DOは照合に成功したときだけ登録準備中であることと uid を返し、ハンドラは uid が同じ aid で確定済みか、自分の予約が残っていれば確定してから `activate` する。uid が別の aid に渡っていれば再開せず失敗にする。
- 残る弱点: login の確定後に uid の確定の結果が分からず、`lookup` も失敗した場合、10分後に uid の予約が切れると、そのIDは誰も登録できない（login が死んだアカウントを指し続ける）。回収は管理画面の段で扱う。
- Account DOからIndex DOを待つ呼出しは作っていない（相互待機なし）。
