package riff.json

import java.time.Instant
import java.util.Base64

import io.circe.Decoder.Result
import io.circe._
import io.circe.syntax._
import riff.Input.{Append, HeartbeatTimeout, UserInput}
import riff.RiffRequest.{AppendEntries, RequestVote}
import riff.RiffResponse.{AppendEntriesResponse, RequestVoteResponse}
import riff.Role.{Candidate, Follower, Leader}
import riff._
import zio.Schedule
import zio.duration.{Duration, durationInt}

object RiffCodec {

  sealed trait AddressedMessage

  implicit object LogEntryCodec extends Codec[LogEntry] {
    override def apply(value: LogEntry): Json = {
      Json.obj(
        "term" -> value.term.asJson,
        "data" -> value.dataBase64.asJson,
      )
    }

    override def apply(c: HCursor): Result[LogEntry] = {
      for {
        term <- c.downField("term").as[Int]
        base64 <- c.downField("data").as[String]
      } yield LogEntry(term, Base64.getDecoder.decode(base64))
    }
  }

  implicit object OffsetCodec extends Codec[Offset] {
    override def apply(a: Offset): Json = a.offset.asJson

    override def apply(c: HCursor): Result[Offset] = c.as[Int].map(Offset.apply)
  }

  implicit object LogCoordsCodec extends Codec[LogCoords] {
    override def apply(value: LogCoords): Json = Json.obj(
      "term" -> value.term.asJson,
      "offset" -> value.offset.asJson,
    )

    override def apply(c: HCursor): Result[LogCoords] = for {
      term <- c.downField("term").as[Int]
      offset <- c.downField("offset").as[Offset]
    } yield LogCoords(term, offset)
  }

  implicit object RequestCodec extends Codec[RiffRequest] {
    override def apply(value: RiffRequest): Json = value match {
      case AppendEntries(term, leaderId, previous, leaderCommit, entries) =>
        Json.obj(
          "term" -> term.asJson,
          "leaderId" -> leaderId.asJson,
          "previous" -> previous.asJson,
          "leaderCommit" -> leaderCommit.asJson,
          "entries" -> entries.asJson
        )

      case RequestVote(term, candidateId, latestLog) => Json.obj(
        "term" -> term.asJson,
        "candidateId" -> candidateId.asJson,
        "latest" -> latestLog.asJson
      )
    }


    override def apply(c: HCursor): Result[RiffRequest] = {
      asAppendEntries(c).orElse(asRequestVote(c))
    }

    def asAppendEntries(c: HCursor): Result[AppendEntries] = {
      for {
        term <- c.downField("term").as[Int]
        leaderId <- c.downField("leaderId").as[String]
        previous <- c.downField("previous").as[LogCoords]
        leaderCommit <- c.downField("leaderCommit").as[Offset]
        entries <- c.downField("entries").as[Array[LogEntry]]
      } yield AppendEntries(term, leaderId, previous, leaderCommit, entries)
    }

    def asRequestVote(c: HCursor): Result[RequestVote] = {
      for {
        term <- c.downField("term").as[Int]
        candidateId <- c.downField("candidateId").as[String]
        latest <- c.downField("latest").as[LogCoords]
      } yield RequestVote(term, candidateId, latest)
    }
  }

  implicit object ResponseCodec extends Codec[RiffResponse] {
    override def apply(value: RiffResponse): Json = value match {
      case AppendEntriesResponse(term, success, matchIndex) => Json.obj(
        "term" -> term.asJson,
        "success" -> success.asJson,
        "matchIndex" -> matchIndex.asJson,
      )
      case RequestVoteResponse(term, granted) => Json.obj(
        "term" -> term.asJson,
        "granted" -> granted.asJson
      )
    }

    override def apply(c: HCursor): Result[RiffResponse] = {
      asAppendDataResponse(c).orElse(asRequestVoteResponse(c))
    }

    def asAppendDataResponse(c: HCursor): Result[AppendEntriesResponse] = {
      for {
        term <- c.downField("term").as[Int]
        success <- c.downField("success").as[Boolean]
        matchIndex <- c.downField("matchIndex").as[Offset]
      } yield AppendEntriesResponse(term, success, matchIndex)
    }

    def asRequestVoteResponse(c: HCursor): Result[RequestVoteResponse] = {
      for {
        term <- c.downField("term").as[Int]
        granted <- c.downField("granted").as[Boolean]
      } yield RequestVoteResponse(term, granted)
    }
  }

