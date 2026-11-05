package io._3ch.bbs.repository

import io._3ch.bbs.durable.DurableTestKit
import munit.FunSuite

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class ArchiveIndexRepositorySuite extends FunSuite {
  test("過去ログ目次のファイル名") {
    assertEquals(ArchiveIndexRepository.subjectFile("sample", "1700000000"), "sample/thread/1700/subject.txt")
    assertEquals(ArchiveIndexRepository.archiveListFile("sample"), "sample/thread/subject.txt")
  }

  test("読込は本文と ETag を返し、無ければ None") {
    val env = DurableTestKit.newEnv()
    env.putR2Raw("a.txt", "本文", "etag-a")
    for {
      found <- ArchiveIndexRepository.read("a.txt", env.worker)
      missing <- ArchiveIndexRepository.read("b.txt", env.worker)
    } yield {
      assertEquals(found, Some(ArchiveIndexRepository.Stored("本文", Some("etag-a"))))
      assertEquals(missing, None)
    }
  }

  test("ETag が一致すれば書き、競合すれば書かずに false") {
    val env = DurableTestKit.newEnv()
    env.putR2Raw("s.txt", "old", "etag-1")
    for {
      conflict <- ArchiveIndexRepository.writeIndex("s.txt", "lost", Some("etag-stale"), env.worker)
      _ = assertEquals((conflict, env.r2Text("s.txt")), (false, Some("old")))
      written <- ArchiveIndexRepository.writeIndex("s.txt", "new", Some("etag-1"), env.worker)
      _ = assertEquals((written, env.r2Text("s.txt")), (true, Some("new")))
    } yield ()
  }

  test("ETag が無いか空なら doesNotExist だけを付け、R2 はこれを解さないので既存でも書く（KB-25）") {
    val env = DurableTestKit.newEnv()
    env.putR2Raw("s.txt", "old", "etag-1")
    for {
      overwritten <- ArchiveIndexRepository.writeIndex("s.txt", "new", None, env.worker)
      _ = assertEquals((overwritten, env.r2Text("s.txt")), (true, Some("new")))
      empty <- ArchiveIndexRepository.writeIndex("s.txt", "again", Some(""), env.worker)
      _ = assertEquals((empty, env.r2Text("s.txt")), (true, Some("again")))
    } yield ()
  }

  test("headline.txt は ETag が一致するときだけ書き、競合は失敗にしない") {
    val env = DurableTestKit.newEnv()
    env.putR2Raw("headline.txt", "old", "etag-h")
    for {
      _ <- ArchiveIndexRepository.writeHeadline("lost", Some("etag-stale"), env.worker)
      _ = assertEquals(env.r2Text("headline.txt"), Some("old"))
      _ <- ArchiveIndexRepository.writeHeadline("new", Some("etag-h"), env.worker)
      _ = assertEquals(env.r2Text("headline.txt"), Some("new"))
      _ <- ArchiveIndexRepository.writeHeadline("unconditional", None, env.worker)
      _ = assertEquals(env.r2Text("headline.txt"), Some("unconditional"))
    } yield ()
  }
}
