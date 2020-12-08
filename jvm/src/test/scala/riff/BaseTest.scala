package riff

import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import zio._
import zio.duration.{Duration, durationInt}

import java.nio.file.{Files, Path, Paths}
import java.util.UUID


abstract class BaseTest extends AnyWordSpec with Matchers with GivenWhenThen with Eventually with ScalaFutures {

  implicit val rt: zio.Runtime[zio.ZEnv] = zio.Runtime.default

  def zenv = rt.environment

  def testTimeout: Duration = 3.seconds

  def shortTimeoutJava = 200.millis

  implicit def asRichZIO[A](zio: => ZIO[_root_.zio.ZEnv, Any, A])(implicit rt: _root_.zio.Runtime[_root_.zio.ZEnv]) = new {
    def value(): A = rt.unsafeRun(zio.timeout(testTimeout)).getOrElse(sys.error("Test timeout"))
  }

  def withTmpDir[A](f: java.nio.file.Path => A) = {
    val dir = Paths.get(s"./target/${getClass.getSimpleName}-${UUID.randomUUID()}")
    try {
      Files.createDirectories(dir)
      f(dir)
    } finally {
      delete(dir)
    }
  }

  def delete(dir: Path): Unit = {
    if (Files.isDirectory(dir)) {
      Files.list(dir).forEach(delete)
    }
    Files.delete(dir)
  }

  implicit override def patienceConfig = PatienceConfig(timeout = scaled(Span(testTimeout.toSeconds, Seconds)), interval = scaled(Span(150, Millis)))

}
