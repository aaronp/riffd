package riff.test

import riff.test.TestHeartbeat.Reset
import riff.{Heartbeat, NodeId}
import zio.clock.Clock
import zio.console.Console
import zio.random.Random
import zio.{IO, Ref, ZIO}

class TestHeartbeat(scheduled: Ref[List[Reset]]) extends Heartbeat.Service {
  override def scheduleHeartbeat(key: Option[NodeId]): ZIO[Console with Clock with Random, Nothing, Unit] = {
    for {
      now <- zio.clock.instant
      _ <- scheduled.update(Reset(now, key) :: _)
    } yield ()
  }

  def pretty = scheduled.get.map { list =>
    list.mkString(s"${list.size} scheduled heartbeats:\n\t", "\n\t", "")
  }

  def clear(): IO[Nothing, Unit] = scheduled.set(Nil)
}

object TestHeartbeat {

  final case class Reset(at: java.time.Instant, key: Option[String])

  def apply() = {
    Ref.make(List[Reset]()).map(list => new TestHeartbeat(list))
  }
}
