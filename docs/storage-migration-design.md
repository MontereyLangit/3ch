# ストレージ改修 詳細設計
作成: 2025-10-25  
ブランチ: feature/operate_1760897283

## 目的
現状 R2（中央オブジェクトストレージ）へ板やスレのデータを直接読み書きしているため、書き込みレイテンシが大きくなっている。エッジで分散して動作する Cloudflare Durable Objects（以下 DO）と Workers KV（以下 KV）を利用して「ホットパス」をエッジへ移行し、閲覧・書き込み速度を改善する。今回の修正で subject.txt は Durable Objects に移行し、スレッドのサイズは1スレ512KBの制限を設ける。R2 は廃止する。

要点:
- 書き込み（投稿／スレ立て）の低遅延化（エッジ上での直書き／競合制御）
- subject.txt を Durable Objects（Board DO）で管理
- スレッド本体は Thread Durable Object にて管理（1 スレッドあたり最大 512KB）
- bbsmenu(headline) と subback は Workers KV に移行
- R2 は廃止。永続のバックアップは別途検討（オフラインアーカイブ等）

## 制約と前提
- Durable Objects は各 DO インスタンスごとにシリアライズされたアクセスが保証されるため、append の競合が起きにくい。
- スレッドは最大 512KB に制限するため、DO のストレージ制約を超えない。
- Workers KV は最終整合性であるため、 headline / subback の即時性に関しては若干のラグがあり得る。
- R2 は今回の設計から外す（廃止）。

## 高レベル設計（ストレージマッピング）
- Durable Objects
  - ThreadDO（per-thread）: 各スレッド（bbs + key）を 1 DO インスタンスで管理。スレ内のレスを DO の state（バイナリ／JSON）に保持。1 スレッドの総データは 512KB を超えないように制限し、超える場合は書き込みを拒否（または新スレ作成を促す）。
  - BoardDO（per-board）: 各板の subject.txt を BoardDO が管理。subject.txt の読み書き、スレ一覧の生成、スレ立て規制（間隔等）の判定は BoardDO が行う。これにより subject.txt の原子的更新を DO 内で担保できる。
- Workers KV
  - HEADLINE_KV: bbsmenu / headline（全板ヘッドライン）を格納。
  - SUBBACK_KV: 板内の thread/<4桁>/subject.txt 相当の補助データを格納。

## なぜ subject.txt を DO にするのか
- subject.txt は更新時の競合が頻繁に発生しやすく、原子的更新が必要。BoardDO にて更新をシリアライズすれば ETag ベースの競合解決や複雑な再試行ロジックを簡潔にできる。

## Durable Object の API 設計（HTTP over fetch）
BoardDO と ThreadDO を用意し、以下の API を提供する。DO は Workers Durable Objects の fetch ハンドラで受ける。

ThreadDO (bbs:key 単位)
- GET /?op=read&start=<n>&count=<m>
  - スレのレスを取得。
  - レスポンス: { subject, number, lastModified, posts: [...], etag }
- POST /?op=append
  - レス追加。リクエスト body: { name, mail, message, time, userInfo... }
  - DO は append 前に合計サイズが 512KB を超えないか検査。超える場合は { ok:false, reason:'exceed_size' } を返す。
  - 成功時: { ok:true, number:<resnum>, etag:<new-etag> }
- POST /?op=create&subject=...
  - スレ立て。存在チェックと create-if-not-exist を DO 内で行う。
- POST /?op=compact (管理用)
  - 必要に応じた圧縮や古いセグメントのガベージコレクション。

BoardDO (bbs 単位)
- GET /?op=get_subject.txt
  - subject.txt を返す（先頭は lastModified 行を含む）。
- POST /?op=update_subject.txt
  - subject.txt を更新。リクエスト: { body: '...' }
  - BoardDO は更新の原子性を保障（複数リクエストはシリアライズされる）。
- POST /?op=check_create_thread (スレ立て規制判定)
  - スレ立て許可/拒否（BBS_CREATETHREAD_INTERVAL, BBS_THREAD_TATESUGI などの判定を BoardDO が行う）。

DO 内の state の想定構造 (ThreadDO):
{
  subject: string,
  number: number,
  lastModified: unixtime,
  etagCounter: number,
  posts: [ { num, name, shikibetsuId, time, id, message, userInfo } ] // 合計サイズ <= 512KB
}