  implicit object UserInputCodec extends Codec[UserInput] {
    override def apply(value: UserInput): Json = {
      value.message match {
        case Left(request) => Json.obj(
          "fromNode" -> value.fromNode.asJson,
          "request" -> request.asJson
        )
        case Right(response) => Json.obj(
          "fromNode" -> value.fromNode.asJson,
          "response" -> response.asJson
        )
      }
    }

    override def apply(c: HCursor): Result[UserInput] = fromRequest(c).orElse(fromResponse(c))

    def fromRequest(c: HCursor): Result[UserInput] = {
      for {
        fromNode <- c.downField("fromNode").as[String]
        request <- c.downField("request").as[RiffRequest]
      } yield UserInput(fromNode, Left(request))
    }

    def fromResponse(c: HCursor): Result[UserInput] = {
      for {
        fromNode <- c.downField("fromNode").as[String]
        response <- c.downField("response").as[RiffResponse]
      } yield UserInput(fromNode, Right(response))
    }
  }

  object InputCodec extends Codec[Input] {
    override def apply(c: HCursor): Result[Input] = {
      def asHB = c.downField("heartbeat").as[Option[NodeId]].map(HeartbeatTimeout.apply)

      def asAppend = c.downField("append").as[String].map { base64data =>
        val bytes = Base64.getDecoder.decode(base64data)
        Append(bytes)
      }

      UserInputCodec(c).orElse(asHB).orElse(asAppend)
    }

    override def apply(value: Input): Json = {
      value match {
        case userInput: UserInput => UserInputCodec(userInput)
        case HeartbeatTimeout(opt) => Json.obj("heartbeat" -> opt.asJson)
        case Append(data) => Json.obj("append" -> Base64.getEncoder.encodeToString(data).asJson)
      }
    }
  }

  case class Broadcast(from: NodeId, message: RiffRequest) extends AddressedMessage

  object Broadcast {
    implicit val codec = io.circe.generic.semiauto.deriveCodec[Broadcast]
  }

  case class DirectMessage(from: NodeId, to: NodeId, message: Either[RiffRequest, RiffResponse]) extends AddressedMessage {
    def asUserInput = UserInput(from, message)
  }

  object DirectMessage {

    implicit object codec extends Codec[DirectMessage] {
      override def apply(value: DirectMessage): Json = {
        value.message match {
          case Left(request) => Json.obj(
            "from" -> value.from.asJson,
            "to" -> value.to.asJson,
            "request" -> request.asJson,
          )
          case Right(response) => Json.obj(
            "from" -> value.from.asJson,
            "to" -> value.to.asJson,
            "response" -> response.asJson
          )
        }
      }

      override def apply(c: HCursor): Result[DirectMessage] = {
        asRequest(c).orElse(asResponse(c))
      }

      def asRequest(c: HCursor): Result[DirectMessage] = {
        for {
          from <- c.downField("from").as[String]
          to <- c.downField("to").as[String]
          message <- c.downField("request").as[RiffRequest]
        } yield DirectMessage(from, to, Left(message))
      }

      def asResponse(c: HCursor): Result[DirectMessage] = {
        for {
          from <- c.downField("from").as[String]
          to <- c.downField("to").as[String]
          message <- c.downField("response").as[RiffResponse]
        } yield DirectMessage(from, to, Right(message))
      }
    }
  }

