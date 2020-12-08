package riff.rest.server

import com.typesafe.config.Config
import riff._
import zio.{IO, UIO}

class HttpCluster() extends Cluster.Service {
  override def peers(): UIO[Set[NodeId]] = ???

  override def broadcast(message: Request): IO[ClusterError, Unit] = ???

  override def reply(to: NodeId, message: Either[Request, Response]): IO[ClusterError, Unit] = ???
}

object HttpCluster {
  def apply(rootConfig: Config) = {
    ???
  }
}
