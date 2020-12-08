package riff.test

import java.time.Instant

import riff._
import riff.test.TestLogging.State
import zio.clock.Clock
import zio.{Ref, UIO, ZIO}

case class TestLogging(clock: Clock.Service, state: Ref[State]) extends Logging.Service {
  def pretty = {
    state.get.map(_.toString)
  }

  override def onHeartbeatTimeout(): UIO[Unit] = for {
    now <- clock.instant
    _ <- state.update(_.addTimeout(now))
  } yield ()

  override def onRoleChange(from: RaftNodeState, to: RaftNodeState): UIO[Unit] = for {
    now <- clock.instant
    _ <- state.update(_.roleChange(now, from, to))
  } yield ()

  override def warn(message: String): UIO[Unit] = for {
    now <- clock.instant
    _ <- state.update(_.warn(now, message))
  } yield ()

  override def debug(message: String): UIO[Unit] = for {
    now <- clock.instant
    _ <- state.update(_.debug(now, message))
  } yield ()
}

object TestLogging {

  case class RoleChange(at: Instant, from: RaftNodeState, to: RaftNodeState)

  case class RequestResponse(at: Instant, input: Input, message: Either[RiffRequest, RiffResponse])

  case class State(timeouts: List[Instant], roleChanges: List[RoleChange], requestResp: List[RequestResponse], debugMsgs: List[(Instant, String)], warnMsgs: List[(Instant, String)]) {
    def addTimeout(now: Instant) = copy(timeouts = now :: timeouts)

    def roleChange(now: Instant, from: RaftNodeState, to: RaftNodeState) = copy(roleChanges = RoleChange(now, from, to) :: roleChanges)

    def requestResponse(now: Instant, input: Input, message: Either[RiffRequest, RiffResponse]) = copy(requestResp = RequestResponse(now, input, message) :: requestResp)

    def debug(now: Instant, message: String) = copy(debugMsgs = (now -> message) :: debugMsgs)

    def warn(now: Instant, message: String) = copy(warnMsgs = (now -> message) :: warnMsgs)

  }

  def apply(): ZIO[Clock, Nothing, TestLogging] = {
    val io: ZIO[Clock, Nothing, TestLogging] = for {
      clock <- ZIO.service[Clock.Service]
      ref <- Ref.make(State(Nil, Nil, Nil, Nil, Nil))
    } yield new TestLogging(clock, ref)

    io
  }
}
