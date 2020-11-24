package riff

import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import java.util.Base64

import zio._
import zio.duration.durationInt
import zio.random.Random

import scala.language.implicitConversions

final case class Offset(offset: Int) extends AnyVal with Ordered[Offset] {
  override def compare(that: riff.Offset): Int = offset.compareTo(that.offset)

  override def toString = offset.toString

  def inc(n: Int = 1) = copy(offset + n)

  def dec = copy((offset - 1).max(0))
}

object Offset {

  implicit object AsNum extends Numeric[Offset] {
    override def plus(x: Offset, y: Offset): Offset = Offset(x.offset + y.offset)

    override def minus(x: Offset, y: Offset): Offset = Offset(x.offset - y.offset)

    override def times(x: Offset, y: Offset): Offset = Offset(x.offset * y.offset)

    override def negate(x: Offset): Offset = Offset(-x.offset)

    override def fromInt(x: Int): Offset = Offset(x)

    override def parseString(str: String): Option[Offset] = str.toIntOption.map(fromInt)

    override def toInt(x: Offset): Term = x.offset

    override def toLong(x: Offset): Long = x.offset

    override def toFloat(x: Offset): Float = x.offset

    override def toDouble(x: Offset): Double = x.offset

    override def compare(x: Offset, y: Offset): Term = x.offset.compareTo(y.offset)
  }

}

final case class LogCoords(term: Term, offset: Offset) {
  override def toString = s"{offset: $offset, term:$term}"

  def isFirstEntry = offset.offset == 0
}

object LogCoords {
  val empty = LogCoords(0, Offset(0))
}

case class TimeRange(min: Duration, max: Duration) {
  def isFixed = min == max

  val nextDuration: URIO[Random, Int] = if (isFixed) {
    URIO.succeed(min.toMillis.toInt)
  } else {
    zio.random.nextIntBetween(min.toMillis.toInt, max.toMillis.toInt)
  }
}

object TimeRange {
  def apply(fixed: zio.duration.Duration): TimeRange = TimeRange(fixed, fixed)

  val defaultLeader: Duration = 100.millis
  val defaultFollower = TimeRange(300.millis, 600.millis)
}

/**
 * User-level requests
 */
sealed trait Request

object Request {

  final case class AppendEntries(term: Term,
                                 leaderId: NodeId,
                                 previous: LogCoords,
                                 leaderCommit: Offset,
                                 entries: Array[LogEntry]) extends Request {
    override def toString = {
      val label = if (entries.isEmpty) "Heartbeat" else "Append"
      s"$label { ${entries.size} entries, term:$term ldrId:$leaderId prev:$previous leaderKermit:${leaderCommit.offset} }"
    }

    def previousIsFirstOffset = previous.offset.offset == 0
  }

  object AppendEntries {
    def heartbeat(term: Term,
                  leaderId: NodeId,
                  previous: LogCoords,
                  leaderCommit: Offset) = new AppendEntries(
      term,
      leaderId,
      previous,
      leaderCommit,
      Array.empty)
  }

  final case class RequestVote(term: Term, candidateId: NodeId, latestLog: LogCoords) extends Request {
    override def toString = s"RequestVote from $candidateId in term $term, latestLog:${latestLog}"
  }

}

sealed trait Response

object Response {

  final case class AppendEntriesResponse(term: Term, success: Boolean, matchIndex: Offset) extends Response {
    override def toString = s"AppendResponse ok:$success in term $term, matchIndex:${matchIndex.offset}"
  }

  final case class RequestVoteResponse(term: Term, granted: Boolean) extends Response {
    override def toString = s"VoteResponse ok:$granted in term $term"
  }

}

/** The valid inputs into our system */
sealed trait Input

object Input {
  def request(fromNode: NodeId, msg: Request) = Input.UserInput(fromNode, Left(msg))

  def response(fromNode: NodeId, msg: Response) = Input.UserInput(fromNode, Right(msg))

  def append(data: Array[Byte]): Input.Append = Input.Append(data)

  def append(data: String): Append = append(data.getBytes("UTF-8"))

  /** @param node either None (in the case of a follower/candidate, meaning we need to start an election) or some(peer)
   *              in the case where we need to send a HB message to the peer
   */
  case class HeartbeatTimeout(node: Option[NodeId]) extends Input

  case class UserInput(fromNode: NodeId, message: Either[Request, Response]) extends Input {
    override def toString: NodeId = {
      message match {
        case Left(value) =>
          s"Request: $fromNode: $value"
        case Right(value) =>
          s"Response $fromNode: $value"
      }
    }
  }

  case class Append(data: Array[Byte]) extends Input {
    override def toString = s"Append(${data.size} bytes)"
  }

  object Append {
    def apply(text: String): Append = new Append(text.getBytes(StandardCharsets.UTF_8))
  }

  object UserInput {
    def apply(fromNode: NodeId, message: Request) = new UserInput(fromNode, Left(message))

    def apply(fromNode: NodeId, message: Response) = new UserInput(fromNode, Right(message))
  }

}

final case class Record(offset: Int, entry: LogEntry) {
  def dataAsString = entry.dataAsString

  def term = entry.term

  def data = entry.data
}

final case class LogEntry(term: Term, data: Array[Byte]) {
  override def equals(obj: Any): Boolean = obj match {
    case LogEntry(`term`, bytes) => java.util.Arrays.equals(data, bytes)
    case _ => false
  }

  def dataAsString: String = new String(data, "UTF-8")
  def dataBase64: String = Base64.getEncoder.encodeToString(data)

  override def hashCode(): Term = java.util.Arrays.hashCode(data) ^ term.hashCode() * 37
}

final case class ClusterPeer(id: NodeId,
                             nextIndex: Offset = Offset(0),
                             matchIndex: Offset = Offset(0),
                             lastMessageReceived: Option[Instant] = None,
                             lastHearbeatSent: Option[Instant] = None) {
  override def toString: NodeId = s"match: ${matchIndex.offset}, next: ${nextIndex.offset}"

  def onHeartbeat(now: Instant): ClusterPeer = {
    copy(lastMessageReceived = Option(now),
      lastHearbeatSent = Option(now))
  }

  def update(now: Instant, success: Boolean, offset: Offset) = {
    if (success) {
      copy(lastMessageReceived = Option(now), matchIndex = offset, nextIndex = offset.inc())
    } else {
      copy(lastMessageReceived = Option(now), nextIndex = offset.dec)
    }
  }
}

sealed class RaftNodeError(msg: String) extends Exception(msg)

object NotTheLeader extends RaftNodeError("Not the leader")