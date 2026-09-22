package ch3s.bbsclient

import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * ブラウザへ配る island コンポーネントの置き場（`bbs-client`）。v6s骨格の時点では
 * 表示すべきBBS画面がまだ無いため、`@JSExportTopLevel(name, "client")` の配線
 * （`client.js` モジュールへ実際にexportされること）だけを証明する最小関数を置く。
 *
 * ソースは `build.sbt` の設定で `bbs` プロジェクトの `unmanagedSourceDirectories` にも
 * 取り込まれ、同一sbtプロジェクト内で `moduleID = "client"` としてコンパイルされる
 * （参照実装 ch3s-migration の単一sbtプロジェクトパターン。sbtの二重起動を避けるため）。
 */
object ClientEntry:

  @JSExportTopLevel("v6ClientPing", "client")
  def v6ClientPing(): String = "pong-from-client"
