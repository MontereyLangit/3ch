# 新規投稿、スレッド作成
Route: /test/bbs.cgi, /test/subbbs.cgi
C: `index.js`
S: `bbs_cgi_service.js`
V: `bbs_cgi.template.js`
M: `bbs_cgi_view_model.js`

## post(request, env, ctx, model)
掲示板への書き込み処理 `/test/bbs.cgi` のエントリーポイントです。一連の書き込み処理を順次呼び出します。

1.  **`bbsCgiViewModel` オブジェクトの初期化**:
    *   `index.js` から渡された `bbsCgiViewModel` を、リクエスト情報（IP、UAなど）や各種設定値で初期化します。
2.  **処理の実行**:
    *   bbs_cgi_service.js内の関数を順番に呼び出します。
    *   各関数は、処理を中断してエラーを `bbsCgiViewModel` に設定する場合は `false` を、処理を続行する場合は `true` を返します。
3.  **レスポンス生成**:
    *   `index.js` に処理が戻り、`createHtmlResponse` と `viewBbsCgiViewModel` を使って、`bbsCgiViewModel` の状態に応じたHTMLレスポンス（成功、確認、エラー画面）が生成されます。

## checkRequest(model, request)
リクエストの初期検証を行い、結果を `bbsCgiViewModel` に格納します。

1.  `POST` メソッドであるか、`Content-Type` が正しいか、`Referer` が正当なドメインかをチェックします。
2.  User-Agentの長さと文字種を検証します。
3.  User Agent Client Hints (`sec-ch-ua-*`) を取得し、User-Agent文字列やデバイス情報に追加します。
4.  2ch専用ブラウザ `Monazilla/1.00` かどうかを判定します。
5.  IPv6アドレスかどうかを判定し、ID生成の元になる文字列 (`idnotane`) を設定します。
6.  簡易的なプロキシチェックを行います。

## getPlatformByUserAgent(userAgent) / getAndroidModelByUserAgent(userAgent)
User-Agent文字列からOSのプラットフォーム名やAndroidのモデル名を取得するヘルパー関数です。

## readForm(model, request)
POSTされたフォームデータを読み込み、解析して `bbsCgiViewModel.form` オブジェクトに格納します。

1.  リクエストボディをバイト配列として取得します。
2.  `submit` ボタンの値から、文字コードが `Shift_JIS` か `UTF-8` かを判定します (`bbsCgiViewModel.isSJIS`)。
3.  ボディを `&` で分割し、各キーと値のペアを処理します。
4.  `bbsCgiViewModel.isSJIS` の値に応じて、URLデコードと文字コード変換を行います。
5.  HTMLエンティティ化、改行コードの `<br>` への変換、制御文字の削除などのサニタイズ処理を行います。
6.  偽キャップ(`★`→`☆`)や偽トリップ(`◆`→`◇`)の変換を行います。
7.  名前欄からトリップキー (`#`以降) を分離します。
8.  `!newthread` コマンドを検出し、スレッドタイトルと本文を分離します。
9.  各フォームフィールドの不要な改行を削除し、型を検証します。
10. 必須項目（本文、スレタイorキー、time）の存在と形式をチェックします。
11. `time` の値が現在時刻から大きくずれていないかチェックします。
12. スレッド作成時は、`key` に現在時刻のUnixタイムスタンプを設定します。

## decodeSjisURIComponent(encodedURIComponent)
Shift_JISでURLエンコードされた文字列をデコードします。

## processTrip(model, request, env)
トリップキーからトリップを生成します。

1.  トリップキーをShift_JISとしてバイト配列に変換し、再度文字列として解釈します。
2.  キーの長さや形式（10桁、12桁、#付きなど）に応じて、異なるアルゴリズム（`crypt` または `sha1Base64`）でトリップ文字列を生成します。
3.  生成したトリップを名前欄 (`bbsCgiViewModel.form.FROM`) に追加します。

## SetPath(gb)
Durable Objectにアクセスするためのパスを `bbsCgiViewModel` オブジェクトに設定します。

## checkItabetsuSpecial(model, request, env)
板固有の特殊な書き込み制限（例: `madakana` 板や `admin` 板はキャップのみ書き込み可）をチェックします。

## getBoardSettings(model, request, env)
`Bbs` Durable Objectから設定ファイル `SETTING.TXT` を読み込み、`bbsCgiViewModel.boardSettings` に格納します。スレッド作成時のみ実行されます。

## getThreadDataAndThreadSettings(model, request, env)
書き込み対象のスレッドのデータを取得し、スレッド固有の設定を読み込みます。

1.  **スレッド作成時**:
    *   対象のログファイルが既に存在する場合はエラーとします。
    *   `bbsCgiViewModel.logData` にスレッドタイトルを初期値として設定します。
2.  **レス書き込み時**:
    *   `Thread` Durable Objectからログファイルを取得します。存在しない場合はエラーとします。
    *   ログの1行目からスレッドタイトル、2行目からスレ設定を読み込み、`bbsCgiViewModel.settings` に格納します。
    *   レス数が300を超えている場合はエラー、ちょうど300の場合は警告メッセージを追加します。
    *   スレッドが停止されている (`BBS_HEISA`) 場合はエラーとします。

