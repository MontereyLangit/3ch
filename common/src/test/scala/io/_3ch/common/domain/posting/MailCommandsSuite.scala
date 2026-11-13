package io._3ch.common.domain.posting

class MailCommandsSuite extends munit.FunSuite {
  test("キャップのパスワードは CapHandle と同じく次の # の手前まで読み、その部分をメール欄から除く") {
    val commands = MailCommands.parse("sage#pass word")
    assertEquals(commands.capPassword, Some("pass word"))
    assertEquals(commands.capPassword, CapHandle.password("sage#pass word"))
    assertEquals(commands.plain, "sage")
    assertEquals(MailCommands.parse("#pass#sage").plain, "#sage")
    assertEquals(MailCommands.parse("#pass#sage").capPassword, Some("pass"))
    assertEquals(MailCommands.parse("#").capPassword, None)
    assertEquals(MailCommands.parse("#").plain, "")
  }
  test("専ブラキーは最初の !# から空白の手前までで、すべての !# をキャップの読み取りとメール欄から除く") {
    val commands = MailCommands.parse("sage !#abcdefghij234567abcd !#other #cap")
    assertEquals(commands.token, Some("abcdefghij234567abcd"))
    assertEquals(commands.capPassword, Some("cap"))
    assertEquals(commands.plain, "sage   ")
    assertEquals(MailCommands.parse("!#tok#en").token, Some("tok#en"))
    assertEquals(MailCommands.parse("!#tok#en").capPassword, None)
    assertEquals(MailCommands.parse("!#").token, Some(""))
  }
  test("!auth は位置によらず検出して除く") {
    val commands = MailCommands.parse("sage!auth")
    assert(commands.auth)
    assertEquals(commands.plain, "sage")
    val inCap = MailCommands.parse("#pass!auth")
    assert(inCap.auth)
    assertEquals(inCap.capPassword, Some("pass!auth"))
    assertEquals(inCap.plain, "")
  }
  test("コマンドが無ければそのまま") {
    assertEquals(MailCommands.parse("sage"), MailCommands("sage", None, None, auth = false))
    assertEquals(MailCommands.parse(""), MailCommands.empty)
  }
}
