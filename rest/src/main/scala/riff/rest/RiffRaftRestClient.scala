package riff.rest

import cats.effect._
import com.typesafe.config.Config
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
import riff.{NodeId, RiffRequest, RiffResponse}
import zio.Task
import zio.interop.catz._

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

/**
 * see https://http4s.org/v0.20/client/
 */
trait RiffRaftRestClient {
  def send(either: Either[RiffRequest, RiffResponse]): Task[Boolean]
}

object RiffRaftRestClient {

  case class Instance(hostPort: String, ourNodeId: NodeId, httpClient: Client[Task]) extends Http4sClientDsl[Task] with RiffRaftRestClient {
    private def post(destination: Uri, jason: Json): Task[Boolean] = httpClient.expect(POST(jason, destination))(jsonOf[Task, Boolean])

    def send(destination: Uri, request: RiffRequest): Task[Boolean] = post(destination, request.asJson)

    def send(destination: Uri, response: RiffResponse): Task[Boolean] = post(destination, response.asJson)

    final def send(destination: Uri, either: Either[RiffRequest, RiffResponse]): Task[Boolean] = either match {
      case Left(request) => send(destination, request)
      case Right(response) => send(destination, response)
    }

    override def send(either: Either[RiffRequest, RiffResponse]): Task[Boolean] = {
      val uriString = s"${hostPort}/riff/${ourNodeId}"
//      send(uri"$uriString", either)
      ???
    }
  }

  def apply(rootConfig : Config) = {
    val cluster = rootConfig.getConfig("riff.cluster")
    import args4c.implicits._
    cluster.entries().map {
      case (name, cfg) =>
        val nested =  cfg.atKey(name)
        nested.getString("hostPort")
    }
  }

  def apply(hostPort: String, ourNodeId: NodeId, request: RiffRequest, ec: ExecutionContext = global)(implicit ce: ConcurrentEffect[Task]) = {
    //      val destination = http://localhost:8080
    BlazeClientBuilder[Task](ec).resource.use { client: Client[Task] =>
      Instance(hostPort, ourNodeId, client).send(Left(request))
    }
  }
}