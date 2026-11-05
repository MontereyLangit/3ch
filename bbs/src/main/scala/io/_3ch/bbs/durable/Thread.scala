package io._3ch.bbs.durable

import io._3ch.bbs.external.cloudflare.*
import io._3ch.bbs.durable.codec.ThreadOwnerCodec
import io._3ch.bbs.durable.support.DurableLock

import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * スレッドデータを管理するDurable Object (Thread)
 * 1つのスレッドに対して1つのインスタンスが対応します。
 */
@JSExportTopLevel("Thread", "server")
class Thread(val state: DurableObjectState, env: WorkerEnv) extends DurableObject(state, env) {
  private def read(key: String): Future[js.Any] = state.storage.get[js.Any](key).toFuture.map(_.asInstanceOf[js.Any])
  private def truth(value: js.Any): Boolean = js.Dynamic.global.Boolean(value).asInstanceOf[Boolean]
  private def str(value: js.Any): String = js.Dynamic.global.String(value).asInstanceOf[String]
  private def text(key: String): Future[String] = read(key).map(v => if truth(v) then str(v) else "")
  private def put(values: (String, js.Any)*): Future[Unit] = state.storage.put(js.Dictionary(values*)).toFuture
  private def board(name: String): BoardDoStub = env.BOARD_DO.get(env.BOARD_DO.idFromName(name)).asInstanceOf[BoardDoStub]
  private def thread(name: String): ThreadDoStub = env.THREAD_DO.get(env.THREAD_DO.idFromName(name)).asInstanceOf[ThreadDoStub]
  private def fail(message: String): Nothing = throw js.JavaScriptException(new js.Error(message))
  private def trimEnd(s: String): String = s.asInstanceOf[js.Dynamic].trimEnd().asInstanceOf[String]
  private def trim(s: String): String = s.asInstanceOf[js.Dynamic].trim().asInstanceOf[String]
  private def escape(value: js.Any): String = {
    val s = if value == null || js.isUndefined(value) then "" else str(value)
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replaceAll("\r?\n", " <br> ")
  }

  private def locked[A](f: => Future[A]): Future[A] = DurableLock.serialized(state)(f)

  /**
   * dat形式の全スレッドデータを文字列として返します。
   */
  def getDat(bbs: String = "", key: String = "0"): js.Promise[String] =
    (for {
      hidden <- read("hidden")
      result <- if truth(hidden) then Future.successful("") else
        text("dat").flatMap { existing =>
          val number = js.Dynamic.global.Number(key).asInstanceOf[Double]
          val imported = if existing.isEmpty && bbs.nonEmpty && number > 1761990000d && number < 1762087165d then {
            val old = thread(key)
            old.getDat().toFuture.flatMap { dat =>
              if dat.isEmpty then Future.successful("") else
                for {
                  log <- old.getLog().toFuture
                  settings <- old.getSettings().toFuture
                  _ <- put("dat" -> dat, "log" -> log, "settings" -> settings)
                } yield dat
            }
          } else Future.successful(existing)
          imported.flatMap { dat =>
            val parsed = js.Dynamic.global.parseInt(key).asInstanceOf[Double]
            if dat.isEmpty && bbs.nonEmpty && parsed > 0 && parsed < 1762009199d then importLegacy(bbs, key)
            else Future.successful(dat)
          }
        }
    } yield result).toJSPromise

  // datが存在しない場合で keyが1以上1762009199未満の場合、R2から旧形式スレッドログを取得しdatに変換
  private def importLegacy(bbs: String, key: String): Future[String] =
    (for {
      obj <- env.ch3www.get(s"$bbs/thread/${key.take(4)}/$key.cgi").toFuture
      dat <- if obj == null then put("dat" -> "").map(_ => "") else
        for {
          log <- obj.text().toFuture
          _ <- put("legacyLog" -> log)
          rows = log.split("\n", -1)
          /* スレの配列に最低3個要素がないとおかしい */
          _ = if rows.length < 3 then fail("スレッドのログファイルが壊れています。運用情報板で修理してもらってください。")
          _ <- put("settings" -> rows(1))
          dat = convertLegacy(rows)
          _ <- put("dat" -> dat)
        } yield dat
    } yield dat).recover { case _ => "" }

