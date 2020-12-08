package riff

import zio.{UIO, ZIO}

object CastVoteLogic {
  def apply(requestingNodeId: NodeId,
            currentTerm: Term,
            forRequest: RiffRequest.RequestVote): ZIO[Disk, DiskError, RiffResponse.RequestVoteResponse] = {
    def logStateOk(ourLogState: LogCoords) = {
      forRequest.latestLog.term >= ourLogState.term &&
        forRequest.latestLog.offset >= ourLogState.offset
    }

    Disk.votedFor(currentTerm).flatMap {
      case Some(whoWeVotedFor) =>
        ZIO.succeed(RiffResponse.RequestVoteResponse(forRequest.term, whoWeVotedFor == requestingNodeId))
      case None if forRequest.term < currentTerm =>
        UIO.succeed(RiffResponse.RequestVoteResponse(currentTerm, false))
      case None =>
        Disk.latestCommitted.map(logStateOk).flatMap { shouldVote =>
          if (shouldVote) {
            Disk.voteFor(currentTerm, requestingNodeId).as(RiffResponse.RequestVoteResponse(forRequest.term, true))
          } else {
            // we reply with OUR term...
            UIO.succeed(RiffResponse.RequestVoteResponse(currentTerm, false))
          }
        }
    }
  }
}