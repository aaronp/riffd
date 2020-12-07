package riff.rest

import org.http4s
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import riff.Input.UserInput
import riff.json.RiffCodec._
import riff.{FullEnv, Raft, Request, Response}
import zio.interop.catz._
import zio.{Task, ZIO}

import scala.util.control.NonFatal

object RiffRoutes {

  type Resp = http4s.Response[Task]

  import taskDsl._

  def apply(node: Raft)(implicit runtime: EnvRuntime) = {
    onRequest { userIn =>
      val handle: ZIO[FullEnv, Throwable, http4s.Response[Task]] = node.applyInput(userIn) *> Ok(true)

      val handler = handle.catchAll {
        case NonFatal(err) =>
          Task(http4s.Response[Task](status = InternalServerError))
      }
      val x: ZIO[zio.ZEnv, Any, http4s.Response[Task]] = handler.provideCustomLayer(node.dependencies)
      x
    }
  }

  def onRequest2(handler: UserInput => ZIO[zio.ZEnv, Any, Resp]) = {
    HttpRoutes.of[Task] {
      case req@POST -> Root / "riff" / from =>
        val userInput: ZIO[Any, Throwable, UserInput] = req.as[Request].map(r => UserInput(from, r)).orElse(
          req.as[Response].map(r => UserInput(from, r))
        )

        userInput.flatMap(handler)
    }
  }
}
