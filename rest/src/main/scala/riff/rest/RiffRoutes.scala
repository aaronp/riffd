package riff.rest

import io.circe.Json
import io.circe.syntax._
import org.http4s
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import riff.Input.UserInput
import riff.json.RiffCodec._
import riff.{FullEnv, Raft, Request, Response}
import zio.interop.catz._
import zio.{Task, UIO, ZEnv, ZIO}

import scala.util.control.NonFatal

object RiffRoutes {

  type Resp = http4s.Response[Task]

  import taskDsl._

  def apply(node: Raft, env: ZEnv): HttpRoutes[Task] = {
    apply { userIn =>
      val handle: ZIO[FullEnv, Throwable, http4s.Response[Task]] = node.applyInput(userIn) *> Ok(true)

      handle.catchAll {
        case NonFatal(err) =>
          Task(http4s.Response[Task](status = InternalServerError).withEntity(Json.obj("error" -> err.getMessage.asJson)))
      }
        .provideCustomLayer(node.dependencies)
        .provide(env).mapError { bug =>
        sys.error(s"BUG: $bug")
      }
    }
  }

  def apply(handler: UserInput => UIO[Resp]) = {
    HttpRoutes.of[Task] {
      case req@POST -> Root / "riff" / from =>
        val userInput: ZIO[Any, Throwable, UserInput] = req.as[Request].map(r => UserInput(from, r)).orElse(
          req.as[Response].map(r => UserInput(from, r))
        )
        userInput.flatMap(handler)
    }
  }
}
