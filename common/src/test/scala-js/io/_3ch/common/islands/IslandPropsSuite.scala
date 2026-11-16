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
    assertEquals(parsed.requiredLevel, 3)
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

  private val post: AccountPostItemProps = new AccountPostItemProps {
    val t = 1800000001d
    val bbs = "sample"
    val key = "1800000001"
    val title = "投稿先"
    val resNum = 12
    val excerpt = "本文"
    val network = "回線・東京都"
  }

  private def menu(tokenPosts: js.Array[AccountPostItemProps]): AccountMenuProps = new AccountMenuProps {
    val tokens = js.Array(new AccountTokenItemProps {
      val id = "token-id"
      val label = "JaneStyle"
      val issuedAt = 1799999900d
      val expiresAt = 1800086400d
      val recentPosts = tokenPosts
      val warnings = js.Array(new AccountTokenWarningProps {
        val t = 1800000002d
        val uaLabel = "Siki"
      })
      val boundAt = 1800000000d
      val lastUsedAt = js.undefined
      val revokedAt = js.undefined
      val replacedTokenAt = js.undefined
      val revokeReason = js.undefined
    })
    val activeCount = 1
    val nowSeconds = 1800000100d
  }

  test("アカウント系の props はサーバーが組み立てた形のまま JSON の往復後も検証を通る") {
    val parsed = AccountMenuProps.validate(roundTrip(menu(js.Array(post)))).toOption.get
    assertEquals(parsed.tokens.head.recentPosts.head.resNum, 12)
    assert(parsed.tokens.head.revokedAt.isEmpty)
    val cap = new AccountCapProps {
      val caps = js.Array(new AccountCapItemProps {
        val id = "cap1"
        val name = "運営"
        val strong = true
        val issuedAt = 1800000000d
        val recentPosts = js.Array(post)
        val revokedAt = js.undefined
      })
      val admin = true
      val canIssue = true
    }
    assert(AccountCapProps.validate(roundTrip(cap)).isRight)
    val auth = new AuthFormProps {
      val turnstileSiteKey = "public-key"
      val returnPath = "/sample/"
      val embed = false
      val login = false
      val agreementVersion = "v3"
      val googleEnabled = false
      val notice = ""
      val mailEnabled = false
      val upgrade = false
    }
    assert(AuthFormProps.validate(roundTrip(auth)).isRight)
    val header = new SiteHeaderProps {
      val authEnabled = true
      val returnPath = "/sample/"
      val session = member
    }
    assert(SiteHeaderProps.validate(roundTrip(header)).isRight)
    assert(AccountNavProps.validate(roundTrip(header)).isRight)
    List(
      AuthTokenProps.validate(js.JSON.parse("""{"mode":"reset","token":"t","enabled":true,"uid":"u","email":""}""")),
      AccountCredentialsProps.validate(js.JSON.parse("""{"email":"","pendingEmail":"","mailEnabled":false}""")),
      AccountLogoutProps.validate(js.JSON.parse("""{"simple":true,"kind":"client","google":false}"""))
    ).foreach(result => assert(result.isRight, result))
  }

  test("アカウント系の props は欠落・型違い・null・小数の Int をパス付きで拒否する") {
    val menuJson = js.JSON.stringify(menu(js.Array(post)))
    def menuWith(edit: js.Dynamic => Unit): js.Any = {
      val raw = js.JSON.parse(menuJson)
      edit(raw)
      raw
    }
    assertEquals(AccountMenuProps.validate(menuWith(raw => js.special.delete(raw.tokens.asInstanceOf[js.Array[js.Dynamic]](0), "warnings"))),
      Left("props.tokens[0].warnings: missing"))
    assertEquals(AccountMenuProps.validate(menuWith(_.activeCount = 1.5)), Left("props.activeCount: expected integer in Int range, got 1.5"))
    assertEquals(AccountMenuProps.validate(menuWith(raw => raw.tokens.asInstanceOf[js.Array[js.Dynamic]](0).recentPosts = null)),
      Left("props.tokens[0].recentPosts: expected array, got null"))
    assertEquals(AccountMenuProps.validate(menuWith(raw => raw.tokens.asInstanceOf[js.Array[js.Dynamic]](0).recentPosts.asInstanceOf[js.Array[js.Dynamic]](0).resNum = "12")),
      Left("props.tokens[0].recentPosts[0].resNum: expected number, got string"))
    assertEquals(AuthFormProps.validate(js.JSON.parse("""{"turnstileSiteKey":"k","returnPath":"/","embed":false,"login":false,"agreementVersion":"v",""" +
      """"googleEnabled":false,"mailEnabled":false,"upgrade":false}""")), Left("props.notice: missing"))
    assertEquals(SiteHeaderProps.validate(js.JSON.parse("""{"authEnabled":null,"returnPath":"/","session":{"uid":"member"}}""")),
      Left("props.authEnabled: expected boolean, got null; props.session.lv: missing"))
    assertEquals(AccountLogoutProps.validate(js.JSON.parse("""{"simple":true}""")), Left("props.kind: missing; props.google: missing"))
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
