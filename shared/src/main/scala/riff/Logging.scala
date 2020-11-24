package riff

import zio.console.Console
import zio.{IO, UIO, ZIO}

object Logging {

  trait Service {
    def onHeartbeatTimeout(): UIO[Unit] = UIO.unit

    def onRoleChange(from: RaftNodeState, to: RaftNodeState): UIO[Unit] = UIO.unit

    def onUpdate(input: Input, before: RaftNodeState, after: RaftNodeState): UIO[Unit] = UIO.unit

    def warn(message: String): UIO[Unit] = UIO.unit

    def debug(message: String): UIO[Unit] = UIO.unit

    def zipRight(other: Service) = Zip(this, other)
  }

  val onHeartbeatTimeout = ZIO.accessM[Logging](_.get.onHeartbeatTimeout())

  def onRoleChange(from: RaftNodeState, to: RaftNodeState): ZIO[Logging, Nothing, Unit] = ZIO.accessM[Logging](_.get.onRoleChange(from, to))

  def onUpdate(input: Input, before: RaftNodeState, after: RaftNodeState): ZIO[Logging, Nothing, Unit] = ZIO.accessM[Logging](_.get.onUpdate(input, before, after))

  def warn(message: String): ZIO[Logging, Nothing, Unit] = ZIO.accessM[Logging](_.get.warn(message))

  def debug(message: String): ZIO[Logging, Nothing, Unit] = ZIO.accessM[Logging](_.get.warn(message))

  object StdOut {
    def apply(): ZIO[Console, Nothing, Service] = {
      ZIO.environment[Console].map(c => StdOut(c))
    }
  }

  case class StdOut(console: zio.console.Console) extends Service {
    override def onHeartbeatTimeout() = info("on HB Timeout")

    override def onRoleChange(from: RaftNodeState, to: RaftNodeState) = info(s"onRoleChange($from, $to)")

    def info(message: String): UIO[Unit] = console.get.putStrLn(s"INFO: ${message}")

    override def warn(message: String): UIO[Unit] = console.get.putStrLn(s"WARN: ${message}")

    override def debug(message: String): UIO[Unit] = console.get.putStrLn(s"DEBUG: ${message}")

    override def onUpdate(input: Input, before: RaftNodeState, after: RaftNodeState): IO[Nothing, Unit] = info(s"onUpdate($input, $before, $after)")
  }

  case class Zip(left: Service, right: Service) extends Service {
    override def onHeartbeatTimeout() = left.onHeartbeatTimeout().zipRight(right.onHeartbeatTimeout())

    override def onRoleChange(from: RaftNodeState, to: RaftNodeState) = {
      left.onRoleChange(from, to).zipRight(right.onRoleChange(from, to))
    }

    override def warn(message: String): UIO[Unit] = left.warn(message).zipRight(right.warn(message))

    override def debug(message: String): UIO[Unit] = left.debug(message).zipRight(right.debug(message))

    override def onUpdate(input: Input, before: RaftNodeState, after: RaftNodeState) = {
      left.onUpdate(input, before, after).zipRight(right.onUpdate(input, before, after))
    }
  }


  def withRoleChange(onRoleChangeHandler: (RaftNodeState, RaftNodeState) => Unit): Service = new Service {
    override def onRoleChange(from: RaftNodeState, to: RaftNodeState): UIO[Unit] = UIO.effectTotal(onRoleChangeHandler(from, to))
  }

  def withUpdate(onUpdateHandler: (Input, RaftNodeState, RaftNodeState) => Unit): Service = new Service {
    override def onUpdate(input: Input, before: RaftNodeState, after: RaftNodeState): UIO[Unit] = UIO.effectTotal(onUpdateHandler(input, before, after))
  }

  def withTimeoutHandler(hbTimeout: => Unit) = new Service {
    override def onHeartbeatTimeout(): UIO[Unit] = UIO.effectTotal(hbTimeout)
  }

}
