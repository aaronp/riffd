package riff

import zio.{Schedule, ZIO}
import zio.clock.Clock
import zio.duration.Duration

/**
 * The Role holds onto the  current in-memory state.
 *
 * steps:
 * - start as follower and schedule a random timeout
 * - handle an incoming RequestVote message and enqueue a VoteResponse
 *
 */
sealed class Role(val name: String) {
  def isLeader: Boolean = false
}

object Role {

  final case object Follower extends Role("Follower")

  final case class Candidate(votesFor: Set[NodeId], votesAgainst: Set[NodeId], peers: Set[String]) extends Role("Candidate") {
    override def toString: NodeId = s"{ ${votesFor.mkString("for:[", ",", "]")}, ${votesAgainst.mkString("against:[", ",", "]")}, ${peers.mkString("peers:[", ",", "]")} }"

    def clusterSize = peers.size

    def canBecomeLeader = isMajority(votesFor.size, clusterSize)

    def update(term: Term, from: NodeId, reply: Response.RequestVoteResponse): Candidate = {
      if (reply.term == term) {
        if (reply.granted) {
          copy(votesFor = votesFor + from, peers = peers + from)
        } else {
          copy(votesAgainst = votesAgainst + from, peers = peers + from)
        }
      } else {
        this
      }
    }
  }

  object Candidate {
    def apply(id: NodeId, term: Term, peers: Set[NodeId]): Candidate = {
      val fullCluster = peers + id
      new Candidate(Set(id), Set.empty, fullCluster)
    }
  }

  final case class Leader(clusterView: Map[NodeId, ClusterPeer]) extends Role("Leader") {
    def addPeer(from: NodeId) = copy(clusterView = clusterView.updated(from, ClusterPeer(from)))

    def broadcastLeaderHeartbeat(ourNodeId: NodeId, term: Term, clusterRetrySchedule: Schedule[Any, Any, Duration]) = {
      for {
        latest <- Disk.latestCommitted
        _ <- ZIO.foreachPar(clusterView.toList) {
          case (_, peer) =>
            for {
              previous <- Disk.termFor(peer.matchIndex).orDie
              heartbeat = Request.AppendEntries.heartbeat(
                term,
                ourNodeId,
                previous,
                latest.offset)
              _ <- Cluster.sendRequest(peer.id, heartbeat).retry(clusterRetrySchedule).orDie
            } yield ()
        }
      } yield ()
    }

    override def toString: NodeId = {
      clusterView.map {
        case (id, peer) => s"$id : $peer"
      }.mkString(s"${clusterView.size} peers:\n\t", "\n\t", "\n")
    }

    def peersMatching(index: Offset): Set[NodeId] = {
      clusterView.collect {
        case (id, peer) if peer.matchIndex == index => id
      }.toSet
    }

    override def isLeader = true

    def latestCommittedPeerOffset: Option[Offset] = {
      Option(latestCommittedInt).filterNot(_ == Int.MaxValue).map(offset => Offset(offset))
    }

    /**
     * the max offset held by a quarum of peers?
     *
     * e.g.:
     * given 3 nodes w/ match offsets 5, 7 and 9, then we can commit up to offset 7.
     * given 3 nodes w/ match offsets 7, 7 and 9, then we can commit up to offset 7.
     * given 2 nodes w/ match offsets 5, 7 then we can commit up to offset 5
     *
     * Then we should be able
     *
     * @return
     */
    def latestQuarum: Int = {
      val matchOffsets = clusterView.foldLeft(List.empty[Int]) {
        case (set, (_, next)) => next.matchIndex.offset +: set
      }.toSet
      quarum(matchOffsets)
    }

    private lazy val latestCommittedInt = clusterView.foldLeft(Int.MaxValue) {
      case (offset, (_, peer)) => offset.min(peer.matchIndex.offset)
    }

    def update(peerId: NodeId, success: Boolean, matchIndex: Offset) = {
      zio.clock.instant.map { now =>
        val newPeer: ClusterPeer = clusterView.get(peerId) match {
          case Some(old) => old.update(now, success, matchIndex)
          case None => ClusterPeer(peerId).update(now, success, matchIndex)
        }
        copy(clusterView = clusterView.updated(peerId, newPeer)) -> newPeer
      }
    }

    def onHeartbeat(peerId: NodeId): ZIO[Clock, Nothing, Option[(Leader, ClusterPeer)]] = {
      zio.clock.instant.map { now =>
        clusterView.get(peerId) match {
          case Some(old) =>
            val newPeer = old.onHeartbeat(now)
            val updated = copy(clusterView = clusterView.updated(peerId, newPeer))
            Some(updated -> newPeer)
          case None =>
            val newPeer = ClusterPeer(peerId)
            val updated = copy(clusterView = clusterView.updated(peerId, newPeer))
            Some(updated -> newPeer)
        }
      }
    }
  }

  object Leader {
    def apply(ourId: NodeId, peers: Set[NodeId]): Leader = {
      val cluster = peers + ourId
      new Leader((cluster - ourId).map(id => id -> ClusterPeer(id)).toMap)
    }
  }

  private def isMajority(numberReceived: Int, clusterSize: Int): Boolean = numberReceived > clusterSize / 2

  // we assume the leader is not included and already has
  def quarum(peerValues: Set[Int]): Int = {
    val needed = peerValues.size + 1 >> 1
    peerValues.foldLeft(0) {
      case (max, x) if peerValues.count(_ >= x) >= needed => max.max(x)
      case (max, _) => max
    }
  }
}