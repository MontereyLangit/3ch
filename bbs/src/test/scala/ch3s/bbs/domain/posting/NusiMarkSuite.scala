package ch3s.bbs.domain.posting

import munit.FunSuite

class NusiMarkSuite extends FunSuite {
  private val owner = ThreadOwner("ip1", "shiki1", "")
  private val sameIp = ThreadOwner("ip1", "", "")
  private val stranger = ThreadOwner("ip2", "shiki2", "acc2")

  private def applies(isReply: Boolean = true, cap: Boolean = false, noNusi: Boolean = false, boardDisabled: Boolean = false,
                      threadDisabled: Boolean = false, stored: Option[ThreadOwner] = Some(owner), poster: ThreadOwner = sameIp): Boolean =
    NusiMark.applies(isReply, cap, noNusi, boardDisabled, threadDisabled, stored, poster)

  test("返信・owner一致なら付く") {
    assert(applies())
  }

  test("owner と一致しなければ付かない") {
    assert(!applies(poster = stranger))
  }

  test(">>1 には付かない") {
    assert(!applies(isReply = false))
  }

  test("キャップには付かない") {
    assert(!applies(cap = true))
  }

  test("!no があれば付かない") {
    assert(!applies(noNusi = true))
  }

  test("板設定またはスレ設定で無効なら付かない") {
    assert(!applies(boardDisabled = true))
    assert(!applies(threadDisabled = true))
  }

  test("owner の無いスレには付かない") {
    assert(!applies(stored = None))
  }

  test("接尾辞は半角スペース+(主)") {
    assertEquals(NusiMark.suffix, " (主)")
  }
}