## KV キー設計
- SUBJECT は BoardDO が管理するため、SUBJECT_KV には直接書かない（read-through キャッシュ等は別途検討）。
- HEADLINE_KV
  - key: 'headline.txt' -> value: full headline text
- SUBBACK_KV
  - key: 'bbs:<bbs>:thread-subback:<4桁>' -> value: subback 相当のテキスト

## 競合制御・整合性
- 投稿競合: ThreadDO のシリアライズ特性に依存し、append は DO 内で直列化されるため競合発生を回避。
- subject.txt 更新: BoardDO が唯一の更新者となることで整合を担保。
- スレッドサイズ制限: ThreadDO は append のたびに合計サイズをチェックし、512KB を超過する場合は拒否してクライアントにエラーを返す（運用ポリシーで『次スレへ移動』を促す）。

## R2 廃止に伴う注意点
- R2 を廃止するため、長期アーカイブや大容量保存方法を別途検討する必要がある（オンプレ/別クラウド/定期ダンプ等）。
- DO のストレージだけで全データを保持するのは望ましくないため、スレの冷却（古いスレ）は削除または外部アーカイブに移す運用が必要。

## 既存コードの変更箇所（bbs.cgi.js の対応）
- getBoardSettings(gb, request, env)
  - 変更: 現行の env.ch3www.get(SETTING.TXT) を BoardDO へ問い合わせる。BoardDO が存在しなければ KV からフォールバック（ただし SETTING は DO が担保する方針）。

- getThreadDataAndThreadSettings(gb, request, env)
  - 変更: env.ch3www.get(gb.logFile) -> ThreadDO に op=read を fetch。スレ立て時は ThreadDO の create API を呼ぶ。

- updateThreadFile(gb, env)
  - 変更: ThreadDO に対して POST op=append（または op=create for new thread）。ThreadDO が append の原子性・サイズ検査を行う。

- updateSubback(gb, env)
  - 変更: subback は KV(SUBBACK_KV) に格納。スレ立て時の更新要求は BoardDO が検査したうえで KV に書き込む（BoardDO が更新操作を仲介する）。

- updateHeadline(gb, env)
  - 変更: HEADLINE_KV に書き込む。BoardDO が headline の更新トリガを発行し、KV を更新する。

- getSubject(gb, env)
  - 変更: subject.txt は BoardDO が管理するため BoardDO に GET op=get_subject.txt を行う。

重要: すべての直接的な env.ch3www.get/put 呼び出しは削除/置換する（R2 を使わないため）。

## 移行手順（段階的）
1. 準備
   - ThreadDO / BoardDO 実装
   - KV namespaces 作成（HEADLINE_KV, SUBBACK_KV）
   - テスト環境でユニット/統合テスト作成
2. 読み取りのみ DO/KV へ切替（read-only キャナリ）
   - getSubject, getThreadDataAndThreadSettings の read path を DO に向ける。書き込みは従来のまま（ただし R2 は既に廃止するため一時的な保存方法を検討）
3. 書き込みを DO に切替
   - updateThreadFile（append/create）を ThreadDO に向ける。BoardDO が subject.txt 更新を行う。
4. KV へ headline/subback の移行
5. 古い冷却スレの運用ポリシーを導入（削除・オフラインアーカイブなど）

## ロールバックプラン
- FEATURE_USE_DO のフラグ（Workers 環境変数）で read/write のパス切替を行い、問題時は従来のパス（ただし R2 は廃止されたため従来パスは使えない点に注意。テスト時は R2 を残しておくこと推奨）

## テスト計画
- 単体テスト: DO の append/read/create/compact の動作（順序、整合性、サイズ制限）
- 負荷テスト: 高頻度書き込みに対する DO の待ち行列、待機時間を測定
- 障害注入: DO の失敗時のクライアント挙動、フォールバックの動作確認

## 監視とメトリクス
- ThreadDO の平均処理時間、失敗率、KV 読込/書込レイテンシ、DO インスタンス数、ストレージ使用量（スレ毎）

## 実装の短期優先リスト
1. ThreadDO / BoardDO の最小実装（append/read/create, subject.txt 管理, size check 512KB）
2. bbs.cgi.js の read パス差替え（getSubject, getThreadDataAndThreadSettings）
3. write パス差替え（updateThreadFile, updateSubback, updateHeadline）
4. ステージングで負荷/整合確認、段階的リリース

---

この設計を feature/operate_1760897283 ブランチに保存しました（subject.txt を BoardDO で管理すること、スレは 1 スレ 512KB 制限、R2 廃止を反映）。
