package riff

import zio.{IO, Ref, UIO, ZIO}

/**
 * a map-like based [[Disk]] service
 * Keys:
 * {{{
 *   <prefix>.data.<offset>.term
 *   <prefix>.data.<offset>.value
 *   <prefix>.data.<offset>.committed
 *   <prefix>.firstOffset
 *   <prefix>.latestCommitted
 *   <prefix>.latestUncommitted
 *   <prefix>.voted.<term>
 *   <prefix>.currentTerm
 * }}}
 *
 */
abstract class DiskMap(namespace: String) extends Disk.Service {

  private object keys {
    private def dataKey(offset: Int, suffix: String) = s"$namespace.data.$offset.$suffix"

    /** @param offset the offset
     * @return the key to the 'term' for this offset
     */
    def dataTerm(offset: Int) = dataKey(offset, "term")

    /** @param offset the offset
     * @return the key to the 'value' for this offset
     */
    def dataValue(offset: Int) = dataKey(offset, "value")

    /** @param offset the offset
     * @return the key to the 'committed' boolean
     */
    def dataCommitted(offset: Int) = dataKey(offset, "committed")

    // TODO - needed for log compaction
    //def firstOffset = s"${namespace}.firstOffset"

    def latestCommitted = s"${namespace}.latestCommitted"

    def latestUncommitted = s"${namespace}.latestUncommitted"

    def voteIn(term: Term) = s"${namespace}.voted.$term"

    def currentTerm = s"${namespace}.currentTerm"
  }

  /**
   * @param key   the key of the mapped value
   * @param value the value to write
   * @return a IO which writes the key/value
   */
  def upsert(key: String, value: Array[Byte]): UIO[Unit]

  /**
   * @param key a key to the map
   * @return the data held in the map at the particular key
   */
  def readData(key: String): UIO[Option[Array[Byte]]]

  /**
   * @param keys the keys to remove
   * @return an operation to remove the given keys
   */
  def deleteKeys(keys: Set[String]): UIO[Unit]

  /**
   * delete logs after the given offset
   */
  protected def deleteEntries(from: Offset, to: Offset): ZIO[Any, DiskError, Unit] = {
    latestCommitted().flatMap { latest: LogCoords =>
      if (!latest.isFirstEntry && latest.offset <= from) {
        IO.fail(AttemptToDeleteCommitted(from, latest))
      } else {
        val allKeys = (from.offset to to.offset).flatMap { offset =>
          List(
            keys.dataCommitted(offset),
            keys.dataTerm(offset),
            keys.dataValue(offset)
          )
        }

        deleteKeys(allKeys.toSet)
      }
    }
  }

  private def writeDataEntry(offset: Int, entry: LogEntry): UIO[Unit] = {
    for {
      _ <- upsert(keys.dataTerm(offset), Disk.intBytes(entry.term))
      _ <- upsert(keys.dataValue(offset), entry.data)
    } yield ()
  }

  override def commit(commitToOffset: Offset): IO[DiskError, Unit] = {
    def kermitFrom(from: Int, to: Int) = {
      UIO.foreach((from to to).toList) { i =>
        upsert(keys.dataCommitted(i), Disk.boolBytes(true))
      }.unless(from >= commitToOffset.offset)
    }

    for {
      before <- latestCommitted()
      max <- latestUncommitted()
      to = max.offset.offset.min(commitToOffset.offset)
      _ <- kermitFrom(before.offset.offset, to)
      _ <- upsert(keys.latestCommitted, Disk.intBytes(to))
    } yield ()
  }

  override def votedFor(term: Term): IO[DiskError, Option[NodeId]] = readData(keys.voteIn(term)).map(_.map(Disk.readString))

  override def voteFor(term: Term, node: NodeId): IO[DiskError, Unit] = {
    votedFor(term).flatMap {
      case None => upsert(keys.voteIn(term), Disk.stringBytes(node))
      case Some(_) => IO.fail(AlreadyVotedErr(term))
    }
  }

  override def currentTerm(): IO[DiskError, Term] = {
    readData(keys.currentTerm).map(_.fold(0)(Disk.readInt))
  }

  override def updateTerm(term: Term): IO[DiskError, Unit] = {
    upsert(keys.currentTerm, Disk.intBytes(term))
  }

  override def write(fromIndex: Offset, entries: Array[LogEntry]): IO[DiskError, Option[LogCoords]] = {
    for {
      written <- IO.foreach(entries.zipWithIndex) {
        case (entry, i) => writeEntry(fromIndex.offset + i, entry)
      }
      latest = written.maxBy(_.offset)
      _ <- upsert(keys.latestUncommitted, Disk.intBytes(latest.offset.offset))
    } yield Some(latest)
  }