  private def convertLegacy(rows: Array[String]): String = {
    /* スレ設定を取得。ログの二行目に入っている。NAME=VALUE<>NAME2=VALUE2 */
    val settings = mutable.Map.empty[String, String]
    trim(rows(1)).split("<>", -1).foreach { s =>
      val parts = s.split("=", -1)
      if parts(0).nonEmpty then settings(parts(0)) = trim(parts.lift(1).getOrElse(""))
    }
    val formatter = js.Dynamic.newInstance(js.Dynamic.global.Intl.DateTimeFormat)("ja-JP", js.Dynamic.literal(
      year = "numeric", month = "2-digit", day = "2-digit", weekday = "short", hour = "2-digit", minute = "2-digit", second = "2-digit", timeZone = "Asia/Tokyo"))
    val out = new StringBuilder
    var count = 0
    rows.drop(2).foreach { row =>
      /* 0:num<>1:name<>2:sikibetsuId<>3:time<>4:id<>5:MESSAGE<>6:HOST999 ただし6:HOST999はここでは使わない */
      val fields = trim(row).split("<>", -1)
      /* num無し、numが番号でない、num < 1、timeなし、本文部分まで<>がない、削除済み（num=delレス番） -> 表示しない */
      if fields.length >= 6 && fields(0).matches("^[0-9]+$") && fields(0).toDouble >= 1 && fields(3).nonEmpty then {
        val next = fields(0).toDouble.toInt
        /* レス削除などによるレス番飛びを検出する。更新前のresnumが前のレスの番号でなければ、空いた分だけ行を挿入する。 */
        for i <- count + 1 until next do out.append(s"$i<>あぼーん<>あぼーん<>あぼーん<>あぼーん\n")
        count = next
        var name = if fields(1).nonEmpty then fields(1) else settings.getOrElse("BBS_NONAME_NAME", "774@3ch")
        /* 識別ID欄を名前欄に追加 */
        if fields(2).nonEmpty then name += s" </b>(${fields(2)})<b>"
        /* 日付欄の処理 */
        val timestamp = js.Dynamic.global.Number(fields(3).take(10)).asInstanceOf[Double] * 1000
        var date = formatter.format(new js.Date(timestamp)).asInstanceOf[String]
        if fields(3).length > 10 then date += "." + fields(3).drop(10)
        /* ID欄を日付欄に追加 */
        if fields(4).nonEmpty then date += " " + fields(4)
        /* <br>の前後にスペースを挿入。必要あるのかはわからないけど、一応。 */
        val message = fields(5).replace("<br>", " <br> ")
        out.append(s"$name<><>$date<> $message <>")
        if count == 1 then out.append(rows(0)) /* スレッドタイトルはログの一行目に入っている。 */
        out.append('\n')
      }
    }
    out.toString
  }

  def getPublicData(bbs: String, key: String): js.Promise[js.Dynamic] =
    (for {
      dat <- getDat(bbs, key).toFuture
      modified <- read("modifiedAt")
      edited <- read("edited")
    } yield js.Dynamic.literal(dat = dat, modifiedAt = modified, edited = truth(edited))).toJSPromise

  /**
   * log形式の全投稿者ログを文字列として返します。
   */
  def getLog(): js.Promise[String] = text("log").toJSPromise

  /**
   * スレ設定文字列を返します。
   */
  def getSettings(): js.Promise[String] = text("settings").toJSPromise

  /**
   * スレ立て時に保存したスレ主の照合値を返します。無ければ undefined（docs/トリップ・ID廃止とスレ主表示.md §2.4）。
   */
  def getOwner(): js.Promise[js.Any] = read("owner").toJSPromise

