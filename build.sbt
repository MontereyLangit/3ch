// v6s: Scala 3 / Scala.js x HonoX バックエンド（docs/移植方針_step3_ScalaJS.md）。
// 参照実装 C:/project/3ch/ch3s-migration の build.sbt をそのまま踏襲する
// （common: crossProject 純粋 / bbs: JS-only Workers本体 / bbs-client: JS-only islands /
//   codegen: JVM-only ファイルI/O）。BBSロジックはbbs、純粋な暗号・文字コード処理はcommon。
// bg（バックグラウンドサービス、http4s/tapir）は step 5 のスコープなのでこの骨格には含めない。

import org.scalajs.jsenv.nodejs.NodeJSEnv

val scala3Version = "3.3.8" // Scala 3 LTS
val munitVersion  = "1.0.0"

ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "io._3ch"
ThisBuild / version      := "1.2.1"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
  libraryDependencies += "org.scalameta" %%% "munit" % munitVersion % Test,
  testFrameworks += new TestFramework("munit.Framework")
)

// --- common: cross project (JVM + Scala.js) ---
// 純粋関数のみ（I/O禁止）。公開パスの互換パターンと配線生成用アノテーションをここに置く。
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

// DO の公開メソッドから RPC スタブを生成する（docs/durable-object-stubs.md）。bbs の sourceGenerators から呼ぶ。
// 生成器は JVM の codegen にあり、bbs のソースだけを入力に取るのでタスクグラフは循環しない。
val durableObjectStubsFile = settingKey[File]("生成する RPC スタブ（src_managed 配下）")
val generateDurableObjectStubs = taskKey[Seq[File]]("DO の公開メソッドから RPC スタブを生成する")

lazy val generateDurableObjectStubsTask: Def.Initialize[Task[Seq[File]]] = Def.taskDyn {
  val durableDir = (Compile / scalaSource).value / "io" / "_3ch" / "bbs" / "durable"
  val output = (Compile / durableObjectStubsFile).value
  // spaceDelimitedが解釈する引用符とWindowsパスのバックスラッシュをエスケープする
  def quotedPath(path: File): String = "\"" + path.getAbsolutePath.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  val arguments = s" io._3ch.codegen.GenerateDurableObjectStubsMain ${quotedPath(durableDir)} ${quotedPath(output)}"
  Def.task {
    (codegen / Compile / runMain).toTask(arguments).value
    Seq((Compile / durableObjectStubsFile).value)
  }
}

// --- bbs: Scala.js — Durable Object と、HonoXから呼ばれるサーバ側ロジック（Workersバンドル専用） ---
lazy val bbs = project
  .in(file("bbs"))
  .enablePlugins(ScalaJSPlugin)
  // "test->test" も繋いでおく: bbs の golden テスト（WebHashSuite 等）が、common 側で
  // 自己完結して用意した golden JSON パーサ/ローダ (io._3ch.common.golden.{Json, GoldenFile})
  // をテストだけ再利用できるようにするため
  .dependsOn(commonJS % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    Compile / sourceGenerators += Def.task {
      val output = (Compile / sourceManaged).value / "io" / "_3ch" / "bbs" / "BuildInfo.scala"
      val updatedDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).toString
      IO.write(output, s"""package io._3ch.bbs
                         |object BuildInfo {
                         |  val version = "${version.value}"
                         |  val updatedDate = "$updatedDate"
                         |}
                         |""".stripMargin)
      Seq(output)
    }.taskValue,
    scalaJSUseMainModuleInitializer := false,
    libraryDependencies += "com.github.japgolly.scalajs-react" %%% "core" % "4.0.0",
    // ブラウザへサーバ処理を混入させないため、bbsClient とは別にリンクする
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    // DO の公開メソッドから RPC スタブ（BoardDoStub 等）を生成する（上記 generateDurableObjectStubsTask）
    Compile / durableObjectStubsFile := (Compile / sourceManaged).value / "io" / "_3ch" / "bbs" / "external" / "cloudflare" / "DurableObjectStubs.scala",
    Compile / generateDurableObjectStubs := generateDurableObjectStubsTask.value,
    Compile / sourceGenerators += (Compile / generateDurableObjectStubs).taskValue,
    // テストが Date / Math.random / caches 等のグローバルを一時的に差し替えるため、スイート間は直列に実行する
    Test / parallelExecution := false,
    // 参照実装からの既知の地雷: `@JSImport("cloudflare:workers", "DurableObject")` を extends した
    // クラスが常にexportされるため、Testのリンク成果物にも巻き込まれる。素の NodeJSEnv では
    // cloudflare: スキームを解決できず Test 全体が起動できないため、Node の Module customization
    // hooks（--import で module.register）で cloudflare:workers の解決だけをスタブへ差し替える
    // island が import する scalajs:client.js を bbsClient のリンク成果物へ解決する
    // （loader が CH3S_CLIENT_JS_DIR を読む）。リンクはテスト実行・Test/fastLinkJS の前に行う
    Test / jsEnv := {
      val clientDir = (bbsClient / Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
      (bbsClient / Compile / fastLinkJS).value
      new NodeJSEnv(
        NodeJSEnv.Config()
          .withArgs(
            List(
              "--import",
              (baseDirectory.value / "test-stubs" / "register.mjs").toURI.toString
            )
          )
          .withEnv(Map("CH3S_CLIENT_JS_DIR" -> clientDir.getAbsolutePath))
      )
    },
    Test / fastLinkJS := (Test / fastLinkJS).dependsOn(bbsClient / Compile / fastLinkJS).value
  )

// --- bbs-client: Scala.js — ブラウザへ配る island コンポーネント（ブラウザ側ロジックは TS でなくここに置くこと） ---
lazy val bbsClient = project
  .in(file("bbs-client"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(commonJS)
  .settings(commonSettings)
  .settings(
    scalaJSUseMainModuleInitializer := false,
    libraryDependencies += "com.github.japgolly.scalajs-react" %%% "core" % "4.0.0",
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
  )

// --- codegen: JVM — Main のアノテーションから配線、DO の Scala 宣言から TS 型と Scala RPC スタブを生成 ---
lazy val codegen = project
  .in(file("codegen"))
  .dependsOn(commonJVM)
  .settings(commonSettings)
  .settings(
    libraryDependencies += ("org.scalameta" %% "scalameta" % "4.17.4").cross(CrossVersion.for3Use2_13),
    // `sbt codegen/run`（npm run v6:generate）はルートと TS 型の生成。Scala スタブは bbs の sourceGenerators から runMain で呼ぶ
    Compile / mainClass := Some("io._3ch.codegen.GenerateAppFiles")
  )

lazy val root = project
  .in(file("."))
  .aggregate(commonJVM, commonJS, bbs, bbsClient, codegen)
  .settings(
    name := "3ch",
    publish / skip := true
  )
