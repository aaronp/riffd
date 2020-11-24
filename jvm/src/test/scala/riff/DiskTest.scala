package riff

import riff.jvm.NioDisk
import zio.ZIO

trait DiskTest extends BaseTest {

  def withDisk(callback: Disk.Service => Unit): Unit

  "Disk.intBytes" should {
    List(Int.MaxValue, Int.MinValue, -1, 0, 1, 123).foreach { expected =>
      s"read and write $expected" in {
        val bytes = Disk.intBytes(expected)
        Disk.readInt(bytes) shouldBe expected
      }
    }
  }

  "Disk.boolBytes" should {
    List(true, false).foreach { expected =>
      s"read and write $expected" in {
        val bytes = Disk.boolBytes(expected)
        Disk.readBool(bytes) shouldBe expected
      }
    }
  }
  "Disk.stringBytes" should {
    List("", "hello").foreach { expected =>
      s"read and write $expected" in {
        val bytes = Disk.stringBytes(expected)
        Disk.readString(bytes) shouldBe expected
      }
    }
  }

  "Disk.latestCommitted" should {
    "get only return the latest committed" in withDisk { service =>
      service.latestCommitted().value() shouldBe LogCoords.empty
      service.write(Offset(3), LogEntry(2, "second".getBytes())).value()
      service.readCommitted(Offset(3), 1).value().length shouldBe 0
      service.latestCommitted().value() shouldBe LogCoords.empty
      val readBack = service.readUncommitted(Offset(3), 1).value().ensuring(_.length == 1).head
      readBack.dataAsString shouldBe "second"
      readBack.term shouldBe 2
    }
  }
  "Disk.commit" should {
    "get only return the latest committed" in withDisk { service =>
      service.latestCommitted().value() shouldBe LogCoords.empty
      (1 to 10).foreach { i =>
        service.write(Offset(i), LogEntry(100, s"entry $i".getBytes())).value()
      }
      val one = service.readUncommitted(Offset(3), 1).value()
      one.ensuring(_.length == 1, s"Read ${one.length}: ${one.mkString(",")}").head.dataAsString shouldBe "entry 3"
      service.readCommitted(Offset(3), 1).value().length shouldBe 0
      service.latestCommitted().value() shouldBe LogCoords.empty

      service.commit(Offset(3)).value()
      service.latestCommitted().value() shouldBe LogCoords(100, Offset(3))

      service.readCommitted(Offset(3), 1).value().ensuring(_.length == 1).head.dataAsString shouldBe "entry 3"
    }
    "still work if trying to commit beyond the latest log entry" in withDisk { service =>
      service.latestCommitted().value() shouldBe LogCoords.empty
      (1 to 10).foreach { i =>
        service.write(Offset(i), LogEntry(2, s"entry $i".getBytes())).value()
      }

      service.commit(Offset(20)).value()

      service.latestCommitted().value() shouldBe LogCoords(2, Offset(10))
      service.readCommitted(Offset(10), 3).value().ensuring(_.length == 1).head.dataAsString shouldBe "entry 10"
    }
  }
  "Disk.write" should {
    "overwrite uncommitted entries if asked to do so" in withDisk { service =>
      service.write(Offset(3), LogEntry(2, "original entry".getBytes())).value()
      locally {
        val List(readBack) = service.readUncommitted(Offset(3), 1).value().toList
        readBack.term shouldBe 2
        readBack.dataAsString shouldBe ("original entry")
      }
      val Right(_) = service.write(Offset(3), LogEntry(2, "replaced".getBytes())).either.value()
      service.latestCommitted().value() shouldBe LogCoords.empty
      locally {
        val List(readBack) = service.readUncommitted(Offset(3), 1).value().toList
        readBack.term shouldBe 2
        readBack.dataAsString shouldBe ("replaced")
      }
    }

    "fail if there's already an entry w/ a different term" in withDisk { service =>
      service.write(Offset(3), LogEntry(2, "second".getBytes())).value()
      service.latestUncommitted().value() shouldBe LogCoords(2, Offset(3))

      service.write(Offset(3), LogEntry(3, "third".getBytes())).value()
      service.latestCommitted().value() shouldBe LogCoords.empty
      service.latestUncommitted().value() shouldBe LogCoords(3, Offset(3))
    }
  }
  "Disk.voteFor" should {
    "not allow voting twice in the same term" in withDisk { service =>

      service.voteFor(1, "foo").value()
      service.votedFor(1).value() shouldBe Some("foo")
      service.voteFor(1, "nope").either.value() shouldBe Left(AlreadyVotedErr(1))
    }
  }
  "Disk.currentTerm" should {
    "return the current term" in withDisk { service =>

      service.currentTerm().value() shouldBe 0
      service.updateTerm(3).value()
      service.currentTerm().value() shouldBe 3
      service.updateTerm(4).value()
      service.currentTerm().value() shouldBe 4
    }
  }
  "Disk.writeFrom" should {
    "fail if the entry is already committed" in withDisk { service =>
      val readBack = for {
        _ <- service.write(Offset(1), LogEntry(10, "one".getBytes()))
        _ <- service.write(Offset(2), LogEntry(20, "two".getBytes()))
        _ <- service.write(Offset(3), LogEntry(30, "three".getBytes()))
        _ <- service.commit(Offset(2))
        coords <- service.latestCommitted()
        _ = coords shouldBe LogCoords(20, Offset(2))
        expectedFailure <- service.appendEntriesOnMatch(LogCoords(20, Offset(2)), Array(LogEntry(12, "data".getBytes))).either
        readBack <- service.readUncommitted(Offset(0), 3)
      } yield (expectedFailure, readBack.toList)
      val (Left(AttemptToDeleteCommitted(tried, committed)), List(a, b, c)) = readBack.value()
      committed shouldBe LogCoords(20, Offset(2))
      tried shouldBe Offset(3)
      a.term shouldBe 10
      b.term shouldBe 20
      c.term shouldBe 30
      c.dataAsString shouldBe "three"
    }
    "delete all subsequent entries if the terms don't match" in withDisk {
      case service =>
        val readBack = for {
          _ <- ZIO.foreach((1 to 350).toList) { i =>
            service.write(Offset(i), LogEntry(i, s"item-$i".getBytes()))
          }
          first <- service.readUncommitted(Offset(1), 1)
          _ = first.map(_.offset).toList shouldBe List(1)
          before <- service.latestUncommitted()
          _ = before shouldBe LogCoords(350, Offset(350))
          _ <- service.appendEntriesOnMatch(LogCoords(290, Offset(290)), Array(LogEntry(12, "data".getBytes)))
          after <- service.latestUncommitted()
          _ = after shouldBe LogCoords(12, Offset(291))
          firstCheck <- service.readUncommitted(Offset(1), 1)
          _ = firstCheck.map(_.offset).toList shouldBe List(1)
          readBack <- service.readUncommitted(Offset(290), 3)
        } yield readBack.toList
        val List(a, b) = readBack.value()

        a.term shouldBe 290
        b.term shouldBe 12
    }
  }

