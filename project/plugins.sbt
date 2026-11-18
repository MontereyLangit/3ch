// TODO: v6s skeleton (step 3 of docs/移植方針_step3_ScalaJS.md). Mirrors ch3s-migration's
// project/plugins.sbt (reference implementation) verbatim: no ScalablyTyped, hand-written
// @js.native facades only.
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

// hx4s は scripts/setup-hx4s.mjs が hx4s.lock の版で ~/.ivy2/local に公開したものを使う（docs/development.md）
val hx4sLockedVersion: String = {
  val properties = new java.util.Properties
  IO.load(properties, file("hx4s.lock"))
  Option(properties.getProperty("version")).getOrElse(sys.error("hx4s.lock has no version"))
}
addSbtPlugin("io.github.montereylangit" % "sbt-hx4s" % hx4sLockedVersion)