## processIdentification(gb, request, env)
ユーザー識別システム（忍法帖）に関する処理です。Cookieの取得、認証、各種制限判定などを行います。

1.  Cookie (`PON`, `HAP`, `time`, `PIN`) を取得します。
2.  `PON` Cookie（IPハッシュ）を生成し、レスポンスヘッダーにセットします。
3.  忍法帖システムが無効な板では、`PON` Cookieの有無のみチェックして処理を終了します。
4.  Cookieの正当性を `PIN` を使って検証します。
5.  `HAP` (忍者ID) をキーに `User` Durable Objectからユーザーデータを取得します。
6.  **ユーザーデータが存在する場合**:
    *   認証状態をチェックします。`AUTH` 状態なら認証ページへ誘導します。
    *   User-Agentが登録時と変わっている場合はエラーとします。
7.  **ユーザーデータが存在しない場合**:
    *   新しい `HAP` と認証コードを生成し、`User` DOに `AUTH` 状態で保存します。
    *   認証ページへ誘導するエラーメッセージを表示します。

## checkDenyList(model, env)
アクセス規制リスト `madakana` と照合し、書き込みを制限します。

1.  キャップユーザーはチェックをスキップします。
2.  KVから `madakana` ファイルを取得します。
3.  ファイルの内容を1行ずつ読み込み、正規表現で板、規制対象ユーザー情報、NGワードを照合します。
4.  マッチした場合、規制内容（`TateDame`, `mark`, 通常の書き込み禁止）に応じて処理を分岐します。
    *   `TateDame`: スレッド作成時のみエラーとします。
    *   `mark`: 拒否られマークを付けて書き込みを許可します。
    *   その他: エラーを返して書き込みを禁止します。

## checkFieldSize(model)
名前、メールアドレス、本文、スレッドタイトルの文字数や本文の行数制限をチェックします。板設定 (`BBS_*_COUNT`など) に基づいて判定します。

## makeIdAndShikibetuId(model)
IDと識別IDを生成します。

1.  **ID生成**:
    *   板設定 (`BBS_ID`) に応じて、投稿時IPまたは初回IPからハッシュ値を計算し、`bbsCgiViewModel.id` に `ID:xxxxxx` の形式で格納します。
2.  **識別ID生成**:
    *   板設定 (`BBS_ID_SHIKIBETSU`) に応じて、国、地域、AS番号、デバイス情報などからハッシュ値を計算し、`bbsCgiViewModel.shikibetsuId` に `JP.vE.J2 xH-7ca00d39db6fd3e6` のような形式で格納します。

## getSha1Base64Id(...)
`makeIdAndShikibetuId` で使われるヘルパー関数で、与えられた情報からID用のハッシュ文字列を生成します。

## processCommands(model, request, env)
ユーザーコマンドや管理コマンドを処理します。

*   `!SETTING:NAME=VALUE`: スレ主またはキャップがスレッド設定を変更できます。
*   `!LOG:bbs:key:n`: 管理者が指定したレスの生ログを取得できます。

## processByBySaru(gb, request, env)
スレッド内の連投規制（バイバイさるさん）や、壊れたレスの修復などを行います。（このファイルではまだ実装されていません）

## checkRegulationOfCreatingThread(model, env)
スレッド作成時の規制をチェックします。

1.  スレッド作成時でなければ処理をスキップします。
2.  `Bbs` Durable Objectを検索し、同じキーや同じタイトルのスレッドが既に存在しないかチェックします。
3.  板設定 (`BBS_THREAD_TATESUGI`) に基づき、短期間にスレッドを立てすぎていないかチェックします。

## updateSubject(gb, env)
スレッド一覧 (`Bbs` Durable Objectの `subjectText`) を更新します。

1.  `sage` 判定を行います（メール欄に "sage"、古いスレ、実況板など）。
2.  `sage` の場合は更新せずに処理を終了します。
3.  `Bbs` Durable Objectの `subjectText` に、スレッド情報（新規作成またはレス数・最終更新日時の更新）を書き込みます。
4.  板設定 (`BBS_MAX_MENU_THREAD`) に基づき、古いスレッドの情報を削除します。

## updateThreadFile(gb, env)
スレッドのログファイル（`Thread` Durable Object）を更新します。

1.  新しいレスのデータを `bbsCgiViewModel.logData` に追記します。
2.  `Thread` Durable Objectに書き込みます。
3.  競合した場合は、リトライ処理を行いますが、最終的に失敗した場合はエラーとします。

## updateSubback(gb, env)
スレッド作成時に、KVに過去ログ倉庫のインデックス情報を書き込みます。

## updateHeadline(model, env)
サイト全体のヘッドラインファイル (`Bbs` Durable Objectの `headText`) を更新します。`sage` でない書き込みがあった場合に実行されます。

## dispError(model, errMsg)
エラーメッセージを `bbsCgiViewModel.error` に設定し、処理中断を示す `false` を返すヘルパー関数です。