  implicit object AddressedMessageCodec extends Codec[AddressedMessage] {
    override def apply(a: AddressedMessage): Json = {
      a match {
        case value: Broadcast => value.asJson
        case value: DirectMessage => value.asJson
      }
    }

    override def apply(c: HCursor): Result[AddressedMessage] = {
      c.as[Broadcast].orElse(c.as[DirectMessage])
    }
  }

  implicit object ClusterPeerCodec extends Codec[ClusterPeer] {
    override def apply(c: HCursor): Result[ClusterPeer] = {
      for {
        id <- c.downField("id").as[NodeId]
        nextIndex <- c.downField("nextIndex").as[Offset]
        matchIndex <- c.downField("matchIndex").as[Offset]
        lastMessageReceived <- c.downField("lastMessageReceived").as[Option[Instant]]
        lastHearbeatSent <- c.downField("lastHearbeatSent").as[Option[Instant]]
      } yield ClusterPeer(id, nextIndex, matchIndex, lastMessageReceived, lastHearbeatSent)
    }

    override def apply(peer: ClusterPeer): Json = {
      import peer._
      Json.obj(
        "id" -> id.asJson,
        "nextIndex" -> nextIndex.asJson,
        "matchIndex" -> matchIndex.asJson,
        "lastMessageReceived" -> lastMessageReceived.asJson,
        "lastHearbeatSent" -> lastHeartbeatSent.asJson
      )
    }
  }

  implicit object LeaderCodec extends Codec[Leader] {
    override def apply(c: HCursor): Result[Leader] = {
      c.as[Map[NodeId, ClusterPeer]].map(Leader.apply)
    }

    override def apply(value: Leader): Json = {
      value.clusterView.asJson
    }
  }

  implicit object CandidateCodec extends Codec[Candidate] {
    override def apply(c: HCursor): Result[Candidate] = {
      for {
        votesFor <- c.downField("votesFor").as[Set[String]]
        votesAgainst <- c.downField("votesAgainst").as[Set[String]]
        peers <- c.downField("peers").as[Set[String]]
      } yield Candidate(votesFor, votesAgainst, peers)
    }

    override def apply(value: Candidate): Json = {
      Json.obj(
        "votesFor" -> value.votesFor.asJson,
        "votesAgainst" -> value.votesAgainst.asJson,
        "peers" -> value.peers.asJson
      )
    }
  }

  implicit object RoleCodec extends Codec[Role] {
    override def apply(c: HCursor): Result[Role] = {
      c.as[String] match {
        case Right("Follower") => Right(Follower)
        case _ => CandidateCodec(c).orElse(LeaderCodec(c))
      }
    }

    override def apply(bread: Role): Json = {
      bread match {
        case Follower => "Follower".asJson
        case value: Candidate => CandidateCodec(value)
        case value: Leader => LeaderCodec(value)
      }
    }
  }

  case class Snapshot(ourNodeId: NodeId,
                      term: Term,
                      commitIndex: Offset, // volatile state of last committed index
                      lastApplied: Offset, //index of highest log entry applied to state machine (initialized to 0, increases monotonically)
                      maxSendBatchSize: Int,
                      role: Role,
                      currentLeaderId: Option[NodeId]) {

    def asState(clusterRetrySchedule: Schedule[Any, Any, Duration] = Schedule.fromDuration(1.second),
                minClusterSize: Option[Int] = None): RaftNodeState = {
      RaftNodeState(
        ourNodeId,
        term,
        commitIndex,
        lastApplied,
        maxSendBatchSize,
        role,
        currentLeaderId,
        clusterRetrySchedule,
        minClusterSize
      )
    }
  }

  object Snapshot {
    implicit val codec = io.circe.generic.semiauto.deriveCodec[Snapshot]

    def apply(state: RaftNodeState): Snapshot = {
      import state._
      new Snapshot(ourNodeId,
        term,
        commitIndex,
        lastApplied,
        maxSendBatchSize,
        role,
        currentLeaderId
      )
    }
  }
}
