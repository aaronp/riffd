package riff.rest.server

import cats.data.{Kleisli, OptionT}
import org.http4s.{HttpRoutes, Request, Response}
import zio.Task
import cats.syntax.option._

object ErrorHandler {
  def apply(routes: HttpRoutes[Task])(handler: (Request[Task], Throwable) => Task[Response[Task]]): HttpRoutes[Task] =
    Kleisli { req: Request[Task] =>
      OptionT {
        val task: Task[Option[Response[Task]]] = routes.run(req).value
        task.catchAll(err => handler(req, err).map(_.some))
      }
    }
}