  /**
   * スレ設定を更新します。
   * @param settings - 新しいスレ設定文字列
   */
  def setSettings(settings: String): js.Promise[Unit] = put("settings" -> settings).toJSPromise

  /**
   * 新しいレスを追加します。
   * @param datLine - dat形式の新しい行データ
   * @param logLine - log形式の新しい行データ
   * @param posting
   * @return 追加後のレス数
   */
  def addRes(datLine: String, logLine: String, posting: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[Int] = locked {
    // v5 uses `posting?.x` / `if (posting) ...` — plain JS truthiness, so a literal `null` argument
    // (indistinguishable from omission to any untyped caller across the DO RPC boundary) behaves
    // exactly like an omitted one. `js.UndefOr#isDefined` only means "not undefined": a `null` here
    // would pass it, then `p.bbs` etc. would throw on a null receiver instead of degrading gracefully.
    // `hasPosting` mirrors the JS truthiness check for both nullish cases.
    val rawPosting = posting.asInstanceOf[js.Any]
    val hasPosting = rawPosting != null && !js.isUndefined(rawPosting)
    val p = if hasPosting then posting.get else js.Dynamic.literal()
    for {
      hidden <- read("hidden")
      stopped <- if truth(hidden) then Future.successful[js.Any](true) else read("stopped")
      moving <- if truth(stopped) then Future.successful[js.Any](true) else read("movingTo")
      _ = if truth(moving) then fail("このスレッドは停止・移動または削除されています。")
      dat <- getDat(if js.isUndefined(p.bbs) then "" else str(p.bbs), if js.isUndefined(p.key) then "0" else str(p.key)).toFuture
      settings <- text("settings")
      _ = if new js.RegExp("(?:^|<>)BBS_HEISA=checked(?:<>|$)").test(settings) then fail("このスレッドは停止されています。")
      _ = if truth(p.create) && dat.nonEmpty then fail("同じkeyのスレッドが存在します。")
      _ = if hasPosting && !truth(p.create) && dat.isEmpty then fail("スレッドがありません。")
      count = trimEnd(dat).split("\n", -1).count(_.nonEmpty) + 1
      _ = if count >= 1000 then fail("このスレッドは1000を超えたのでもう書けません。")
      log <- text("log")
      update = js.Dictionary[js.Any]("dat" -> (dat + datLine + "\n"), "log" -> (log + logLine.replaceFirst("^\\d+<>", s"$count<>") + "\n"), "modifiedAt" -> js.Date.now())
      _ = if truth(p.create) then update("settings") = if truth(p.initialSettings) then p.initialSettings else ""
      _ = if truth(p.create) && truth(p.owner) then update("owner") = ThreadOwnerCodec.encode(ThreadOwnerCodec.decode(p.owner))
      creationLimit = truth(p.create) && truth(p.creationLimit)
      _ <- if creationLimit then board(str(p.bbs)).reserveThreadCreation(str(p.key), p.creationLimit).toFuture.map { error =>
        if error.nonEmpty then fail(error)
      } else Future.unit
      _ <- state.storage.put(update).toFuture.recoverWith { case error =>
        if creationLimit then board(str(p.bbs)).cancelThreadCreation(str(p.key)).toFuture.transformWith(_ => Future.failed(error))
        else Future.failed(error)
      }
      _ <- if creationLimit then board(str(p.bbs)).completeThreadCreation(str(p.key)).toFuture.recover { case _ => () } else Future.unit
      _ <- if hasPosting then board(str(p.bbs)).updateThread(js.Dynamic.literal(key = p.key, title = p.title, resNum = count), p.sage.asInstanceOf[js.UndefOr[Boolean]], p.maxThreads.asInstanceOf[js.UndefOr[Double]]).toFuture else Future.unit
    } yield count
  }.toJSPromise

  def inspect(bbs: js.UndefOr[String] = js.undefined, key: js.UndefOr[String] = js.undefined): js.Promise[js.Dynamic] =
    (for {
      _ <- getDat(bbs.getOrElse(""), key.getOrElse("0")).toFuture
      data <- state.storage.get(js.Array("dat", "log", "legacyLog", "settings", "hidden", "stopped", "movedTo", "movingTo", "importedFrom")).toFuture
      _ <- if !data.contains("legacyLog") && bbs.exists(_.nonEmpty) && key.exists(_.nonEmpty) then
        env.ch3www.get(s"${bbs.get}/thread/${key.get.take(4)}/${key.get}.cgi").toFuture.flatMap { obj =>
          if obj == null then Future.unit else obj.text().toFuture.flatMap { log => data.update("legacyLog", log); put("legacyLog" -> log) }
        } else Future.unit
    } yield js.Dynamic.global.Object.fromEntries(data)).toJSPromise

  def moderate(action: String, input: js.Dynamic): js.Promise[Unit] = {
    val source = s"${str(input.bbs)}/${str(input.key)}"
    val target = s"${str(input.targetBoard)}/${str(input.targetKey)}"
    val preflight = if action == "move" then Future.unit.flatMap { _ =>
      if source == target then fail("移動元と移動先が同じです。")
      thread(target).inspect(str(input.targetBoard), str(input.targetKey)).toFuture.map { dest =>
        if (dest.dat.exists(_.nonEmpty) || dest.hidden.getOrElse(false)) && !dest.importedFrom.toOption.contains(source) then fail("移動先のkeyは使用済みです。")
      }
    } else Future.unit
    preflight.flatMap { _ => locked {
      for {
        dat <- text("dat")
        _ = if dat.isEmpty then fail("スレッドがありません。")
        boardStub = board(str(input.bbs))
        pending <- read("pendingMove")
        _ = if truth(pending) then fail("移動先の準備中です。移動元から同じ移動を再実行してください。")
        moving <- read("movingTo")
        _ = if action != "move" && truth(moving) then fail("移動処理中です。同じ移動先で再実行してください。")
        _ <- action match {
          case "delete" => put("hidden" -> true).flatMap(_ => boardStub.removeThread(str(input.key)).toFuture)
          case "stop" | "resume" =>
            for {
              _ <- put("stopped" -> (action == "stop"))
              settings <- text("settings")
              entries = settings.split("<>", -1).filter(s => s.nonEmpty && !s.startsWith("BBS_HEISA="))
              _ <- put("settings" -> (entries :+ s"BBS_HEISA=${if action == "stop" then "checked" else ""}").mkString("<>"))
            } yield ()
          case "editRes" | "deleteRes" =>
            val lines = trimEnd(dat).split("\n", -1)
            val number = js.Dynamic.global.Number(input.number).asInstanceOf[Double]
            if !js.Dynamic.global.Number.isInteger(number).asInstanceOf[Boolean] || number < 1 || number > lines.length then fail("レス番号が不正です。")
            val fields = lines(number.toInt - 1).split("<>", -1).toBuffer
            if action == "deleteRes" then {
              fields.remove(0, math.min(4, fields.length))
              fields.prependAll(Seq.fill(4)("あぼーん"))
            } else {
              while fields.length < 4 do fields += ""
              fields(0) = escape(input.name); fields(1) = escape(input.mail); fields(3) = escape(input.message)
            }
            lines(number.toInt - 1) = fields.mkString("<>")
            put("dat" -> (lines.mkString("\n") + "\n"), "modifiedAt" -> js.Date.now(), "edited" -> true)
          case "move" => move(input, dat, source, target, moving, boardStub)
          case _ => fail("操作が不正です。")
        }
      } yield ()
    }}.toJSPromise
  }

  private def move(input: js.Dynamic, dat: String, source: String, target: String, moving: js.Any, boardStub: BoardDoStub): Future[Unit] = {
    if truth(moving) && str(moving) != target then fail("処理中の移動先と異なります。")
    for {
      hidden <- read("hidden")
      _ = if truth(hidden) && !truth(moving) then fail("削除・移動済みスレッドは移動できません。")
      _ = if target == source then fail("移動元と移動先が同じです。")
      _ <- put("movingTo" -> target)
      _ <- (for {
        log <- text("log")
        legacy <- text("legacyLog")
        settings <- text("settings")
        stopped <- read("stopped")
        edited <- read("edited")
        owner <- read("owner")
        data = js.Dynamic.literal(dat = dat, log = log, legacyLog = legacy, settings = settings, stopped = truth(stopped), edited = truth(edited), modifiedAt = js.Date.now())
        _ = if truth(owner) then data.owner = owner
        _ <- thread(target).receiveMove(data, source).toFuture
      } yield ()).recoverWith { case e =>
        val message = e match {
          case x: js.JavaScriptException => str(x.exception.asInstanceOf[js.Dynamic].message)
          case _ => e.getMessage
        }
        if message.contains("移動先のkeyは使用済み") then state.storage.delete("movingTo").toFuture.flatMap(_ => Future.failed(e)) else Future.failed(e)
      }
      _ <- put("hidden" -> true, "movedTo" -> target)
      _ <- boardStub.removeThread(str(input.key)).toFuture
      _ <- thread(target).publishMove(source, str(input.targetBoard), str(input.targetKey)).toFuture
      _ <- state.storage.delete("movingTo").toFuture
    } yield ()
  }

  def receiveMove(data: js.Dynamic, source: String): js.Promise[Unit] = locked {
    read("importedFrom").flatMap { imported =>
      if imported == source.asInstanceOf[js.Any] then Future.unit else
        for {
          dat <- read("dat")
          hidden <- read("hidden")
          _ = if truth(dat) || truth(hidden) then fail("移動先のkeyは使用済みです。")
          values = js.Dynamic.global.Object.assign(js.Dynamic.literal(), data, js.Dynamic.literal(importedFrom = source, hidden = true, pendingMove = true)).asInstanceOf[js.Dictionary[js.Any]]
          _ <- state.storage.put(values).toFuture
        } yield ()
    }
  }.toJSPromise

  def publishMove(source: String, bbs: String, key: String): js.Promise[Unit] = locked {
    for {
      imported <- read("importedFrom")
      _ = if imported != source.asInstanceOf[js.Any] then fail("移動元が一致しません。")
      pending <- read("pendingMove")
      _ <- if !truth(pending) then Future.unit else
        for {
          dat <- text("dat")
          title = dat.split("\n", -1)(0).split("<>", -1).lift(4).orUndefined
          _ <- board(bbs).updateThread(js.Dynamic.literal(key = key, title = title, resNum = trimEnd(dat).split("\n", -1).length), false).toFuture
          _ <- put("hidden" -> false, "pendingMove" -> false)
        } yield ()
    } yield ()
  }.toJSPromise

  /**
   * 指定されたレス番号をあぼーん化します。
   * @param resNum - あぼーん化するレス番号 (1-based)
   */
  def deleteRes(resNum: Int): js.Promise[Unit] = getDat().toFuture.flatMap { dat =>
    val lines = dat.split("\n", -1)
    if resNum > 0 && resNum < lines.length then {
      lines(resNum - 1) = "あぼーん<>あぼーん<>あぼーん<>あぼーん<>あぼーん"
      put("dat" -> lines.mkString("\n"))
    } else Future.unit
  }.toJSPromise

  /**
   * datを上書きします。
   * @param dat - 上書き内容
   */
  def setDat(dat: String): js.Promise[Unit] = read("hidden").flatMap(v => if truth(v) then Future.unit else put("dat" -> dat)).toJSPromise
}
