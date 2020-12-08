package riff.rest.server

import com.typesafe.scalalogging.StrictLogging

import java.net.URL
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{FileAlreadyExistsException, Files, Path, Paths}
import java.util.zip.ZipInputStream

/**
 * Provides a means to extract the web artifacts (css, html files, etc) from our Jar to some specified directory
 */
object ExtractJar extends StrictLogging {

  def extractResourcesFromJar(toDir: Path): Path = {
    logger.info(s"Extracting jar contents to ${toDir.toAbsolutePath}")

    val scriptWeCanAssumeIsThere = "web/css/knownFixedWebFile.txt"
    val JarPath1 = ("jar:file:(.*)!/" + scriptWeCanAssumeIsThere).r
    val JarPath2 = "file:(.*\\.jar)".r

    val url: URL = {
      val scriptUrl = getClass.getClassLoader.getResource(scriptWeCanAssumeIsThere)
      if (scriptUrl == null) {
        val source = getClass.getProtectionDomain.getCodeSource
        source.getLocation
      } else {
        scriptUrl
      }
    }
    require(url != null, s"Couldn't find $scriptWeCanAssumeIsThere on the classpath")
    url.toString match {
      case JarPath1(pathToJar) => apply(Paths.get(pathToJar), toDir)
      case JarPath2(pathToJar) => apply(Paths.get(pathToJar), toDir)
      case other =>
        logger.info(s"$scriptWeCanAssumeIsThere was found under $other")
    }
    toDir
  }

  /**
   * Our text resources (html, css, js) get jarred up together.
   *
   * When running we should be able to extract them so the [[StaticFileRoutes]] can serve them from local disk.
   *
   * @param fromFile the path to the jar artifact
   * @param toDir    the local destination directory
   * @return the extracted location (same as the 'toDir')
   */
  def apply(fromFile: Path, toDir: Path): Path = {

    def mkDirs(path: Path) = {
      if (!Files.isDirectory(path)) {
        Files.createDirectories(path)
      }
    }

    mkDirs(toDir)

    val jarDest = toDir.resolve(fromFile.getFileName.toString)
    try {
      Files.copy(fromFile, toDir.resolve(fromFile.getFileName.toString))
    } catch {
      case _: FileAlreadyExistsException =>
    }

    using(new ZipInputStream(Files.newInputStream(jarDest))) { is: ZipInputStream =>
      var entry = is.getNextEntry
      while (entry != null) {
        if (entry.getName.contains("web") && !entry.isDirectory && !entry.getName.endsWith(".class")) {
          val target = toDir.resolve(entry.getName)
          if (!Files.exists(target)) {
            mkDirs(target.getParent)
            Files.copy(is, target)

            // our noddy scripts are ok for 777
            import scala.jdk.CollectionConverters._
            Files.setPosixFilePermissions(target, PosixFilePermission.values().toSet.asJava)
          }
        }
        is.closeEntry
        entry = is.getNextEntry
      }
    }

    import eie.io._
    logger.info(s"Exacted jar to ${toDir.toAbsolutePath}:\n${toDir.renderTree()}")

    toDir
  }

  private object using {
    def apply[A <: AutoCloseable, T](resource: A)(thunk: A => T): T = {
      try {
        thunk(resource)
      } finally {
        resource.close()
      }
    }
  }

}
