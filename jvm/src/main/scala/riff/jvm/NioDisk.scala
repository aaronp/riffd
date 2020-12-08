package riff.jvm

import java.nio.file.StandardOpenOption._
import java.nio.file.{Files, Path}

import riff.Disk.Service
import riff._
import zio.blocking.Blocking
import zio.{IO, Task, UIO, ZIO}

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

/**
 * stores the data in this structure:
 * {{{
 *   <baseDir>/data/<offset % offsetBatchSize>/<offset>/<term> <-- where <term> is the filename containing the data
 *   <baseDir>/committed/<offset % offsetBatchSize>/<offset>/<term> <-- term is a sym-link to the data dir
 *   <baseDir>/latestCommitted <-- contains the <term>:<offset> of the max committed entry
 *   <baseDir>/votes/<term> <-- file contains the id of the voted-for node
 *   <baseDir>/currentTerm <-- file contains the id of the voted-for node
 * }}}
 * where 'term' is the name of the file containing the data
 *
 * @param baseDir
 */
final case class NioDisk(block: Blocking.Service, baseDir: Path, offsetBatchSize: Int) extends Service {
  require(Files.exists(baseDir) || Files.exists(Files.createDirectory(baseDir)), s"$baseDir is not a valid base directory")
  private val uncommittedLogDir = baseDir.resolve("data").ensuring { dir =>
    Files.exists(dir) || Files.exists(Files.createDirectory(dir))
  }
  private val committedLogDir = baseDir.resolve("committed").ensuring { dir =>
    Files.exists(dir) || Files.exists(Files.createDirectory(dir))
  }

  private val votesDir = baseDir.resolve("votes").ensuring { dir =>
    Files.exists(dir) || Files.exists(Files.createDirectory(dir))
  }
  private val currentTermFile = baseDir.resolve("currentTerm")
  private val latestCommittedFile = baseDir.resolve("latestCommitted")

  override def currentTerm(): IO[DiskError, Term] = {
    readString(currentTermFile).map(_.toInt).catchSome {
      case NonFatal(_) if !Files.isRegularFile(currentTermFile) => ZIO.succeed(0)
    }
  }

  override def updateTerm(term: Term): IO[DiskError, Unit] = {
    block.blocking {
      Task(Files.writeString(currentTermFile, term.toString, WRITE, CREATE, SYNC, TRUNCATE_EXISTING))
        .refineOrDie {
          case NonFatal(e) =>
            WriteError(s"Couldn't update term to $term : $e"): DiskError
        }
        .unit
    }
  }

  private def deleteRecursive(dir: Path): Unit = {
    if (Files.isDirectory(dir)) {
      Files.list(dir).forEach(deleteRecursive)
    }
    Files.delete(dir)
  }


  private def deleteUnderBatchDir(batchDir: Path, offset: Offset) = {
    Files.list(batchDir).forEach { dir =>
      if (dir.getFileName.toString.toInt >= offset.offset) {
        deleteRecursive(dir)
      }
    }
    val dirIsEmpty = !Files.list(batchDir).iterator().hasNext
    if (dirIsEmpty) {
      deleteRecursive(batchDir)
    }
  }

  private def deleteFrom(offset: Offset): IO[DiskError, Unit] = {
    latestCommitted().flatMap { latest: LogCoords =>
      if (!latest.isFirstEntry && latest.offset <= offset) {
        ZIO.fail(AttemptToDeleteCommitted(offset, latest))
      } else {
        val firstBatch = batchFrom(offset.offset)
        ZIO.foreachPar(batchDirs.toSeq) { btchDir =>
          btchDir.getFileName.toString match {
            case s"${from}-${_}" if from.toInt >= firstBatch =>
              Task(deleteUnderBatchDir(btchDir, offset)).refineOrDie {
                case NonFatal(e) => DiskError.delete(s"Couldn't delete $offset under $btchDir: $e")
              }
            case s"${_}-${_}" => ZIO.unit
            case garbageDir =>
              val deleteGarbage = Task(deleteRecursive(btchDir)).refineOrDie {
                case NonFatal(e) =>
                  WriteError(s"Couldn't delete $offset - unexpected batch directory '${garbageDir}': $e")
              }
              deleteGarbage.unit
          }
        }.unit
      }
    }
  }

  private def batchDirs = Files.list(uncommittedLogDir).iterator.asScala

  override def write(fromIndex: Offset, entries: Array[LogEntry]): IO[DiskError, Option[LogCoords]] = {
    if (entries.isEmpty) {
      ZIO.none
    } else {
      block.blocking {
        entries.length match {
          case 1 => writeEntry(fromIndex.offset, entries.head).map(coords => Some(coords))
          case _ =>
            val coords: IO[Nothing, Array[LogCoords]] = ZIO.foreachPar(entries.zipWithIndex) {
              case (entry, offset) =>
                writeEntry(fromIndex.offset + offset, entry).orDie
            }
            coords.map { written =>
              Some(written.maxBy(_.offset))
            }
        }
      }
    }
  }

  override def latestCommitted(): IO[DiskError, LogCoords] = {
    block.blocking {
      Task(latestCommittedUnsafe()).refineOrDie {
        case err => ReadError(s"Error listing contents of $uncommittedLogDir: $err")
      }
    }
  }

  override def latestUncommitted(): IO[DiskError, LogCoords] = {
    block.blocking {
      Task(latestUncommittedUnsafeSlow()).refineOrDie {
        case err => ReadError(s"Error listing contents of $uncommittedLogDir: $err")
      }
    }
  }

  private def readString(file: Path) = Task(new String(Files.readAllBytes(file), "UTF-8"))
    .refineOrDie {
      case NonFatal(e) => ReadError(s"Couldn't read $file :$e"): DiskError
    }

  override def termFor(offset: Offset): IO[DiskError, LogCoords] = {
    Task {
      val dir = offsetDir(offset.offset, false)
      termFile(dir).map(_.getFileName.toString.toInt).fold(LogCoords.empty) { term =>
        LogCoords(term, offset)
      }
    }.refineOrDie {
      case NonFatal(e) => DiskError.read(s"Couldn't read term under $offset: $e")
    }
  }

  override def readCommitted(offset: Offset, limit: Int): IO[DiskError, Array[Record]] = {
    readFromDir(committedLogDir, offset, limit, latestCommittedUnsafe())
  }

  override def readUncommitted(offset: Offset, limit: Int): IO[DiskError, Array[Record]] = {
    readFromDir(uncommittedLogDir, offset, limit, latestUncommittedUnsafeSlow())
  }

  private def readFromDir(underDir: Path, offset: Offset, limit: Int, latest: => LogCoords): IO[DiskError, Array[Record]] = {
    block.blocking {
      val job = limit match {
        case 1 => Task(readEntry(underDir, offset.offset).toArray)
        case n if n <= 0 => ZIO.succeed(Array.empty[Record])
        case n => Task(readAll(underDir, offset, n, latest))
      }
      job.refineOrDie {
        case err => ReadError(s"Error reading $offset from $uncommittedLogDir, limit $limit: $err")
      }
    }
  }

  private def readAll(underDir: Path, offset: Offset, limit: Int, latest: => LogCoords): Array[Record] = {
    var next = offset.offset
    var remaining = limit
    val builder = mutable.ArrayBuilder.make[Record]
    // a little cache to determine what we do if we read early (but missing) entries
    // in theory we should have the full, complete log, but in practice we may have done some log compaction
    var latestValue = Option.empty[LogCoords]
    while (remaining > 0) {
      readEntry(underDir, next) match {
        case None =>
          val coords = latestValue.getOrElse {
            val coords = latest
            latestValue = Some(coords)
            coords
          }
          if (coords.offset.offset > next) {
            next = next + 1
          } else {
            remaining = 0
          }
        case Some(entry) =>
          builder.addOne(entry)
          remaining = remaining - 1
          next = next + 1
      }
    }
    builder.result()

  }

  override def votedFor(term: Term) = block.blocking {
    val job = Task {
      val bytes = Files.readAllBytes(votesDir.resolve(term.toString))
      if (bytes.nonEmpty) {
        Option(new String(bytes, "UTF-8"))
      } else {
        None
      }
    }
    job.option.map(_.flatten)
  }


  override def voteFor(term: Term, node: NodeId): IO[DiskError, Unit] = block.blocking {
    val termFile = votesDir.resolve(term.toString)
    if (Files.isRegularFile(termFile)) {
      ZIO.fail(AlreadyVotedErr(term): DiskError)
    } else {
      Task(Files.write(termFile, node.getBytes("UTF-8"), CREATE, WRITE, SYNC, TRUNCATE_EXISTING))
        .unit
        .refineOrDie {
          case NonFatal(err) => WriteError(s"Can't vote for $node in $term: $err"): DiskError
        }
    }
  }

  private def latestCommittedUnsafe(): LogCoords = {
    if (Files.exists(latestCommittedFile)) {
      Files.readString(latestCommittedFile) match {
        case s"${term}:${offset}" => LogCoords(term.toInt, Offset(offset.toInt))
        case other => sys.error(s"Unexpected latest contents: >${other}<")
      }
    } else {
      LogCoords.empty
    }
  }

  private def latestUncommittedUnsafeSlow(): LogCoords = {
    import scala.jdk.CollectionConverters._
    val (_, batchDir) = Files.list(uncommittedLogDir).iterator.asScala.foldLeft((-1, null: Path)) {
      case (entry@(maxOffset, _), next) =>
        next.getFileName.toString match {
          case s"${from}-${_}" =>
            from.toIntOption.fold(entry) {
              case offset if offset > maxOffset => (offset, next)
              case _ => entry
            }
          case _ => entry
        }
    }
    Option(batchDir).fold(LogCoords.empty)(latestUnsafeSlow)
  }

  private def latestUnsafeSlow(inDir: Path): LogCoords = {
    import scala.jdk.CollectionConverters._
    val (maxIdx, idxDir) = Files.list(inDir).iterator.asScala.foldLeft((-1, null: Path)) {
      case (entry@(maxOffset, _), next) =>
        next.getFileName.toString.toIntOption.fold(entry) {
          case offset if offset > maxOffset => (offset, next)
          case _ => entry
        }
    }
    Option(idxDir).fold(LogCoords(0, Offset(0))) { dir =>
      val children = Files.list(dir)
      val term = Try(children.findFirst().get().getFileName.toString.toInt).getOrElse {
        sys.error(s"Expected a single file whose filename was the term under $dir but got ${children.toArray.size}: ${children.map(_.getFileName).toArray.mkString("\n")}")
      }
      LogCoords(term, Offset(maxIdx))
    }
  }

  private def readEntry(underDir: Path, offset: Int): Option[Record] = {
    val dir = offsetDirUnder(underDir, offset, false)
    termFile(dir).map { file =>
      val entry = LogEntry(file.getFileName.toString.toInt, Files.readAllBytes(file))
      Record(offset, entry)
    }
  }

  private def termFile(idxDir: Path): Option[Path] = {
    Try(Files.list(idxDir).findFirst().get()).toOption
  }

  private def batchFrom(index: Int): Int = (index / offsetBatchSize) * offsetBatchSize

  private def batchDir(index: Int): String = {
    val from = batchFrom(index)
    s"$from-${from + offsetBatchSize - 1}"
  }

  private def getOrCreateFile(file: Path) = {
    val ok = Files.isDirectory(file.getParent) || Files.isDirectory(Files.createDirectories(file.getParent))
    require(ok)
    file
  }

  private def dirOk(dir: Path) = Files.isDirectory(dir) || Files.isDirectory(Files.createDirectories(dir))

  private def offsetDir(index: Int, createIfMissing: Boolean) = offsetDirUnder(uncommittedLogDir, index, createIfMissing)

  private def committedOffsetDir(index: Int, createIfMissing: Boolean) = offsetDirUnder(committedLogDir, index, createIfMissing)

  private def offsetDirUnder(under: Path, index: Int, createIfMissing: Boolean) = {
    getOrCreateFile(under.resolve(batchDir(index)).resolve(index.toString).ensuring(d => !createIfMissing || dirOk(d)))
  }

  private def writeEntry(index: Int, entry: LogEntry): IO[DiskError, LogCoords] = {
    def write(offsetDir: Path) = Task {
      Files.createDirectories(offsetDir)
      val termFile = offsetDir.resolve(entry.term.toString)
      Files.write(termFile, entry.data, CREATE, TRUNCATE_EXISTING, WRITE, SYNC)
      LogCoords(entry.term, Offset(index))
    }.refineOrDie {
      case NonFatal(e) => WriteError(s"Error writing to $offsetDir: $e")
    }

    def verifyTermOk(offsetDir: Path) = {
      val singleChild: UIO[Option[Path]] = singleChildInDir(offsetDir)
      singleChild.flatMap {
        case None =>
          ZIO.unit
        case Some(termFile) =>
          val term = termFile.getFileName.toString.toInt
          if (entry.term != term) {
            deleteFrom(Offset(index)).unit
          } else Task.unit
      }
    }

    for {
      file <- Task(offsetDir(index, false)).orDie
      ok <- if (Files.exists(file)) {
        verifyTermOk(file) *> write(file)
      } else {
        write(file)
      }
    } yield ok
  }


  /**
   * There should be one file under the <offset> directory - the <term> file
   *
   * @param dir
   * @return
   */
  private def singleChildInDir(dir: Path): UIO[Option[Path]] = {
    val existing = Files.list(dir).iterator().asScala.take(2).toList
    existing match {
      case Nil => ZIO.none
      case List(file) => ZIO.some(file)
      case _ => ZIO.die(ReadError(s"Multiple files exist under ${dir.toAbsolutePath}"))
    }
  }

  private def commitOne(offset: Int): Option[LogCoords] = {
    val dir = offsetDir(offset, false)
    termFile(dir).map {
      file =>
        val fileName = file.getFileName.toString
        val linkFile = committedOffsetDir(offset, true).resolve(fileName)
        val coords = LogCoords(fileName.toInt, Offset(offset))
        Files.createSymbolicLink(linkFile.toAbsolutePath, file.toAbsolutePath)
        coords
    }
  }

  override def commit(commitToOffset: Offset): IO[DiskError, Unit] = {
    def updateLatestCommittedFile(coords: LogCoords) = {
      Files.writeString(latestCommittedFile, s"${
        coords.term
      }:${
        coords.offset.offset
      }", CREATE, WRITE, SYNC, TRUNCATE_EXISTING)
    }

    latestCommitted().flatMap {
      latest =>
        val fromIndex = latest.offset.offset
        commitToOffset.offset - fromIndex match {
          case n if n < 1 => ZIO.unit
          case 1 => Task(commitOne(commitToOffset.offset).foreach(updateLatestCommittedFile)).orDie
          case n =>
            val commitPar: ZIO[Any, WriteError, List[Option[LogCoords]]] = ZIO.foreachPar((fromIndex to n).toList) {
              i =>
                val idx = fromIndex + i
                ZIO.fromOption(commitOne(idx)).either.flatMap {
                  case Left(_) => ZIO.none
                  case Right(coords) => ZIO.some(coords)
                }
            }
            commitPar.map {
              committed =>
                val opt = committed.flatten.maxByOption(_.offset.offset)
                opt.foreach(updateLatestCommittedFile)
                opt
            }
        }
    }
  }
}

object NioDisk {
  def apply(baseDir: Path, offsetBatchSize: Int = 100, block: Blocking.Service = Blocking.Service.live): NioDisk = {
    require(Files.exists(baseDir) || Files.exists(Files.createDirectories(baseDir)), s"Couldn't create $baseDir")
    new NioDisk(block, baseDir, offsetBatchSize)
  }
}