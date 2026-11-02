package ch3s.bbs.domain.posting

import munit.FunSuite

class ThreadOwnerSuite extends FunSuite {
  private val owner = ThreadOwner("ip1", "shiki1", "acc1")

  test("空でない項目が1つでも一致すれば true") {
    assert(owner.matches(ThreadOwner("ip1", "x", "y")))
    assert(owner.matches(ThreadOwner("x", "shiki1", "y")))
    assert(owner.matches(ThreadOwner("x", "y", "acc1")))
  }

  test("どれも一致しなければ false") {
    assert(!owner.matches(ThreadOwner("x", "y", "z")))
  }

  test("空の項目同士は一致とみなさない") {
    assert(!ThreadOwner("", "", "").matches(ThreadOwner("", "", "")))
    assert(!ThreadOwner("ip1", "", "").matches(ThreadOwner("x", "", "")))
    assert(!ThreadOwner("ip1", "", "acc1").matches(ThreadOwner("x", "", "y")))
  }

  test("保存側が空でも投稿者側の空と一致しない") {
    assert(!ThreadOwner("", "", "acc1").matches(ThreadOwner("ip9", "", "")))
  }
}
