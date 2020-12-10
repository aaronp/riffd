package riff.rest

import cats.effect.ConcurrentEffect
import com.typesafe.config.{Config, ConfigFactory}
import eie.io._
import org.http4s.HttpRoutes
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.Logger
import riff.jvm.NioDisk
import riff.rest.RiffRaftRest.Settings
import riff.rest.server.{RiffRaftRestRoutes, StaticFileRoutes}
import riff.{DiskMap, Raft}
import zio.interop.catz._
import zio.interop.catz.implicits._
import zio._

import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext

/**
 * The wrappings of a REST service around a [[Raft]] node
 *
 * @param settings
 * @param node
 */
case class RiffRaftRest(settings: Settings, node: Raft) {

  import settings._

  private def mkRouter(restRoutes: HttpRoutes[Task]) = {
    val httpApp = org.http4s.server.Router[Task](
      "/rest" -> restRoutes,
      "/" -> StaticFileRoutes(riffConfig).routes[Task]()
    ).orNotFound
    if (logHeaders || logBody) {
      Logger.httpApp(logHeaders, logBody)(httpApp)
    } else httpApp
  }

  @implicitNotFound("You need ConcurrentEffect, which (if you're calling w/ a ZIO runtime in scope), can be fixed by: import zio.interop.catz._")
  def serve(implicit ce: ConcurrentEffect[Task]): ZIO[zio.ZEnv, Nothing, ExitCode] = {
    for {
      env <- ZIO.environment[ZEnv]
      restRoutes = RiffRaftRestRoutes(node, env)
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

object RiffRaftRest {

  def apply(rootConfig: Config = ConfigFactory.load()): ZIO[zio.ZEnv, Nothing, RiffRaftRest] = apply(Settings(rootConfig.getConfig("riff")))

  def apply(settings: Settings): ZIO[zio.ZEnv, Nothing, RiffRaftRest] = {
    val diskIO = settings.dataDir match {
      case "" => DiskMap("in-memory")
      case path => UIO(NioDisk(path.asPath))
    }

    for {
      disk <- diskIO
      raft <- Raft(disk)
    } yield new RiffRaftRest(settings, raft)
  }

  case class Settings(riffConfig: Config, host: String, port: Int, logHeaders: Boolean, logBody: Boolean, dataDir: String)

  object Settings {
    def apply(config: Config): Settings = {
      Settings(
        config,
        host = config.getString("host"),
        port = config.getInt("port"),
        logHeaders = config.getBoolean("logHeaders"),
        logBody = config.getBoolean("logBody"),
        dataDir = config.getString("data")
      )
    }

    def fromRootConfig(rootConfig: Config = ConfigFactory.load()): Settings = Settings(rootConfig.getConfig("riff"))
  }

}
