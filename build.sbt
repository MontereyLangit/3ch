// v6s: Scala 3 / Scala.js x HonoX バックエンド（docs/移植方針_step3_ScalaJS.md）。
// 参照実装 C:/project/3ch/ch3s-migration の build.sbt をそのまま踏襲する
// （common: crossProject 純粋 / bbs: JS-only Workers本体 / bbs-client: JS-only islands /
//   codegen: JVM-only ファイルI/O）。BBSロジックはbbs、純粋な暗号・文字コード処理はcommon。
// bg（バックグラウンドサービス、http4s/tapir）は step 5 のスコープなのでこの骨格には含めない。

import org.scalajs.linker.interface.ModuleSplitStyle

val scala3Version = "3.3.8" // Scala 3 LTS
val munitVersion  = "1.0.0"

ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "ch3s"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
  libraryDependencies += "org.scalameta" %%% "munit" % munitVersion % Test,
  testFrameworks += new TestFramework("munit.Framework")
)

// --- common: cross project (JVM + Scala.js) ---
// 純粋関数のみ（I/O禁止）。RouteTable（ルーティング表の唯一の真実）をここに置く。
lazy val common = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("common"))
  .settings(commonSettings)
  .jvmSettings(
    // golden入出力表 (tests/golden/*.json) を読むテスト専用のJVM実装 (java.io を使う。
    // 本番コードはcommon/src/main配下だけなので「pure/no I/O」方針には反しない)。
    // CrossType.Pure は src/{main,test}/scala を共有するため、プラットフォーム別の
    // 追加ソースディレクトリはここで明示的に足す。
    Test / unmanagedSourceDirectories += baseDirectory.value / ".." / "src" / "test" / "scala-jvm"
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := false,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    // golden入出力表を読むテスト専用のJS実装 (Node の fs を使う。sbt-scalajsのテストは
    // 既定でNodeJSEnv上で実行されるため利用できる)。
    Test / unmanagedSourceDirectories += baseDirectory.value / ".." / "src" / "test" / "scala-js"
  )

lazy val commonJVM = common.jvm
lazy val commonJS  = common.js

// --- bbs: Scala.js — Durable Object と、HonoXから呼ばれるサーバ側ロジック（Workersバンドル専用） ---
lazy val bbs = project
  .in(file("bbs"))
  .enablePlugins(ScalaJSPlugin)
  // "test->test" も繋いでおく: bbs の golden テスト（WebHashSuite 等）が、common 側で
  // 自己完結して用意した golden JSON パーサ/ローダ (ch3s.common.golden.{Json, GoldenFile})
  // をテストだけ再利用できるようにするため
  .dependsOn(commonJS % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    scalaJSUseMainModuleInitializer := false,
    libraryDependencies += "com.github.japgolly.scalajs-react" %%% "core" % "4.0.0",
    // ブラウザへサーバ処理を混入させないため、bbsClient とは別にリンクする
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    // テストが Date / Math.random / caches 等のグローバルを一時的に差し替えるため、スイート間は直列に実行する
    Test / parallelExecution := false,
    // 参照実装からの既知の地雷: `@JSImport("cloudflare:workers", "DurableObject")` を extends した
    // クラスが常にexportされるため、Testのリンク成果物にも巻き込まれる。素の NodeJSEnv では
    // cloudflare: スキームを解決できず Test 全体が起動できないため、Node の Module customization
    // hooks（--experimental-loader）で cloudflare:workers の解決だけをスタブへ差し替える
    Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(
      org.scalajs.jsenv.nodejs.NodeJSEnv.Config()
        .withArgs(
          List(
            "--import",
            (baseDirectory.value / "test-stubs" / "register.mjs").toURI.toString
          )
        )
    )
  )

// --- bbs-client: Scala.js — ブラウザへ配る island コンポーネント（今回は配線のみの空実装） ---
lazy val bbsClient = project
  .in(file("bbs-client"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(commonJS)
  .settings(commonSettings)
  .settings(
    scalaJSUseMainModuleInitializer := false,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
  )

// --- codegen: JVM — RouteTable(common) から app/routes/** を機械生成するsbtタスク ---
lazy val codegen = project
  .in(file("codegen"))
  .dependsOn(commonJVM)
  .settings(commonSettings)

lazy val root = project
  .in(file("."))
  .aggregate(commonJVM, commonJS, bbs, bbsClient, codegen)
  .settings(
    publish / skip := true
  )
