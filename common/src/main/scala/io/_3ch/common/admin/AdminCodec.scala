package io._3ch.common.admin

import io._3ch.common.admin.Json.{Arr, Bool, Num, Obj, Str}

/** 管理 API の DTO と JSON の相互変換。読めなければ誤りのある項目名を返す */
object AdminCodec {
  type Decoded[A] = Either[String, A]

  private def opt(value: Option[String]): Json = value.fold(Json.Null)(Str(_))
  private def strings(values: List[String]): Json = Arr(values.map(Str(_)))

  private def field(json: Json, name: String): Decoded[Json] = json.get(name).toRight(name)
  private def string(json: Json, name: String): Decoded[String] = field(json, name).flatMap {
    case Str(s) => Right(s)
    case _ => Left(name)
  }
  private def boolean(json: Json, name: String): Decoded[Boolean] = field(json, name).flatMap {
    case Bool(b) => Right(b)
    case _ => Left(name)
  }
  /** null も受け付ける。キーが無ければ誤り */
  private def nullable(json: Json, name: String): Decoded[Option[String]] = field(json, name).flatMap {
    case Json.Null => Right(None)
    case Str(s) => Right(Some(s))
    case _ => Left(name)
  }
  private def int(json: Json, name: String): Decoded[Int] = field(json, name).flatMap {
    case Num(n) if n == math.rint(n) && n >= Int.MinValue && n <= Int.MaxValue => Right(n.toInt)
    case _ => Left(name)
  }
  private def list[A](json: Json, name: String)(item: Json => Option[A]): Decoded[List[A]] = field(json, name).flatMap {
    case Arr(items) => items.foldRight[Decoded[List[A]]](Right(Nil)) { (value, acc) =>
      acc.flatMap(rest => item(value).map(_ :: rest).toRight(name))
    }
    case _ => Left(name)
  }
  private def stringList(json: Json, name: String): Decoded[List[String]] = list(json, name) {
    case Str(s) => Some(s)
    case _ => None
  }
  private def objectOf(json: Json, name: String): Decoded[List[(String, Json)]] = field(json, name).flatMap {
    case Obj(entries) => Right(entries)
    case _ => Left(name)
  }

  def error(value: AdminError): Json = {
    val base = List("code" -> Str(value.code.value), "message" -> Str(value.message))
    Obj(if (value.fields.isEmpty) base else base :+ ("fields" -> Obj(value.fields.toList.sortBy(_._1).map((k, v) => k -> Str(v)))))
  }

  def error(json: Json): Decoded[AdminError] = for {
    code <- string(json, "code").flatMap(c => AdminErrorCode.values.find(_.value == c).toRight("code"))
    message <- string(json, "message")
    fields <- json.get("fields").fold[Decoded[List[(String, Json)]]](Right(Nil))(_ => objectOf(json, "fields"))
    values <- fields.foldRight[Decoded[Map[String, String]]](Right(Map.empty)) {
      case ((k, Str(v)), acc) => acc.map(_.updated(k, v))
      case (_, _) => Left("fields")
    }
  } yield AdminError(code, message, values)

  def boardList(value: BoardList): Json = Obj(List("boards" -> strings(value.boards)))

  def boardList(json: Json): Decoded[BoardList] = stringList(json, "boards").map(BoardList(_))

  private def settingValues(values: BoardSettingsValues): List[(String, Json)] = List(
    "fields" -> Obj(BoardSettingsForm.fields.flatMap(f => values.fields.get(f.key).map(v =>
      f.key -> Obj(List("value" -> Str(v.value), "explicit" -> Bool(v.explicit)))))),
    "extra" -> strings(values.extra)
  )

  /** fields に無い項目は未設定（既定値）とし、知らないキーは誤り */
  private def settingValues(json: Json): Decoded[BoardSettingsValues] = for {
    entries <- objectOf(json, "fields")
    fields <- entries.foldLeft[Decoded[Map[String, SettingValue]]](Right(Map.empty)) { case (acc, (key, item)) =>
      val name = s"fields.$key"
      for {
        values <- acc
        _ <- Either.cond(BoardSettingsForm.keys.contains(key), (), name)
        value <- string(item, "value").left.map(_ => name)
        explicit <- boolean(item, "explicit").left.map(_ => name)
      } yield values.updated(key, SettingValue(value, explicit))
    }
    extra <- stringList(json, "extra")
  } yield {
    val unset = BoardSettingsForm.fields.filterNot(f => fields.contains(f.key)).map(f => f.key -> SettingValue(f.default, explicit = false))
    BoardSettingsValues(fields ++ unset, extra)
  }

  def boardSettings(value: BoardSettingsView): Json = Obj(List("bbs" -> Str(value.bbs)) ++ settingValues(value.values) ++ List(
    "head" -> Str(value.head),
    "settingsEtag" -> opt(value.settingsEtag),
    "headEtag" -> opt(value.headEtag),
    "rules" -> Obj(List("access" -> strings(value.rules.access), "ngwords" -> strings(value.rules.ngwords)))
  ))

  def boardSettings(json: Json): Decoded[BoardSettingsView] = for {
    bbs <- string(json, "bbs")
    values <- settingValues(json)
    head <- string(json, "head")
    settingsEtag <- nullable(json, "settingsEtag")
    headEtag <- nullable(json, "headEtag")
    rules <- field(json, "rules")
    access <- stringList(rules, "access")
    ngwords <- stringList(rules, "ngwords")
  } yield BoardSettingsView(bbs, values, head, settingsEtag, headEtag, BoardRules(access, ngwords))

