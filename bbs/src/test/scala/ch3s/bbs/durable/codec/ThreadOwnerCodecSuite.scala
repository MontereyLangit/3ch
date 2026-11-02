package ch3s.bbs.durable.codec

import ch3s.bbs.domain.posting.ThreadOwner
import munit.FunSuite
import scala.scalajs.js

class ThreadOwnerCodecSuite extends FunSuite {
  test("encode した値を decode すると元に戻る") {
    val owner = ThreadOwner("iph", "JP.x", "acc")
    assertEquals(ThreadOwnerCodec.decode(ThreadOwnerCodec.encode(owner)), owner)
  }

  test("欠けた項目は空文字として読む") {
    assertEquals(ThreadOwnerCodec.decode(js.Dynamic.literal(ip = "iph")), ThreadOwner("iph", "", ""))
    assertEquals(ThreadOwnerCodec.decode(js.Dynamic.literal()), ThreadOwner("", "", ""))
  }
}
