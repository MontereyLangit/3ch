package io._3ch.bbsclient.islands

import org.scalajs.dom
import scala.scalajs.js
import scala.util.Try

/** 認証用の端末情報を採取する。取得できない軸は既定値にする */
object BrowserFingerprintCollector {
  private final val FontCandidates = Vector("MS Gothic", "MS Mincho", "Meiryo", "Yu Gothic", "Hiragino Kaku Gothic ProN",
    "Hiragino Mincho ProN", "Noto Sans CJK JP", "Osaka", "Segoe UI", "Roboto", "Arial", "Courier New", "Georgia", "Tahoma", "Verdana")
  private final val FontBaseFamilies = Vector("monospace", "sans-serif", "serif")
  private final val FontProbeText = "mmmmmmmmmmlliWWMMWW漢字ぁアg"
  private final val FontProbeSize = "72px"

  private def navigatorNumber(name: String, default: Double): Double = Try {
    val raw = dom.window.navigator.asInstanceOf[js.Dynamic].selectDynamic(name)
    number(raw, default)
  }.getOrElse(default)

  private def number(raw: js.Any, default: Double): Double =
    if (js.typeOf(raw) == "number" && raw.asInstanceOf[Double].isFinite) raw.asInstanceOf[Double] else default

  private def renderer(): String = Try {
    val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
    val gl = Option(canvas.getContext("webgl")).orElse(Option(canvas.getContext("experimental-webgl")))
    gl.fold("") { context =>
      val dynamic = context.asInstanceOf[js.Dynamic]
      val extension = dynamic.getExtension("WEBGL_debug_renderer_info")
      if (extension == null || js.isUndefined(extension)) ""
      else dynamic.getParameter(extension.UNMASKED_RENDERER_WEBGL).asInstanceOf[String]
    }
  }.getOrElse("")

  /** 候補の順序・計測文字列・サイズは端末ハッシュの材料仕様 */
  private def fonts(): String = Try {
    val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
    Option(canvas.getContext("2d")).fold("") { raw =>
      val context = raw.asInstanceOf[dom.CanvasRenderingContext2D]
      def measure(font: String): Double = {
        context.font = font
        context.measureText(FontProbeText).width
      }
      val baseWidths = FontBaseFamilies.map(family => measure(s"$FontProbeSize $family"))
      FontCandidates.map { candidate =>
        val available = FontBaseFamilies.zipWithIndex.exists { (family, index) =>
          measure(s"$FontProbeSize '$candidate',$family") != baseWidths(index)
        }
        if (available) '1' else '0'
      }.mkString
    }
  }.getOrElse("")

  /** osはnavigator.userAgent、archはnavigator.platformをそのまま送る */
  def collect(): js.Object = js.Dynamic.literal(
    os = Try(dom.window.navigator.userAgent).getOrElse(""),
    cores = navigatorNumber("hardwareConcurrency", 0).toInt,
    ram = navigatorNumber("deviceMemory", 0).toInt,
    screen = Try(s"${dom.window.screen.width}x${dom.window.screen.height}").getOrElse(""),
    dpr = Try(number(dom.window.asInstanceOf[js.Dynamic].devicePixelRatio, 1d)).getOrElse(1d),
    colorDepth = Try(number(dom.window.screen.asInstanceOf[js.Dynamic].colorDepth, 24).toInt).getOrElse(24),
    webgl = renderer(), fonts = fonts(), touch = navigatorNumber("maxTouchPoints", 0) > 0,
    arch = Try(Option(dom.window.navigator.platform).getOrElse("")).getOrElse(""))
}
