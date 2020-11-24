package riff.js.ui

import zio._
import zio.console.putStrLn
import zio.duration.{Duration, durationInt}

import scala.concurrent.ExecutionContext.Implicits.global

object JSRuntime extends App {
  def evalTimeout: Duration = 5.seconds
  object implicits {
    implicit def asRichZIO[A](job: ZIO[_root_.zio.ZEnv, Any, A]) = new {
      def value() = unsafeRun(job.either).getOrElse(sys.error("oops"))

      def future() = unsafeRunToFuture(job.either).map {
        case Left(err) => sys.error(s"Got error $err")
        case Right(v) => v
      }
    }
  }
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = ZIO.unit.exitCode
}
