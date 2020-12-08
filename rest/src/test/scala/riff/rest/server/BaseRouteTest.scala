package riff.rest.server

import io.circe.Decoder
import org.http4s.{EntityDecoder, HttpRoutes, Method, Request, Response, Uri}
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import zio.duration.{Duration, durationInt}
import zio.interop.catz._
import zio.{CancelableFuture, Task}

import scala.util.Try

abstract class BaseRouteTest extends AnyWordSpec with Matchers with GivenWhenThen with Eventually with ScalaFutures {

  implicit val rt: zio.Runtime[zio.ZEnv] = zio.Runtime.default

  def zenv = rt.environment

  def testTimeout: Duration = 15.seconds

  def testTimeoutJava = java.time.Duration.ofMillis(testTimeout.toMillis)

  def shortTimeoutJava = java.time.Duration.ofMillis(200)

  implicit override def patienceConfig = PatienceConfig(timeout = scaled(Span(testTimeout.toSeconds.toInt, Seconds)), interval = scaled(Span(150, Millis)))


  implicit def asRichZIO[A](zio: => Task[A])(implicit rt: _root_.zio.Runtime[_root_.zio.ZEnv]) = new {
    def value(): A = rt.unsafeRun(zio)
  }

  implicit def forResponse(response: Response[Task])(implicit rt: zio.Runtime[zio.ZEnv]) = new {

    def bodyTask: Task[String] = EntityDecoder.decodeText(response)

    def bodyAsString: String = bodyTask.value()
  }

  implicit class RichRoute(route: HttpRoutes[Task]) {
    def responseFor(request: Request[Task]): Response[Task] = responseForOpt(request).getOrElse(sys.error("no response"))

    def responseForOpt(request: Request[Task]): Option[Response[Task]] = {
      route(request).value.value()
    }
  }


  def get(url: String, queryParams: (String, String)*): Request[Task] = {
    val uri: Uri = asUri(url, queryParams: _*)
    Request[Task](method = Method.GET, uri = uri)
  }

  def post(url: String, body: String, queryParams: (String, String)*): Request[Task] = {
    val uri: Uri = asUri(url, queryParams: _*)
    Request[Task](method = Method.POST, uri = uri).withEntity(body)
  }

  private def asUri(url: String, queryParams: (String, String)*) = {
    val encoded = Uri.encode(url)
    val uri = if (queryParams.isEmpty) {
      Uri.unsafeFromString(encoded)
    } else {
      Uri.unsafeFromString(encoded).withQueryParams(queryParams.toMap)
    }
    uri
  }
}