  "Disk.read" should {
    "read entries" in withDisk { service =>
      service.write(Offset(3), LogEntry(2, "foo".getBytes())).value()
      service.write(Offset(4), LogEntry(3, "bar".getBytes())).value()

      service.readCommitted(Offset(1), 2).value().size shouldBe 0
      service.readCommitted(Offset(2), 0).value().size shouldBe 0
      service.readCommitted(Offset(3), 0).value().size shouldBe 0

      locally {
        val List(found) = service.readUncommitted(Offset(3), 1).value().toList
        found.term shouldBe 2
        found.dataAsString shouldBe "foo"
      }

      locally {
        val List(foo, bar) = service.readUncommitted(Offset(3), 2).value().toList
        foo.term shouldBe 2
        foo.dataAsString shouldBe "foo"
        bar.term shouldBe 3
        bar.dataAsString shouldBe "bar"
      }

      locally {
        val List(foo, bar) = service.readUncommitted(Offset(3), 3).value().toList
        foo.term shouldBe 2
        foo.dataAsString shouldBe "foo"
        bar.term shouldBe 3
        bar.dataAsString shouldBe "bar"
      }

      locally {
        val List(foo, bar) = service.readUncommitted(Offset(0), 10).value().toList
        foo.term shouldBe 2
        foo.dataAsString shouldBe "foo"
        bar.term shouldBe 3
        bar.dataAsString shouldBe "bar"
      }
    }
  }
}
