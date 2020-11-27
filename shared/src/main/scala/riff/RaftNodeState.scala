package riff

import riff.Role.{Candidate, Follower, Leader}
import zio._
import zio.duration._

import scala.math.Ordering.Implicits.infixOrderingOps

/**
 *
 * @see https://raft.github.io/raft.pdf
 * @param ourNodeId            the ID for this node
 * @param term                 our current term
 * @param commitIndex          the cached last applied commit index
 * @param lastApplied          the cached last applied log index
 * @param maxSendBatchSize     how many [[riff.Request.AppendEntries]] records should we send at a time?
 * @param role                 our current [[Role]]
 * @param clusterRetrySchedule a schedule used to for retrying failed [[Cluster]] sends (TODO: remove this and just incorporate in the passed-in [[Cluster]])
 * @param currentLeaderId      the known leader in this term
 * @param minClusterSize          this was added to prevent cluster nodes from electing themselves before peers are up -- e.g. to prevent single-node clusters
 */
final case class RaftNodeState(ourNodeId: NodeId,
                               term: Term,
                               commitIndex: Offset, // volatile state of last committed index
                               lastApplied: Offset, //index of highest log entry applied to state machine (initialized to 0, increases monotonically)
                               maxSendBatchSize: Int,
                               role: Role,
                               currentLeaderId: Option[NodeId],
                               clusterRetrySchedule: Schedule[Any, Any, Duration],
                               minClusterSize: Option[Int]) {

  def update(input: Input): ZIO[FullEnv, RaftNodeError, RaftNodeState] = {
    input match {
      case Input.Append(data) => appendData(data)
      case Input.HeartbeatTimeout(peerOpt) => onHeartbeatTimeout(peerOpt)
      case Input.UserInput(from, Left(request: Request.RequestVote)) => onRequestVote(from, request)
      case Input.UserInput(from, Left(request: Request.AppendEntries)) => onAppendEntries(from, request)
      case Input.UserInput(from, Right(response: Response.RequestVoteResponse)) => onRequestVoteResponse(from, response)
      case Input.UserInput(from, Right(response: Response.AppendEntriesResponse)) => onAppendEntriesResponse(from, response)
    }
  }

  def onAppendEntries(from: NodeId, request: Request.AppendEntries): ZIO[FullEnv, Nothing, RaftNodeState] = {
    if (request.term > term) {
      for {
        follower <- becomeFollower(request.term, Some(request.leaderId))
        newState <- follower.appendDataToLog(from, request)
      } yield newState
    } else {
      role match {
        case Leader(_) =>
          val response = Response.AppendEntriesResponse(term, false, commitIndex)
          Cluster.sendResponse(from, response).retry(clusterRetrySchedule).orDie.as(this)
        case Candidate(_, _, _) =>
          for {
            follower <- becomeFollower(request.term, Some(request.leaderId))
            newState <- follower.appendDataToLog(from, request)
          } yield newState
        case _ => appendDataToLog(from, request)
      }
    }
  }

  def onAppendEntriesResponse(from: NodeId, response: Response.AppendEntriesResponse) = {
    role match {
      case leader@Role.Leader(_) => onAppendEntriesResponseWhenLeader(from, response, leader)
      case _ => noop
    }
  }

  /** if the response failed, then matchIndex is the latest commit index.
   * if the response succeeds, then matchIndex is taken as the latest index
   *
   * @param from
   * @param response
   * @param leader
   * @return
   */
  private def onAppendEntriesResponseWhenLeader(from: NodeId, response: Response.AppendEntriesResponse, leader: Leader) = {
    val Response.AppendEntriesResponse(_, success, matchIndex) = response
    leader.update(from, success, matchIndex).flatMap {
      case (updatedLeaderState, peer: ClusterPeer) =>
        val previousCommit = commitIndex
        val latestCommit = Offset(updatedLeaderState.latestQuarum).max(commitIndex)

        val newState = copy(role = updatedLeaderState, commitIndex = latestCommit)
        val sendJob = if (success) {
          if (lastApplied >= peer.nextIndex) {
            newState.sendDataToNode(from, response, peer.nextIndex, maxSendBatchSize)
          } else {
            // do we need up update our peer
            val thisResponseCausedACommitDueToQuarum = previousCommit < latestCommit || peer.matchIndex < latestCommit
            if (thisResponseCausedACommitDueToQuarum) {
              updatedLeaderState.broadcastLeaderHeartbeat(ourNodeId, term, clusterRetrySchedule)
            } else {
              Logging.debug(s"Log all caught up at offset $matchIndex for $from")
            }
          }
        } else {
          newState.sendDataToNode(from, response, peer.nextIndex, maxSendBatchSize)
        }

        Disk.commit(latestCommit).unless(latestCommit <= previousCommit) *> sendJob.as(newState)
    }
  }

  private def sendDataToNode(toNode: NodeId,
                             response: Response.AppendEntriesResponse,
                             fromOffset: Offset,
                             batchSize: Int) = {
    for {
      previous <- Disk.termFor(fromOffset.dec).orDie
      all <- Disk.readUncommitted(fromOffset, batchSize).orDie
      _ <- all match {
        case read if read.isEmpty => noop
        case entries =>
          val message = Request.AppendEntries(term, ourNodeId, previous, commitIndex, entries.map(_.entry))
          Cluster.sendRequest(toNode, message).retry(clusterRetrySchedule).orDie.as(this)
      }
      _ <- Heartbeat.scheduleHeartbeat(toNode)
    } yield this
  }

  def appendData(data: Array[Byte]) = {
    role match {
      case leader: Leader =>
        val index = lastApplied.inc()
        for {
          previous <- Disk.termFor(lastApplied).orDie
          updated <- Disk.append(term, index, data).orDie.as(copy(lastApplied = index))
          request = Request.AppendEntries(updated.term, ourNodeId, previous, commitIndex, Array(LogEntry(updated.term, data)))
          _ <- ZIO.foreachPar(leader.peersMatching(lastApplied)) { peer =>
            Cluster.sendRequest(peer, request).retry(clusterRetrySchedule).orDie
          }
        } yield updated
      case _ => ZIO.fail[RaftNodeError](NotTheLeader)
    }
  }

  def onHeartbeatTimeout(peerOpt: Option[NodeId]): ZIO[FullEnv, Nothing, RaftNodeState] = {
    (role, peerOpt) match {
      case (Role.Follower, None) => becomeCandidate()
      case (Role.Candidate(_, _, _), None) => becomeCandidate() // weird - oh well - let's try another election!
      case (leader: Role.Leader, Some(peer)) => sendHeartbeat(leader, peer) // just ignore - we're already the leader
      case (leader: Role.Leader, None) =>
        leader.broadcastLeaderHeartbeat(ourNodeId, term, clusterRetrySchedule).as(this)
      case _ => noop // just ignore - we're already the leader
    }
  }


  def sendHeartbeat(leader: Role.Leader, peerId: NodeId): ZIO[FullEnv, Nothing, RaftNodeState] = {
    leader.onHeartbeat(peerId).flatMap {
      case None =>
        leader.broadcastLeaderHeartbeat(ourNodeId, term, clusterRetrySchedule).as(this)
      case Some((newRole, newPeer)) =>
        for {
          previous <- Disk.termFor(newPeer.matchIndex).orDie
          hbRequest = Request.AppendEntries.heartbeat(term, ourNodeId, previous, commitIndex)
          _ <- Cluster.sendRequest(peerId, hbRequest).retry(clusterRetrySchedule).orDie.as(newRole)
        } yield copy(role = newRole)
    }
  }

  private val noop: UIO[RaftNodeState] = ZIO.succeed(this)

  def onRequestVote(from: NodeId, request: Request.RequestVote) = {
    if (request.term > term) {
      becomeFollower(request.term, None).flatMap(_.castVote(from, request))
    } else {
      castVote(from, request)
    }
  }

  private def castVote(from: NodeId, request: Request.RequestVote) = {
    val castVote = CastVoteLogic(from, term, request).flatMap { response =>
      val responseNodeIO = if (response.granted) {
        UIO(withRole(response.term, Role.Follower, None))
      } else {
        //
        // if this is a dynamic cluster and we've just had this request from a new node,
        // we need to then start sending heart-beats to this new node
        //
        role match {
          case leader@Leader(_) if !leader.clusterView.contains(from) =>
            Heartbeat.scheduleHeartbeat(from).as(copy(role = leader.addPeer(from)))
          case _ => noop
        }
      }
      for {
        _ <- Cluster.reply(from, Right(response))
        responseNode <- responseNodeIO
      } yield responseNode
    }
    castVote.orDie
  }

  def onRequestVoteResponse(from: NodeId, response: Response.RequestVoteResponse) = {
    if (response.term > term) {
      becomeFollower(response.term, None)
    } else {
      role match {
        case state: Candidate =>
          val updatedState = state.update(term, from, response)
          if (updatedState.canBecomeLeader) {
            becomeLeader(term, state)
          } else {
            ZIO.succeed(withRole(term, updatedState, None))
          }
        case _ =>
          // we're not longer (or never were) a candidate, so just ignore
          noop
      }
    }
  }


  private def becomeLeader(newTerm: Term, state: Role.Candidate) = {
    val newRole: Leader = Role.Leader(ourNodeId, state.peers)
    for {
      _ <- Disk.updateTerm(newTerm).orDie
      newState = withRole(newTerm, newRole, Option(ourNodeId))
      _ <- newRole.broadcastLeaderHeartbeat(ourNodeId, newTerm, clusterRetrySchedule)
      _ <- Logging.onRoleChange(this, newState)
    } yield newState
  }

  private def becomeCandidate() = {
    val newTerm = term + 1
    for {
      _ <- Heartbeat.scheduleFollowerHeartbeat
      _ <- Logging.onHeartbeatTimeout
      latest <- Disk.latestCommitted
      _ <- Cluster.broadcast(Request.RequestVote(newTerm, ourNodeId, latest)).retry(clusterRetrySchedule).orDie
      peers <- Cluster.peers()
      candidateState = Role.Candidate(ourNodeId, newTerm, peers)
      _ <- Disk.voteFor(newTerm, ourNodeId).orDie
      // we may be in a single-node cluster, so check if we can be the leader of our own destiny!
      minQuarumPossible = minClusterSize.fold(true)(_ <= peers.size)
      newNode <- if (minQuarumPossible && candidateState.canBecomeLeader) {
        for {
          newState <- becomeLeader(newTerm, candidateState)
        } yield newState
      } else {
        Disk.updateTerm(newTerm).as(withRole(newTerm, candidateState, None)).orDie
      }
      _ <- Logging.onRoleChange(this, newNode)
    } yield newNode
  }

  private def becomeFollower(newTerm: Term, leaderId: Option[NodeId]) = {
    val newNode = withRole(newTerm, Follower, leaderId)
    for {
      _ <- Disk.updateTerm(newTerm).orDie
      _ <- Logging.onRoleChange(this, newNode)
    } yield newNode
  }

  def withRole(newTerm: Term, newRole: Role, leaderId: Option[NodeId]): RaftNodeState = copy(term = newTerm, role = newRole, currentLeaderId = leaderId)

  def appendDataToLog(from: NodeId, request: Request.AppendEntries): ZIO[FullEnv, Nothing, RaftNodeState] = {
    val task = Disk.appendEntriesOnMatch(request.previous, request.entries).orDie.flatMap {
      case Some(latestCoords) =>
        val newState = copy(
          lastApplied = latestCoords.offset,
          commitIndex = request.leaderCommit.min(latestCoords.offset),
          currentLeaderId = Some(from))
        val response = Response.AppendEntriesResponse(term, true, newState.lastApplied)

        for {
          _ <- Cluster.sendResponse(from, response).retry(clusterRetrySchedule).orDie
          _ <- Disk.commit(newState.commitIndex).unless(commitIndex >= newState.commitIndex)
        } yield newState
      case None =>
        val response = Response.AppendEntriesResponse(term, false, lastApplied)
        Cluster.sendResponse(from, response).retry(clusterRetrySchedule).orDie.as(copy(currentLeaderId = Some(from)))
    }

    Heartbeat.scheduleFollowerHeartbeat *> task
  }

  override def toString: NodeId = {
    val label = role match {
      case _: Leader => "Leader"
      case _: Candidate => "Candidate"
      case Follower => "Follower"
    }
    s"$label($ourNodeId, term:$term, commitIndex:$commitIndex, lastApplied:$lastApplied, leaderIs: ${currentLeaderId.getOrElse("???")}, $role)"
  }
}