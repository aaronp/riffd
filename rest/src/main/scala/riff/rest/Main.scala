package riff.rest

import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import franz.rest.kafka.routes.ProducerOps
import franz.ui.routes.StaticFileRoutes
import org.http4s.HttpRoutes
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.Logger
import zio._
import zio.interop.catz._
import zio.interop.catz.implicits._

import scala.concurrent.ExecutionContext

object Main extends CatsApp {

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    val host = "0.0.0.0"
    val port = 8080

    val logHeaders = true
    val logBody = false

    import args4c.implicits._
    val config = args.toArray.asConfig()

    def mkRouter(restRoutes: HttpRoutes[Task]) = {
      val httpApp = org.http4s.server.Router[Task](
        "/rest" -> restRoutes,
        "/" -> StaticFileRoutes(config).routes[Task]()
      ).orNotFound
      if (logHeaders || logBody) {
        Logger.httpApp(logHeaders, logBody)(httpApp)
      } else httpApp
    }

    for {
      restRoutes <- RiffRoutes(config)
      httpRoutes = mkRouter(restRoutes)
      exitCode <- BlazeServerBuilder[Task](ExecutionContext.global)
        .bindHttp(port, host)
        .withHttpApp(httpRoutes)
        .serve
        .compile[Task, Task, cats.effect.ExitCode]
        .drain
        .fold(_ => ExitCode.failure, _ => ExitCode.success)
    } yield exitCode
  }
}
