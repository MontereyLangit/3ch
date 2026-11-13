package io._3ch.bbsclient.islands

import munit.FunSuite
import scala.scalajs.js

class BrowserFingerprintCollectorSuite extends FunSuite {
  private def withBrowser(contextAvailable: Boolean)(check: js.Dynamic => Unit): Unit = {
    val global = js.Dynamic.global.globalThis
    val previousWindow = global.selectDynamic("window")
    val previousDocument = global.selectDynamic("document")
    val context = js.Dynamic.literal(font = "")
    val measureText: js.Function1[String, js.Object] = (text: String) => {
      assertEquals(text, "mmmmmmmmmmlliWWMMWW漢字ぁアg")
      val font = context.font.asInstanceOf[String]
      assert(font.startsWith("72px "))
      js.Dynamic.literal(width = (if (font.contains("'Meiryo'")) 11d else 10d))
    }
    context.updateDynamic("measureText")(measureText)
    val getExtension: js.Function1[String, js.Object] = (name: String) => {
      assertEquals(name, "WEBGL_debug_renderer_info")
      js.Dynamic.literal(UNMASKED_RENDERER_WEBGL = 37446)
    }
    val getParameter: js.Function1[Int, String] = (parameter: Int) => {
      assertEquals(parameter, 37446)
      "Apple GPU"
    }
    val gl = js.Dynamic.literal(getExtension = getExtension, getParameter = getParameter)
    val getContext: js.Function1[String, js.Any] = (name: String) => {
      if (!contextAvailable) null
      else if (name == "2d") context
      else gl
    }
    val canvas = js.Dynamic.literal(getContext = getContext)
    val createElement: js.Function1[String, js.Object] = (name: String) => {
      assertEquals(name, "canvas")
      canvas
    }
    global.updateDynamic("document")(js.Dynamic.literal(createElement = createElement))
    global.updateDynamic("window")(js.Dynamic.literal(
      navigator = js.Dynamic.literal(userAgent = "UA", hardwareConcurrency = 8, deviceMemory = 0.5, maxTouchPoints = 5, platform = "MacIntel"),
      screen = js.Dynamic.literal(width = 393, height = 852)))
    try {
      check(BrowserFingerprintCollector.collect().asInstanceOf[js.Dynamic])
    } finally {
      global.updateDynamic("window")(previousWindow)
      global.updateDynamic("document")(previousDocument)
    }
  }

  test("原本のフォント順・計測条件とnavigatorの生値を採取する") {
    withBrowser(true) { fp =>
      assertEquals(fp.os.asInstanceOf[String], "UA")
      assertEquals(fp.arch.asInstanceOf[String], "MacIntel")
      assertEquals(fp.screen.asInstanceOf[String], "393x852")
      assertEquals(fp.cores.asInstanceOf[Int], 8)
      assertEquals(fp.ram.asInstanceOf[Int], 0)
      assertEquals(fp.dpr.asInstanceOf[Double], 1d)
      assertEquals(fp.colorDepth.asInstanceOf[Int], 24)
      assertEquals(fp.touch.asInstanceOf[Boolean], true)
      assertEquals(fp.webgl.asInstanceOf[String], "Apple GPU")
      assertEquals(fp.fonts.asInstanceOf[String], "001000000000000")
    }
  }

  test("canvasを使えないときはその軸だけ欠損にする") {
    withBrowser(false) { fp =>
      assertEquals(fp.webgl.asInstanceOf[String], "")
      assertEquals(fp.fonts.asInstanceOf[String], "")
      assertEquals(fp.screen.asInstanceOf[String], "393x852")
    }
  }
}
