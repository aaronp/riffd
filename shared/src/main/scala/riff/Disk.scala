package riff

import java.nio.ByteBuffer

import zio._

/**
 * The means to access local storage
 */
object Disk {

  trait Service {

    /**
     * Writes uncommitted
     *
     * @param fromIndex
     * @param entry
     * @return a ZIO w/ an option of the last written coords
     */
    def write(fromIndex: Offset, entry: LogEntry): IO[DiskError, Option[LogCoords]] = write(fromIndex, Array(entry))

    /**
     *
     * @param fromIndex
     * @param entries
     * @return the last offset written
     */
    def write(fromIndex: Offset, entries: Array[LogEntry]): IO[DiskError, Option[LogCoords]]

    /**
     * ONLY write if the previous matches. If it doesn't DELETE the entry/subsequent entries
     *
     * {{{
     * If an existing entry conflicts with a new one (same index
     * but different terms), delete the existing entry and all that
     * follow it (§5.3) https://raft.github.io/raft.pdf
     * }}}
     *
     * @param previous the previous log to check
     * @param entries  the entries to write
     * @return an optional LogCoords of the latest written entry
     */
    def appendEntriesOnMatch(previous: LogCoords, entries: Array[LogEntry]): IO[DiskError, Option[LogCoords]] = {
      termFor(previous.offset).flatMap {
        case ourPrev if ourPrev == previous =>
          if (entries.isEmpty) {
            ZIO.succeed(Option(ourPrev))
          } else {
            write(previous.offset.inc(), entries)
          }
        case _ => ZIO.none
      }
    }

    def readCommitted(offset: Offset, limit: Int): IO[DiskError, Array[Record]]

    def readUncommitted(offset: Offset, limit: Int): IO[DiskError, Array[Record]]

    def termFor(offset: Offset): IO[DiskError, LogCoords]

    def latestCommitted(): IO[DiskError, LogCoords]

    def latestUncommitted(): IO[DiskError, LogCoords]

    def commit(commitToOffset: Offset): IO[DiskError, Unit]

    def votedFor(term: Term): IO[DiskError, Option[NodeId]]

    /**
     * NOTE - this function should fail with [[AlreadyVotedErr]] if there's already a vote for the given term
     * @param term the term in which to vote
     * @param node the node to vote for
     * @return an operation which will set the nodeId for the recipient of the vote in the given term
     */
    def voteFor(term: Term, node: NodeId): IO[DiskError, Unit]

    def currentTerm(): IO[DiskError, Term]

    def updateTerm(term: Term): IO[DiskError, Unit]


    /**
     * @param from  if positive, the disk to show. If zero/negative, then from will be the latest record - limit
     * @param limit the limit to show, or 0 for all entries
     * @return an ascii table of the records
     */
    def ascii(from: Int = -1, limit: Int = 100, indent: String = "    "): IO[DiskError, String] = {
      for {
        committed <- latestCommitted
        uncommtted <- latestUncommitted()
        actualLimit = if (limit <= 0) uncommtted.offset.offset else limit
        actualFrom = if (from > 0) from else (uncommtted.offset.offset - actualLimit).max(0)
        entries <- readUncommitted(Offset(actualFrom), actualLimit)
      } yield {
        val rows = entries.map {
          case record =>
            val offStr = record.offset.toString.padTo(3, ' ')
            val trmStr = record.term.toString.padTo(3, ' ')
            val committedChar = if (record.offset <= committed.offset.offset) "✅" else "❓"
            val data = if (record.data.size > 1000) s"${record.data.size} bytes" else record.entry.dataAsString
            s" ${offStr}    | ${trmStr}  | $committedChar        | ${data}"
        }
        if (rows.isEmpty) {
          "<empty log>"
        } else {
          val header = s""" Offset | Term | Committed | Data"""
          val sep = header.map(_ => '-')
          rows.mkString(s"$indent latest commit: $committed\n$indent latest uncommitted: $uncommtted\n$indent$header\n$indent$sep\n$indent", s"\n$indent", "\n")
        }
      }
    }
  }

  def inMemory(namespace: String): ZIO[Any, Nothing, Service] = DiskMap(namespace)

  def write(fromIndex: Offset, entries: Array[LogEntry]) = {
    ZIO.accessM[Disk](_.get.write(fromIndex, entries))
  }

  def appendEntriesOnMatch(previous: LogCoords, entries: Array[LogEntry]) = {
    ZIO.accessM[Disk](_.get.appendEntriesOnMatch(previous, entries))
  }

  def append(term: Term, offset: Offset, data: Array[Byte]): ZIO[Disk, DiskError, Unit] = {
    write(offset, LogEntry(term, data)).unit
  }

  def readCommitted(offset: Offset, limit: Int = 1): ZIO[Disk, DiskError, Array[Record]] = ZIO.accessM[Disk](_.get.readCommitted(offset, limit))

  def readUncommitted(offset: Offset, limit: Int = 1): ZIO[Disk, DiskError, Array[Record]] = ZIO.accessM[Disk](_.get.readUncommitted(offset, limit))

  def votedFor(term: Term): ZIO[Disk, DiskError, Option[NodeId]] = ZIO.accessM[Disk](_.get.votedFor(term))

  def voteFor(term: Term, node: NodeId) = ZIO.accessM[Disk](_.get.voteFor(term, node))

  def readOne(offset: Offset): ZIO[Disk, DiskError, Option[Record]] = readCommitted(offset, 1).map(_.headOption)

  def termFor(offset: Offset): ZIO[Disk, DiskError, LogCoords] = {
    ZIO.accessM[Disk](_.get.termFor(offset))
  }

  def write(fromIndex: Offset, entry: LogEntry): ZIO[Disk, DiskError, Option[LogCoords]] = {
    ZIO.accessM[Disk](_.get.write(fromIndex, entry))
  }

  def currentTerm() = ZIO.accessM[Disk](_.get.currentTerm())

  def updateTerm(term: Term) = ZIO.accessM[Disk](_.get.updateTerm(term))

  val latestCommitted: URIO[Disk, LogCoords] = ZIO.accessM[Disk](_.get.latestCommitted()).orDie

  def commit(toOffset: Offset) = ZIO.accessM[Disk](_.get.commit(toOffset)).orDie


  private[riff] def boolBytes(value: Boolean) = if (value) Array[Byte](1) else Array[Byte](0)

  private[riff] def readBool(bytes: Array[Byte]) = bytes.exists(_ > 0)

  private[riff] def stringBytes(value: String) = value.getBytes("UTF-8")

  private[riff] def readString(bytes: Array[Byte]) = new String(bytes, "UTF-8")

  private[riff] def intBytes(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

  private[riff] def readInt(bytes: Array[Byte]): Int = ByteBuffer.wrap(bytes).getInt(0)

}

sealed trait DiskError extends Throwable

object DiskError {
  def read(err: String): DiskError = ReadError(err)

  def write(err: String): DiskError = WriteError(err)

  def delete(err: String): DiskError = DeleteError(err)
}

final case class AttemptToDeleteCommitted(attempted: Offset, latestCommit: LogCoords) extends Exception(s"tried to overwrite committed $latestCommit with a write attempt at offset $attempted") with DiskError

final case class AlreadyVotedErr(existingTerm: Term) extends Exception(s"already voted in term $existingTerm") with DiskError

final case class WriteError(message: String) extends Exception(message) with DiskError

final case class DeleteError(message: String) extends Exception(message) with DiskError

final case class ReadError(message: String) extends Exception(message) with DiskError