  private def isCommitted(offset: Int) = {
    for {
      isCommittedBytes <- readData(keys.dataCommitted(offset))
      isCommitted = isCommittedBytes.fold(false)(Disk.readBool)
    } yield isCommitted
  }

  override def readCommitted(offset: Offset, limit: Term): IO[DiskError, Array[Record]] = {
    val list = IO.foreach((offset.offset until offset.offset + limit).toArray) { idx =>
      readData(keys.dataValue(idx)).flatMap {
        case None => IO.none
        case Some(data) =>
          isCommitted(offset.offset).flatMap {
            case true => asRecord(offset, data).map(Option.apply)
            case false => IO.none
          }
      }
    }
    list.map(_.flatten)
  }

  private def asRecord(offset: Offset, data: Array[Byte]) = {
    termFor(offset).map { term =>
      Record(offset.offset, LogEntry(term.term, data))
    }
  }

  override def readUncommitted(offset: Offset, limit: Term): IO[DiskError, Array[Record]] = {
    latestUncommitted().flatMap { last: LogCoords =>
      readLimit(offset.offset, last, limit, Vector.empty)
    }
  }

  private val noRecords = IO.succeed(Array.empty[Record])

  private def readLimit(index: Int, lastUncommitted: LogCoords, remaining: Term, buffer: Vector[Record]): IO[DiskError, Array[Record]] = {
    remaining match {
      case n if n <= 0 => IO.succeed(buffer.toArray)
      case _ =>
        readData(keys.dataValue(index)).flatMap {
          case None if lastUncommitted.offset.offset > index =>
            // we're perhaps reading behind a conflated log
            readLimit(index + 1, lastUncommitted, remaining, buffer)
          case None => IO.succeed(buffer.toArray)
          case Some(data) =>
            asRecord(Offset(index), data).flatMap { record =>
              readLimit(index + 1, lastUncommitted, remaining - 1, buffer :+ record)
            }
        }
    }
  }

  private def writeEntry(index: Int, entry: LogEntry): IO[DiskError, LogCoords] = {
    val write = writeDataEntry(index, entry).as(LogCoords(entry.term, Offset(index)))
    termFor(Offset(index)).flatMap {
      case term if term.isFirstEntry => write
      case riff.LogCoords(term, _) =>
        val check = latestUncommitted.map(_.offset).flatMap(deleteEntries(Offset(index), _)).unless(entry.term == term)
        check *> write
    }
  }

  override def termFor(offset: Offset): IO[DiskError, LogCoords] = {
    for {
      termBytes <- readData(keys.dataTerm(offset.offset))
      termOpt = termBytes.map(bytes => Disk.readInt(bytes))
      coordOpt = termOpt.fold(LogCoords.empty) { term =>
        LogCoords(term, offset)
      }
    } yield coordOpt
  }

  override def latestCommitted(): IO[DiskError, LogCoords] = {
    readData(keys.latestCommitted).flatMap {
      case None => UIO.effectTotal(LogCoords.empty)
      case Some(offsetBytes) =>
        val offset = Offset(Disk.readInt(offsetBytes))
        termFor(offset)
    }
  }

  override def latestUncommitted(): IO[DiskError, LogCoords] = {
    readData(keys.latestUncommitted).flatMap {
      case None => UIO.effectTotal(LogCoords.empty)
      case Some(offsetBytes) =>
        val offset = Offset(Disk.readInt(offsetBytes))
        termFor(offset)
    }
  }
}

object DiskMap {
  def apply(namespace: String): ZIO[Any, Nothing, Disk.Service] = {
    Ref.make(Map[String, Array[Byte]]()).map { ref =>
      new InMemory(namespace, ref)
    }
  }

  class InMemory(namespace: String, ref: Ref[Map[String, Array[Byte]]]) extends DiskMap(namespace) {
    override def upsert(key: String, value: Array[Byte]): UIO[Unit] = ref.update(_.updated(key, value))

    override def readData(key: String): UIO[Option[Array[Byte]]] = ref.get.map(_.get(key))

    def deleteKeys(keys: Set[String]): UIO[Unit] = ref.update { map =>
      map.removedAll(keys)
    }

    def pretty: ZIO[Any, Nothing, String] = ref.get.map { map =>
      map.toList.sortBy(_._1).map {
        case (key, v) if key.endsWith(".committed") => s"$key : ${Disk.readBool(v)}"
        case (key, v) if key.endsWith(".value") => s"$key : ${Disk.readString(v)}"
        case (key, v) => s"$key : ${Disk.readInt(v)}"
      }.mkString("\n")
    }
  }

}