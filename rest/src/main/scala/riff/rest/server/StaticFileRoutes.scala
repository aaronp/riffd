package riff.rest.server

import cats.data.OptionT
import cats.effect.{Sync, _}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import eie.io._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Request, Response, StaticFile}

import java.nio.file.{Paths, Path => JPath}
import scala.concurrent.ExecutionContext

/**
 * TODO:
 *
 * $ consider zipping the static resources on disk, and so just serve them up already zipped to client
 * $ ...otherwise zip the results and set the expiry time
 *
 * @param htmlRootDirs the directory to otherwise serve up html resources
 * @param landingPage  the redirect landing page (e.g. index.html)
 * @param jsRootDirs   the directory which will serve the /js artifacts
 * @param cssRootDirs  the directory which will serve the /css artifacts
 */
case class StaticFileRoutes(htmlRootDirs: Seq[JPath],
                            landingPage: String,
                            jsRootDirs: Seq[JPath],
                            cssRootDirs: Seq[JPath],
                            resourceMap: Map[String, Option[String]]) extends StrictLogging {

  htmlRootDirs.foreach { htmlRootDir =>
    require(htmlRootDir.exists(), s"htmlRootDir '$htmlRootDir' doesn't exist (working dir is ${(Paths.get(".").toAbsolutePath)})")
  }

  jsRootDirs.foreach { jsRootDir =>
    def baseErr = s"jsRootDir '$jsRootDir' doesn't exist"

    def error = if (jsRootDir.toString.contains("js/target/scala-")) {
      s"""$baseErr: You have to compile clientCrossJS first (e.g. sbt "project clientCrossJS" fastOptJS)"""
    } else baseErr

    require(jsRootDir.exists(), error)
  }
  cssRootDirs.foreach { cssRootDir =>
    require(cssRootDir.exists(), s"cssRootDir '$cssRootDir' doesn't exist")
  }

  def routes[F[_] : Sync : ContextShift](blocker: Blocker = StaticFileRoutes.staticBlocker): HttpRoutes[F] = {
    val builder = new Builder[F](blocker)
    builder.routes
  }

  private class Builder[F[_] : Sync : ContextShift](blocker: Blocker) {
    private val dsl = Http4sDsl[F]

    import dsl._

    def routes = {
      HttpRoutes.of[F] {
        case request@GET -> Root / "js" / path => getJS(path, request)
        case request@GET -> Root / "css" / path => getCSS(path, request)
        case request@GET -> Root / "assets" / "packages" / "cupertino_icons" / "assets" / path => getHTML(s"assets/packages/cupertino_icons/assets/$path", request)
        case request@GET -> Root / "assets" / "fonts" / path => getHTML(s"assets/fonts/$path", request)
        case request@GET -> Root / "assets" / path => getHTML(s"assets/$path", request)
        case request@GET -> Root / "icons" / path => getHTML(s"icons/$path", request)
        case request@GET -> Root => getHTML(landingPage, request)
        case request@GET -> Root / path => getHTML(path, request)
      }
    }

    private def getCSS(unmatchedPath: String, request: Request[F]): F[Response[F]] = {
      resolvePaths(cssRootDirs, unmatchedPath, request).getOrElseF(NotFound())
    }

    private def getHTML(unmatchedPath: String, request: Request[F]): F[Response[F]] = {
      resolvePaths(htmlRootDirs, unmatchedPath, request).getOrElseF(NotFound())
    }

    private def getJS(unmatchedPath: String, request: Request[F]): F[Response[F]] = {
      val key: String = unmatchedPath.toString
      logger.trace(s"Serving '$key' under JS dirs ${jsRootDirs}")
      val opt: OptionT[F, Response[F]] = resourceMap.get(key) match {
        case Some(Some(differentName)) =>
          logger.trace(s"Mapping $unmatchedPath to '${differentName}' under JS dir ${jsRootDirs}")
          resolvePaths(jsRootDirs, differentName, request)
        case Some(None) =>
          logger.trace(s"Rejecting $key")
          OptionT.none[F, Response[F]]
        case None =>
          resolvePaths(jsRootDirs, unmatchedPath, request)
      }

      opt.getOrElseF(NotFound())
    }

    private def resolvePaths(paths: Seq[JPath], resource: String, request: Request[F]) = {
      val opts = paths.map { path =>
        resolvePath(path, resource, request)
      }
      opts.reduce(_ orElse _)
    }

    private def resolvePath(path: JPath, resource: String, request: Request[F]): OptionT[F, Response[F]] = {
      val resolved = path.resolve(resource)
      logger.trace(s"Fetching '$resolved'")
      StaticFile.fromFile(resolved.toFile, blocker, Some(request))
    }
  }

}

object StaticFileRoutes {

  private lazy val staticBlocker: Blocker = Blocker.liftExecutionContext(ExecutionContext.Implicits.global)


  /** @param rootConfig the top-level config, e.g. the result of calling 'ConfigFactory.load()'
   * @return the StaticFileRoutes
   */
  def apply(rootConfig: Config): StaticFileRoutes = {
    fromWWWConfig(rootConfig.getConfig("www"))
  }

  /**
   * @param wwwConfig the relative config which contains the static file route entries
   * @return the StaticFileRoutes
   */
  def fromWWWConfig(wwwConfig: Config): StaticFileRoutes = {
    import args4c.implicits._

    val resourceMapping: Map[String, Option[String]] = wwwConfig.getConfig("resourceMapping").collectAsMap().map {
      case (key, value) =>
        args4c.unquote(key) -> Option(args4c.unquote(value.trim)).filterNot(_.isEmpty)
    }

    wwwConfig.getString("extractTo") match {
      case "" =>
      case dir => ExtractJar.extractResourcesFromJar(dir.asPath)
    }

    def dirs(key: String) = wwwConfig.asList(key).map(_.trim).map(p => Paths.get(p))

    new StaticFileRoutes(
      htmlRootDirs = dirs("htmlDir"),
      landingPage = wwwConfig.getString("landingPage"),
      jsRootDirs = dirs("jsDir"),
      cssRootDirs = dirs("cssDir"),
      resourceMap = resourceMapping
    )
  }
}
