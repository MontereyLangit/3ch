package io._3ch.common.islands

import munit.FunSuite

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

class IslandPropsSuite extends FunSuite {
  private def roundTrip(value: js.Object): js.Any = js.JSON.parse(js.JSON.stringify(value))

  private def postingForm(key: js.UndefOr[String], user: js.UndefOr[SessionProps]): PostingFormProps = new PostingFormProps {
    val bbs = "sample"
    val time = "1800000100"
    val imgurClientId = ""
    val authEnabled = true
    val requiredLevel = 3
    val returnPath = "/sample/"
    val commands = List(new ThreadCommandProps {
      val text = "!cmd"
      val label = "cmd"
      val field = "MESSAGE"
      val description = "コマンド"
    }).toJSArray
    val session = user
    val threadKey = key
  }

  private val member: SessionProps = new SessionProps {
    val uid = "member"
    val lv = 3
  }

  test("PostingFormProps は JSON の往復後も検証を通り、省いた値は undefined のまま") {
    val parsed = PostingFormProps.validate(roundTrip(postingForm(js.undefined, js.undefined))).toOption.get
    assertEquals(parsed.commands.map(_.description).toList, List("コマンド"))
    assertEquals(parsed.requiredLevel.toOption, Some(3))
    assert(parsed.session.isEmpty)
    assert(parsed.threadKey.isEmpty)
    val reply = PostingFormProps.validate(roundTrip(postingForm("1800000001", member))).toOption.get
    assertEquals(reply.threadKey.toOption, Some("1800000001"))
    assertEquals(reply.session.toOption.map(s => (s.uid, s.lv)), Some(("member", 3.0)))
  }

  test("サーバーが組み立てた props のキーの並びと JSON は従来の js.Dynamic.literal と同じ") {
    assertEquals(js.JSON.stringify(postingForm(js.undefined, member)),
      """{"bbs":"sample","time":"1800000100","imgurClientId":"","authEnabled":true,"requiredLevel":3,"returnPath":"/sample/",""" +
        """"commands":[{"text":"!cmd","label":"cmd","field":"MESSAGE","description":"コマンド"}],"session":{"uid":"member","lv":3}}""")
  }

  test("BoardDetailsProps は posting form の props を入れ子で持ち、往復後も検証を通る") {
    val details = new BoardDetailsProps {
      val bbs = "sample"
      val headText = "<b>ルール</b>"
      val settingsText = "BBS_TITLE=テスト"
      val postingForm = IslandPropsSuite.this.postingForm(js.undefined, js.undefined)
    }
    val parsed = BoardDetailsProps.validate(roundTrip(details)).toOption.get
    assertEquals(parsed.postingForm.bbs, "sample")
    assert(BoardDetailsProps.validate(roundTrip(postingForm(js.undefined, js.undefined))).isLeft, "平らな props は受け付けない")
  }

  test("ThreadListProps は必須キーが欠けると拒否する") {
    val list = new ThreadListProps {
      val bbs = "sample"
      val `type` = "subback"
      val subjectText = "1800000001.dat<>スレ (3)\n"
      val query = ""
      val sort = "new"
      val now = 1800000100000d
    }
    assert(ThreadListProps.validate(roundTrip(list)).isRight)
    val missing = js.JSON.parse("""{"bbs":"sample","type":"subback","subjectText":"","query":"","sort":"new"}""")
    assertEquals(ThreadListProps.validate(missing), Left("props.now: missing"))
  }
}