  def boardSettingsUpdate(value: BoardSettingsUpdate): Json = Obj(settingValues(value.values) ++ List(
    "head" -> Str(value.head),
    "settingsEtag" -> opt(value.settingsEtag),
    "headEtag" -> opt(value.headEtag)
  ))

  /** 板設定の保存。ETag のキーは必須で、読込時にファイルが無ければ null */
  def boardSettingsUpdate(json: Json): Decoded[BoardSettingsUpdate] = for {
    values <- settingValues(json)
    head <- string(json, "head")
    settingsEtag <- nullable(json, "settingsEtag")
    headEtag <- nullable(json, "headEtag")
  } yield BoardSettingsUpdate(values, head, settingsEtag, headEtag)

  def boardSettingsSaved(value: BoardSettingsSaved): Json =
    Obj(List("settingsEtag" -> opt(value.settingsEtag), "headEtag" -> opt(value.headEtag), "partial" -> Bool(value.partial)))

  def boardSettingsSaved(json: Json): Decoded[BoardSettingsSaved] = for {
    settingsEtag <- nullable(json, "settingsEtag")
    headEtag <- nullable(json, "headEtag")
    partial <- boolean(json, "partial")
  } yield BoardSettingsSaved(settingsEtag, headEtag, partial)

  def threadList(value: ThreadList): Json = Obj(List(
    "bbs" -> Str(value.bbs),
    "subject" -> Arr(value.subject.map(s => Obj(List("key" -> Str(s.key), "line" -> Str(s.line))))),
    "removed" -> strings(value.removed)
  ))

  def threadList(json: Json): Decoded[ThreadList] = for {
    bbs <- string(json, "bbs")
    subject <- list(json, "subject")(item => (for {
      key <- string(item, "key")
      line <- string(item, "line")
    } yield ThreadSummary(key, line)).toOption)
    removed <- stringList(json, "removed")
  } yield ThreadList(bbs, subject, removed)

  def threadDetail(value: ThreadDetail): Json = Obj(List(
    "bbs" -> Str(value.bbs),
    "key" -> Str(value.key),
    "state" -> Str(value.state.value),
    "movedTo" -> opt(value.movedTo),
    "movingTo" -> opt(value.movingTo),
    "res" -> Arr(value.res.map { r =>
      Obj(List("number" -> Num(r.number), "name" -> Str(r.name), "mail" -> Str(r.mail), "date" -> Str(r.date), "message" -> Str(r.message),
        "edit" -> Obj(List("name" -> Str(r.edit.name), "mail" -> Str(r.edit.mail), "message" -> Str(r.edit.message)))))
    }),
    "dat" -> Str(value.dat),
    "log" -> Str(value.log),
    "legacyLog" -> opt(value.legacyLog),
    "settings" -> Str(value.settings)
  ))

  def threadDetail(json: Json): Decoded[ThreadDetail] = for {
    bbs <- string(json, "bbs")
    key <- string(json, "key")
    state <- string(json, "state").flatMap(s => ThreadState.values.find(_.value == s).toRight("state"))
    movedTo <- nullable(json, "movedTo")
    movingTo <- nullable(json, "movingTo")
    res <- list(json, "res")(item => (for {
      number <- int(item, "number")
      name <- string(item, "name")
      mail <- string(item, "mail")
      date <- string(item, "date")
      message <- string(item, "message")
      edit <- field(item, "edit")
      editName <- string(edit, "name")
      editMail <- string(edit, "mail")
      editMessage <- string(edit, "message")
    } yield ResView(number, name, mail, date, message, ResEditFields(editName, editMail, editMessage))).toOption)
    dat <- string(json, "dat")
    log <- string(json, "log")
    legacyLog <- nullable(json, "legacyLog")
    settings <- string(json, "settings")
  } yield ThreadDetail(bbs, key, state, movedTo, movingTo, res, dat, log, legacyLog, settings)

  def threadAction(value: ThreadAction): Json = Obj(List("action" -> Str(value.name)) ++ (value match {
    case ThreadAction.Move(board, key) => List("targetBoard" -> Str(board), "targetKey" -> Str(key))
    case ThreadAction.EditRes(number, name, mail, message) => List("number" -> Num(number), "name" -> Str(name), "mail" -> Str(mail), "message" -> Str(message))
    case ThreadAction.DeleteRes(number) => List("number" -> Num(number))
    case ThreadAction.Stop | ThreadAction.Resume | ThreadAction.Delete => Nil
  }))

  def threadAction(json: Json): Decoded[ThreadAction] = string(json, "action").flatMap {
    case "stop" => Right(ThreadAction.Stop)
    case "resume" => Right(ThreadAction.Resume)
    case "delete" => Right(ThreadAction.Delete)
    case "move" => for {
      board <- string(json, "targetBoard")
      key <- string(json, "targetKey")
    } yield ThreadAction.Move(board, key)
    case "editRes" => for {
      number <- int(json, "number")
      name <- string(json, "name")
      mail <- string(json, "mail")
      message <- string(json, "message")
    } yield ThreadAction.EditRes(number, name, mail, message)
    case "deleteRes" => int(json, "number").map(ThreadAction.DeleteRes(_))
    case _ => Left("action")
  }

  def textFile(value: TextFile): Json = Obj(List("text" -> Str(value.text), "etag" -> opt(value.etag)))

  /** 規制ルール・板一覧の内容と保存。etag は読込時の値で、ファイルが無ければ null */
  def textFile(json: Json): Decoded[TextFile] = for {
    text <- string(json, "text")
    etag <- nullable(json, "etag")
  } yield TextFile(text, etag)

  def saved(value: Saved): Json = Obj(List("etag" -> opt(value.etag)))

  def saved(json: Json): Decoded[Saved] = nullable(json, "etag").map(Saved(_))
}
