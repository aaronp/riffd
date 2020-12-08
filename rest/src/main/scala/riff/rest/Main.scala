package riff.rest

import args4c.implicits._
import eie.io._
import org.http4s.HttpRoutes
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.Logger
import riff.Raft
import riff.jvm.NioDisk
import zio._
import zio.interop.catz._
import zio.interop.catz.implicits._

import scala.concurrent.ExecutionContext

object Main extends CatsApp {

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {

    val config = args.toArray.asConfig().getConfig("riff")
    val host = config.getString("host")
    val port = config.getInt("port")
    val logHeaders = config.getBoolean("logHeaders")
    val logBody = config.getBoolean("logBody")

    def mkRouter(restRoutes: HttpRoutes[Task]) = {
      val httpApp = org.http4s.server.Router[Task](
        "/rest" -> restRoutes,
        "/" -> StaticFileRoutes(config).routes[Task]()
      ).orNotFound
      if (logHeaders || logBody) {
        Logger.httpApp(logHeaders, logBody)(httpApp)
      } else httpApp
    }

    val data = NioDisk(config.getString("dataDir").asPath)

    for {
      node <- Raft(data)
      restRoutes = RiffRoutes(node, environment)
      httpRoutes = mkRouter(restRoutes)
      _ <- node.run.fork
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
