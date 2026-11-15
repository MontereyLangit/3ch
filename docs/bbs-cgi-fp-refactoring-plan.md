# bbs.cgi 投稿処理の FP 化リファクタリング計画

2026-11-16。[DDD 化リファクタリング計画](bbs-cgi-ddd-refactoring-plan.md)（段 0〜9 完了）の続き。
設計思想の優先順位を **FP > OOP > DDD** とし、層を次のとおりにする（AGENTS.md 3.3）。

```
(Router) -> Handler -> Service -> Domain Model (Value Object / Entity) -> Repository -> Durable Object / DB / Data Storage
```

`application/` パッケージは置かず `service/` を使う。下の層は上の層を import しない。

## 0. 不変条件

- 振る舞いを変えない。`PostingSnapshotSuite`（`tests/snapshots/posting/*.json`）と golden を再生成せずに通す。
- 各段は単独でマージできる。DO / R2 / KV のキーと保存形式は変えない（AGENTS.md 3.2）。

## 1. FP 観点のレビュー所見（2026-11-15、Fable 5.2）

純粋な判定（`PostRouting`、`ModerationService`、`PostRateFlow`、`ThreadWriteGuard` など）は `Either` / enum を返し、
時刻を引数で受け取る形に概ねなっている。FP から最も外れているのは `service/posting/PostingService.scala`。

| # | 所見 | 方針 |
| --- | --- | --- |
| F1 | 業務上の拒否を `throw Rejected` で運び、`guarded` が `isInstanceOf` で振り分ける。拒否しうることが型に出ず、段を単独でテストできない | `opaque type Step[+A] = Future[Either[Rejection, A]]` の薄い合成（cats は入れない） |
| F2 | `PostingContext` が段ごとに埋まる万能バッグ（`number = 0` や `""` が「未設定」を表す）。スレ立て判定が 2 通り、スレ主が呼ぶと例外を投げうる関数、Set-Cookie 文字列がドメイン型に入っている | 段ごとに確定した事実の型（`PostTarget` / `Poster` / `ComposedPost` …）に分ける |
| F3 | ハッシュが非同期なため、識別子・スレ主表示・連投規制の入力組み立てまで `Future` に入っている | 種を列挙 → 一括ハッシュ → 純粋に組み立て |
| F4 | `ports` の多くが 1 メソッドの trait で、repository → service の逆依存になっている | 関数のレコード `PostingDeps` にまとめ、Handler が組み立てる |
| F5 | 認証の有無が `Option` とフラグで 10 箇所に分岐。正しい ADT が既にあるのにフラグへ潰している。auth と posting の循環依存 | `PosterAccount` / `PostingAuthMode` の ADT、`AuthQuery` で循環を切る |
| F6 | Service に HTTP / JS の型（`Request`、`URL`、`Uint8Array`、Set-Cookie の組み立て）がある | Handler へ移す |
| F7 | 過去ログ目次の ETag 比較交換の手順を Service が知っている | `ArchiveIndexRepository.modify` へ |
| F8 | DO に純粋ロジックが残る（subject.txt の並べ替え、`convertLegacy` の `var` と可変コレクション、`addRes` の判定）。業務上の拒否も例外 | `SubjectList.bumped`、`LegacyThreadLog.toDat`、`ThreadAppend.plan` に出し、拒否は値で返す |
| F9 | 判定の途中で副作用としてログを書く（`Try` で握りつぶす） | `events` を値で返し、Handler が書き出す |
| F10 | 細かい層の問題（`BoardRepository` → `handler.PostingText` など）、`common` と `bbs/domain` の線引きが曖昧、弱い型 | 段 1・14 |

## 2. 段階

| 段 | 内容 | 状態 |
| --- | --- | --- |
| 1 | 逆依存の解消: `BoardRepository` は `BbsSettings.defaultText` を直接使う。repository が使う ports の純粋なデータ型（`ThreadSnapshot`、`StoredIndex` / `ArchiveIndexFile`、`PostRateObservation`、`OutgoingMail`、アカウント系の結果型、`AccountStoreException` など）を `domain/posting`・`domain/account` へ移す。port の trait は段 8 まで service に残す | 完了 |
| 2 | `application` → `service` の改名 | 完了 |
| 3 | 純粋な段の切り出し | |
| 4 | ハッシュの一括化（F3） | |
| 5 | `Step` の導入と例外の除去（F1） | |
| 6 | `PostingContext` の分割（F2） | |
| 7 | 認証の ADT 化（F5） | |
| 8 | ports を関数レコード `PostingDeps` に置き換え（F4）。repository の service 依存がなくなる | |
| 9 | HTTP / JS を Handler へ（F6） | |
| 10 | ログの値化（F9） | |
| 11 | 比較交換を Repository へ（F7） | |
| 12 | DO から純粋ロジックを出す（F8） | |
| 13 | （任意）`addRes` の拒否を RPC の戻り値で返す | |
| 14 | （任意）`PostingError` を拒否と障害に分け、`common` / `bbs/domain` を整理（F10） | |

効果の大半は段 5・6 にある。

## 3. 検証

各段で `sbt test`、`sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile`、`npm run v6:build`、`npm run v6:typecheck`、`npm test`。
