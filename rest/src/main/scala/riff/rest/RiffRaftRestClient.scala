package riff.rest

import cats.effect._
import io.circe.Json
import io.circe.syntax._
import org.http4s.Uri
import org.http4s.circe.{jsonEncoder, jsonOf}
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.io.POST
import org.http4s.implicits.http4sLiteralsSyntax
import riff.json.RiffCodec._
import riff.{RiffRequest, RiffResponse}
import zio.Task
import zio.interop.catz._

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

/**
 * see https://http4s.org/v0.20/client/
 */
trait RiffRaftRestClient {
  def send(destination: Uri, either: Either[RiffRequest, RiffResponse]): Task[Boolean]
}

object RiffRaftRestClient {
  val default = uri"http://localhost:8080/hello/"

  case class Instance(httpClient: Client[Task]) extends Http4sClientDsl[Task] with RiffRaftRestClient {
    private def post(destination: Uri, jason: Json): Task[Boolean] = httpClient.expect(POST(jason, destination))(jsonOf[Task, Boolean])

    def send(destination: Uri, request: RiffRequest): Task[Boolean] = post(destination, request.asJson)

    def send(destination: Uri, response: RiffResponse): Task[Boolean] = post(destination, response.asJson)

    final def send(destination: Uri, either: Either[RiffRequest, RiffResponse]): Task[Boolean] = either match {
      case Left(request) => send(destination, request)
      case Right(response) => send(destination, response)
    }
  }


  def apply(destination: Uri, request: RiffRequest, ec: ExecutionContext = global)(implicit ce: ConcurrentEffect[Task]) = {
    //    implicit val cs: ContextShift[Task] = IO.contextShift(ec)
    //    implicit val timer: Timer[Task] = IO.timer(ec)
    BlazeClientBuilder[Task](ec).resource.use { client: Client[Task] =>
      Instance(client).send(destination, request)
    }
  }
}