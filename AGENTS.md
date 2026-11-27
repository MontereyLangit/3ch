# 3ch Development Guide

Instructions for contributors and coding agents (Codex, Claude Code and Gemini CLI).

## 1. Scope and sources of truth

3ch (３ちゃんねる, https://3ch.io/) is a 2ch-compatible, thread-float bulletin board running on Cloudflare Workers. This repository contains the current Scala.js/HonoX generation of the system (v6), formerly called ch3s. It is not a Next.js application.

- This file is the canonical repository-wide development guide. Do not create duplicate CLAUDE.md or GEMINI.md files. Codex reads AGENTS.md directly; Gemini CLI is configured through .gemini/settings.json; compatible Claude Code versions (2.1.277 or later, subject to provider support) read AGENTS.md when CLAUDE.md is absent.
- Use the user's explicit instructions and approved issue requirements for the task. Read the relevant source, tests and configuration before making changes. Distinguish current behavior from target behavior; an open issue or design document is not evidence that a feature is implemented.
- GitHub issues are the backlog and the place to track implementation plans and acceptance criteria. Keep detailed specifications and architectural decisions in docs/, and update affected documentation when behavior changes. Resolve conflicting requirements explicitly rather than silently choosing an obsolete design.
- The former vanilla JavaScript implementation (v5) was retired after the v6 migration. Consult the v5-final tag when investigating historical behavior, e.g. `git show v5-final:src/common.js`. Old references to src/*.js may refer to that tag.
- Do not depend on a developer-specific absolute path or on unrelated local repositories being present.

## 2. Current architecture

### Technology stack

- Scala 3 (LTS), Scala.js, sbt, scalajs-react and React.
- hx4s, the independent Scala.js/HonoX integration framework: https://github.com/MontereyLangit/hx4s. It provides typed route/island wiring, SSR integration, props validation and Vite support. Keep reusable framework functionality in hx4s, not in the application.
- HonoX / Hono / Vite for routing, rendering, hydration, bundling and the Workers entry point. Public pages use server-side rendering with interactive React islands where migrated; a legacy HTML frontend remains for unmigrated pages. The admin scalajs-react SPA is a migration target, not a description of the entire current admin UI.
- Cloudflare Workers, Durable Objects, R2 and KV. Consult wrangler.jsonc for actual bindings, compatibility flags and migrations, and build.sbt, package.json and hx4s.lock for dependency versions.
- The current Board and Thread DOs use the legacy KV-backed storage model. Account, AccountIndex and AuthRate are SQLite-backed DO classes, but their application-level state is not yet fully relational. D1 catalog/feed projections are planned, not deployed by the current configuration.
- R2 (ch3www) stores board settings, static resources, archive indexes and other legacy data. CAP and TEST are KV bindings.
- Monazilla-compatible dat, subject.txt, SETTING.TXT, BBSMENU and bbs.cgi interfaces. Shift_JIS conversion uses the project's own SjisEncoding implementation.

### Repository layout

Scala packages use `io._3ch.*`.

| Path | Responsibility |
| --- | --- |
| common/ | Shared JVM/JS cross-project: pure domain logic, value objects, route compatibility rules; JS-specific shared island props under src/main/scala-js/. No production I/O in the pure shared code. |
| bbs/ | Scala.js server, Workers handlers, services, domain, persistence adapters, SSR components and DOs. |
| bbs-client/ | Scala.js browser islands and client-side behavior. |
| codegen/ | JVM-only 3ch-specific generation of DO TypeScript declarations and Scala RPC stubs. |
| app/ | HonoX/Vite integration, handwritten entry points and generated route/island files. |
| public/ | Static assets, including the remaining legacy 3chiko.html. |
| tests/ | Node/workerd integration tests, SSR/hydration tests, generator checks and shared golden fixtures. |
| docs/ | Specifications, development guides, design decisions, historical notes and UI mockups. |
| .github/workflows/ci.yml | CI build, type checking, integration tests and startup smoke checks. |
| wrangler.jsonc | Worker bindings, migrations, assets and deployment configuration (dist/index.js). |

Within `bbs/src/main/scala/io/_3ch/bbs/`:

- Main.scala: current Scala.js server exports and annotated routes.
- handler/: HTTP/protocol adapters, including PublicRoutes, Posting, AdminHandler and auth/.
- service/: use-case orchestration; service/*/ports defines external capabilities.
- domain/: typed domain models and rules, including posting-specific rules; platform-independent rules belong in common/.
- repository/: persistence and external service adapters implementing ports.
- durable/: Board, Thread, Account, AccountIndex and AuthRate; codecs in durable/codec/ and infrastructure helpers in durable/support/.
- external/cloudflare/: Cloudflare facades and generated RPC interfaces; hash/ and util/ contain hashing and encoding utilities.

The server and browser Scala.js projects link separately. Do not introduce dependencies from bbsClient to bbs or vice versa, or ship server logic and secrets to browsers.

## 3. Roadmap and current priorities

### Completed foundation

The vanilla JS to Scala.js/HonoX migration and extraction of reusable integration infrastructure into hx4s are complete as a foundation. Some legacy presentation and dispatch paths remain intentionally for compatibility. Do not restart the migration or introduce Next.js as the default UI stack.

### Phase 1 — Essential features, quality and refactoring (through the end of 2026)

- Finish authentication, accounts, posting restrictions and management functionality. Authentication code exists on develop, but this does not establish production rollout or completion of all requirements. Finalize the authentication/account schema and migration (#75) before production authentication rollout; dedicated-browser bootstrap/session and abuse-control redesigns are #77 and #78.
- Adopt handler-owned declarative routing and the layered/DDD-oriented structure (#73, #76); remove obsolete dispatch paths only after verifying equivalent behavior.
- Continue the public Web UI migration (#17, #31), admin SPA migration (#10), and core features such as the thread feed (#12). Remove public placeholders and unused migration scaffolding (#45), without deleting planned functionality from the backlog. Remove 3chiko.html only after all dependent routes have replacements.
- Improve reliability, compatibility, bundle size, CI and regression coverage (#43, #50, #53, #56, #59, #68). Keep existing working features intact.
- Implement approved posting identifier changes through their own issues (#58, #74), not as incidental changes.

Preserve deployed storage formats during unrelated changes. The authentication schema is an explicit exception to the former blanket rule against database changes in this phase. Other storage work requires its own migration plan.

### Phase 2 — Storage modernization and legacy archive import (late 2026 / early 2027, provisional)

See #14, #15, #72 and #27. The approved direction in #72 is:

- SQLite-backed per-thread DOs as the authority for posts and thread state; board DOs for board-local ordering, configuration and restrictions.
- D1 directories, catalogs/search indexes and bounded feed projections for global queries, maintained asynchronously and idempotently; never treat an eventually consistent projection as the authoritative posting or credential store.
- R2 for daily historical feed snapshots under /kako/, legacy source archives and static exports. Do not evict old thread bodies from authoritative DO storage as a cold-tier design.
- No automatic dat-drop or posting closure. Listing visibility and writability are different concepts; age-based bump restrictions are planned but thresholds are not yet fixed.
- Support tag-based virtual boards while retaining canonical physical board/thread identities. Retain public posts separately from private posting audit data (approximately three months under #72).
- Finalize schema, migration and reconciliation procedures before bulk importing legacy dat files. Preserve numbering, URLs, encodings and existing data; do not fabricate missing historical private metadata or historical state.

### Phase 3 — Differentiating features (2027, provisional)

Thread-title search (#18; may be implemented during Phase 2), ActivityPub federation (#11) and Workers AI-assisted moderation (#19). Establish the interoperability, authorization, privacy and failure-handling contracts before implementation. These are planned features, not current capabilities.

Dates are planning targets, not release guarantees. Issue requirements and dependencies take precedence over old step numbers and outdated handoff dates.

## 4. Architecture and coding conventions

### Design principles

Prefer FP > OOP > DDD. Use functional programming for domain rules, object-oriented encapsulation where useful, and DDD to establish meaningful boundaries. Avoid mechanical layering, speculative abstractions and large procedural services.

- HTTP flow: Router -> Handler -> Service/use case. Services apply domain rules and depend on typed ports; repositories implement those ports and adapt DOs, databases and other storage. Domain models must not depend on transport or storage implementations. Lower layers must not import upper layers except to implement explicitly defined ports.
- Use the existing service/ package for use cases rather than introducing a parallel application/ layer. Keep existing package names unless carrying out the deliberate migration in #73. As the application grows, separate handlers, views, reusable components/layouts, services, domain and repositories; cohesive vertical slices are acceptable when justified.
- Handlers parse and validate transport input, perform HTTP-level authorization and map typed outcomes to responses. Services coordinate work; domain logic makes business decisions. Keep user-facing messages out of the persistence layer.
- Use immutable values and collections, case classes, enums, Option, Either, pattern matching and standard Scala combinators. Represent expected business rejection as a value instead of an exception. Pass clocks, randomness and external effects through explicit boundaries. Do not read the current time inside a pure domain rule.
- Prefer Scala.js libraries over equivalent npm packages when a suitable Scala.js library exists. Use TypeScript/JavaScript for necessary integration and tooling, not for logic that can be expressed in Scala.
- Use explicit types at external boundaries. Avoid TypeScript any, untyped Scala structures, unchecked casts and spreading js.Dynamic through the application. Decode dynamic inputs into typed models at the boundary.
- Use braces for multiline Scala blocks by default. Scala 3 if/then and while/do are allowed; optional-braces indentation syntax is reserved for clearly more readable cases. Ordinary match case bodies need not be braced. Do not enforce -no-indent globally; the previous migration check was `sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile`.
- Keep comments and Scaladoc/JSDoc concise and useful, normally in Japanese to match existing code, without a final Japanese full stop in Scaladoc. Document public APIs and constraints; put rationale, benchmarks and migration history in docs/ rather than lengthy code comments or task-number annotations. Avoid unnatural generated prose.
- Fix compiler/typecheck/lint warnings at their source; do not hide them or weaken compiler/test settings merely to pass. Scalafmt/WartRemover adoption is tracked in #68; follow the actual configured tools.

### Durable Objects and persistence

DOs are infrastructure boundaries, not the home of business rules.

- Keep storage layout, serialization, RPC conversion, concurrency control and external infrastructure access in DOs. Put state transitions, retention decisions, restriction checks and expiry calculations into pure typed domain code.
- A typical operation reads the clock once, serializes/locks as required, loads and decodes state, invokes domain logic, encodes and persists the result. Keep transactions within their actual storage boundary; do not assume cross-DO atomicity. Use idempotency and reconciliation where multiple stores are involved.
- Do not manipulate business state as js.Dynamic in the DO. Keep codec and infrastructure helpers separate from domain models. Return typed outcomes or stable RPC codes, not HTML/user-facing text.
- Changes to DO class migrations, storage keys, schemas or encodings require an explicit migration and compatibility strategy. Never wipe or rewrite production data to simplify a migration. Test failures, retries, concurrency and recovery.
- Test domain behavior independently of DO infrastructure. Use integration tests for storage semantics, atomicity and runtime behavior.

### hx4s, HonoX and generated code

- Current build.sbt restricts hx4sServerSources and hx4sClientSources to the two Main.scala files. Route annotations and island declarations therefore live there today. Moving routes to handlers (#73) requires updating generation configuration, not just moving annotations. Authentication still has manual dispatch in app/server.ts and AuthHandler (#76).
- Use supported @route / @islands[P] declarations and @JSExportTopLevel exports. Route/island extensions are optional in current hx4s. Preserve public paths, HTTP methods, trailing-slash and fallback semantics during migration.
- Declare island props as shared typed JS traits in common/src/main/scala-js/io/_3ch/common/islands/. Use the shared fields/validation contract, typed IslandExport[P] and generated IslandComponents. Props must be serializable; use js.UndefOr only for truly optional fields, and do not pass functions, Scala collections or private server data across hydration. Verify SSR and hydration compatibility. Do not claim that runtime descriptors remove every possible schema drift.
- Generated app/routes/, app/islands/, app/scalajs-durable-objects.generated.d.ts and managed Scala sources must never be edited by hand. Modify source declarations and regenerate instead. The current repository tracks generated app files and tests them against regenerated output; #43 proposes changing this. Until that issue is implemented, commit regenerated tracked files when applicable. Do not broadly ignore app/ or remove handwritten files.
- Keep app/server.ts, app/client.ts, renderer, CSS and handwritten routes intact unless the task requires a verified migration. Check generation markers before deleting anything.
- Framework-general changes belong in hx4s; 3ch-specific DO generation remains in codegen/. Update the pinned hx4s dependency deliberately and verify both sides when changing a framework contract.

### Web UI

- Use HonoX SSR and scalajs-react for interactive islands; avoid turning the public site into a full SPA by default. The admin UI is intended to be a scalajs-react SPA.
- Prefer ScalaFnComponent/function components, hooks for state and effects, and final val for component definitions. Keep effects and cleanup explicit. Reuse existing layouts and components; avoid duplicate client-side and server-side business logic.
- Follow current UI conventions and docs/ui/ mockups where applicable. Use square corners rather than border-radius unless a design explicitly changes this. Use natural Japanese UI text, consistent spacing, responsive CSS, and preserve accessibility and basic non-JS functionality where supported.
- Do not expose unimplemented features as public placeholders. Do not add speculative components, exports or routes. Verify references through generated code, RPC and tests before removing apparently unused code.
- Preserve compatibility and avoid duplicate submissions, stale hydration and unnecessary client/server bundle growth.

## 5. Compatibility, security and privacy

- Preserve Monazilla protocol compatibility: dat has five fields separated by <>, including spaces around the message field; preserve subject format, posting response markers (including HTTP 200 and <!-- 2ch_X:error --> on protocol errors), Range and conditional 304 behavior.
- Preserve Shift_JIS output for Monazilla clients and the existing Web UTF-8 behavior. Consult the encoding implementation, tests and docs/sjis差異一覧.md before changing conversions. Do not apply a blanket UTF-8 migration to legacy endpoints.
- Preserve stable board/thread IDs, reply numbering, legacy URLs and stored content. Compatibility changes require an explicit issue and regression tests.
- Treat request data, headers and client/device claims as untrusted unless verified at the relevant boundary. Retain server-side authorization, CSRF/Origin checks, Turnstile verification, cookie signing, revocation, rate limits and input/output validation. Do not use client UI state as authorization.
- Authentication behavior may be configuration-gated. Distinguish a code path being implemented from external setup being complete. Google OAuth, mail delivery and production verification require appropriate configuration; do not pretend that provider setup or deployment has been verified.
- Do not expose raw credentials, tokens, private identifiers or audit information in HTML, island props, logs, commits, issue text or external agent prompts. Never copy salt, ADMIN_PASSWORD, AUTH_SECRET or other sensitive configuration values out of their intended scope. Use local ignored .dev.vars files and Wrangler secrets; never commit actual secret values.
- New public derived posting identifiers should follow approved daily (JST), board-scoped keyed derivation requirements; nicknames are not unique IDs or authentication credentials. Do not silently change historical dat or invent unconfirmed identifier formats.
- Use isolated local/test storage. Do not deploy, run production migrations or issue destructive production operations without explicit authorization.

## 6. Development and verification

CI currently uses JDK 21, Node.js 22 and sbt on Ubuntu and Windows. Use a Node.js version compatible with the pinned Vite release. Read docs/development.md and the actual build files before setup.

hx4s is currently consumed from a pinned local publication, not a registry release. Clone MontereyLangit/hx4s next to this repository (or specify HX4S_DIR), check out the exact commit in hx4s.lock with a clean worktree, then run:

```bash
npm run hx4s:setup
npm run v6:build
npm run v6:typecheck
sbt test
npm test
```

- hx4s:setup verifies the pinned commit/version and local artifacts, publishes as necessary, installs npm dependencies and the local hx4s-vite tarball. Running npm ci alone removes hx4s-vite; use the setup script instead.
- v6:generate generates route/island files and DO TypeScript declarations; v6:build runs generation followed by client and server Vite builds.
- npm run dev builds and starts local Wrangler (normally http://127.0.0.1:8787). Use local settings according to docs/auth-integration.md and docs/admin.md.
- sbt test runs munit unit tests across the Scala modules, including golden fixtures. Add domain unit tests for changed rules, boundary values, rejection precedence and compatibility.
- npm test runs pretest (Scala.js test/client linking and generatedAppCheck), followed by Node tests. Workerd integration tests require built dist/; they cover HTTP, DO RPC, posting, auth and storage atomicity. Other tests verify SSR, hydration and generated files.
- generatedAppCheck writes temporary output under target/generated-app-check, not app/. It changes sbt session settings, so keep it last in that invocation. After #43, follow the replacement generation tests.
- CI currently builds, typechecks, runs pretest and Node tests, and checks development startup on Ubuntu/Windows. Do not assume it runs the full sbt test task or Playwright; E2E coverage is tracked in #53.
- npm run deploy builds and deploys via Wrangler; use it only with explicit deployment authorization.

Use focused checks while iterating, then relevant full verification before handoff. For code affecting generated wiring, run generation and review the diff, including preservation of handwritten files. Keep line endings stable across platforms. Report commands actually run, results and any unverified behavior. Do not mark tests as passed based on old reports.

## 7. Documentation map

Read only the task-relevant documents, then verify against source, tests and newer approved issues. Some documents preserve historical plans and may have obsolete implementation status.

| Document | Purpose |
| --- | --- |
| docs/現行システム仕様書.md | Historical v5 behavioral baseline and known compatibility differences; not a complete current v6 specification. |
| docs/既知不具合台帳.md | Known bug register (KB-nn); record newly discovered issues and link relevant fixes. |
| docs/v6s実装メモ.md | Scala.js implementation background. |
| docs/sjis差異一覧.md | Shift_JIS compatibility rationale. |
| docs/トリップ・ID廃止とスレ主表示.md | Tripcode/date-field ID removal, !id and thread-owner display. |
| docs/auth-phoebe-rate-limit-design.md | Authentication, PHOEBE levels, posting restrictions and UI design. |
| docs/claude-handoff.md | Historical authentication implementation handoff and remaining work; newer issues may supersede plans. |
| docs/auth-integration.md | Auth configuration, local development, external providers and integration checks. |
| docs/auth-implementation-review.md, docs/auth-password-implementation-notes.md, docs/auth-phoebe-review.md | Auth reviews and implementation rationale. |
| docs/admin.md, docs/admin-spa-auth-plan.md, docs/admin-spa-auth-review.md | Admin operations and migration design. |
| docs/bbs-cgi-ddd-refactoring-plan.md, docs/bbs-cgi-fp-refactoring-plan.md | Posting pipeline refactoring and domain/service boundaries. |
| docs/thread-creation-regulation.md, docs/global-thread.md, docs/post-rate-limit.md | Thread creation, global threads and rate limits. |
| docs/development.md, docs/codegen.md, docs/islands-ssr.md | Setup, generation, shared props and rendering conventions. |
| docs/durable-object-stubs.md, docs/hx4s-foundation-review.md | DO RPC generation and framework extraction background. |
| docs/ui/ | UI prototypes and design references, not necessarily shipped functionality. |

## 8. Agent workflow and review

1. Inspect the relevant issue, requirements, existing code, tests and git status. Identify compatibility constraints and proposed versus confirmed decisions.
2. Break implementation into reviewable tasks. Delegate implementation to subagents when supported, unless instructed otherwise; provide self-contained scope, file paths, specifications and acceptance criteria. The orchestrator owns integration and review. If delegation is unavailable, perform the work directly and report that limitation when relevant.
3. Make the smallest coherent change that meets the requirements. Preserve unrelated changes. Avoid scope creep and do not add features merely because they appear in the roadmap.
4. Verify the design and implementation, including UI design before coding. Review layer boundaries, Scala idioms, immutability, typed interfaces, auth/privacy, storage invariants, Monazilla compatibility, SSR/hydration, UI consistency, unnecessary exports and test coverage.
5. Run appropriate checks, inspect the final diff, and update relevant docs and issue references. Report outstanding risks, decisions and unrun tests. Use reviewable commits/PRs; do not merge or deploy unless authorized.

Keep this guide focused on stable instructions and the high-level roadmap. Do not turn it into a duplicate issue tracker, a detailed schema specification, or a chronological implementation log.
