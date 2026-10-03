package ch3s.common

/**
 * `common` が JVM/JS 両方でコンパイル・テストできることを示すための最小の純粋関数。
 * 参照実装 ch3s-migration の `Greeting.scala` と同じ役割（v6s骨格ではBBSロジックを
 * まだ移植しないため、ロジックそのものにはBBS上の意味を持たせない）。
 * 
 * TODO: 削除
 */
object Greeting {
  def message(name: String): String = s"Hello, $name! (ch3s v6s skeleton)"
}
