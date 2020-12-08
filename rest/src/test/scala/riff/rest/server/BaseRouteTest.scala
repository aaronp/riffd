package riff.rest.server

import org.http4s._
import riff.rest.BaseRestTest
import zio.Task
import zio.interop.catz._

abstract class BaseRouteTest extends BaseRestTest {
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
