package riff.rest.server

import io.circe.Json
import io.circe.syntax._
import org.http4s
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import riff.Input.UserInput
import riff.json.RiffCodec._
import riff.{Raft, RiffRequest, RiffResponse}
import zio.interop.catz._
import zio.{Task, UIO, ZEnv, ZIO}

import scala.util.control.NonFatal


/**
 * You've got to have a little fun.
 */
object RiffRaftRestRoutes {

  type Resp = http4s.Response[Task]

  val taskDsl: Http4sDsl[Task] = Http4sDsl[Task]

  import taskDsl._

  def apply(node: Raft, env: ZEnv): HttpRoutes[Task] = {
    apply { userIn =>
      val handle = (node.applyInput(userIn) *> Ok(true)).catchAll {
        case NonFatal(err) => Task(http4s.Response[Task](status = InternalServerError).withEntity(expAsJson(err)))
      }

      handle
        .provideCustomLayer(node.dependencies)
        .provide(env).mapError { bug => sys.error(s"BUG: $bug") }
    }
  }

  private def expAsJson(err: Throwable) = Json.obj("error" -> err.getMessage.asJson)

  def apply(handler: UserInput => UIO[Resp]) = {
    HttpRoutes.of[Task] {
      case req@POST -> Root / "riff" / from =>
        val userInput: ZIO[Any, Throwable, UserInput] = req.as[RiffRequest].map(r => UserInput(from, r)).orElse(
          req.as[RiffResponse].map(r => UserInput(from, r))
        )
        userInput.flatMap(handler)
    }
  }
}
