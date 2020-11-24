package riff

import zio.{IO, Ref, UIO, ZIO}


/**
 * The cluster configuration
 */
object Cluster {

  trait Service {
    def peers(): UIO[Set[NodeId]]

    def broadcast(message: Request): IO[ClusterError, Unit]

    def reply(to: NodeId, message: Either[Request, Response]): IO[ClusterError, Unit]
  }

  def broadcast(message: Request) = {
    val resetHB = message match {
      case _: Request.AppendEntries =>
        peers().flatMap { ids =>
          ZIO.foreach(ids)(Heartbeat.scheduleHeartbeat)
        }
      case _ => ZIO.unit
    }
    ZIO.accessM[Cluster](_.get.broadcast(message)) <* resetHB
  }

  def peers(): ZIO[Cluster, Nothing, Set[NodeId]] = ZIO.accessM[Cluster](_.get.peers())

  def reply(to: NodeId, message: Either[Request, Response]) = ZIO.accessM[Cluster](_.get.reply(to, message))

  def sendRequest(to: NodeId, request: Request) = {
    for {
      _ <- reply(to, Left(request))
      _ <- Heartbeat.scheduleHeartbeat(to)
    } yield ()
  }

  def sendResponse(to: NodeId, response: Response) = reply(to, Right(response))

  def apply(): ZIO[Any, Nothing, Buffer] = {
    Ref.make(apply(Map.empty)).map { ref =>
      Buffer(ref)
    }
  }

  final case class Buffer(ref: Ref[Service]) extends Service {
    def update(newSvc: Service): ZIO[Any, Nothing, Buffer] = ref.set(newSvc).as(this)

    override def broadcast(message: Request): IO[ClusterError, Unit] = ref.get.flatMap(_.broadcast(message))

    override def reply(to: NodeId, message: Either[Request, Response]): IO[ClusterError, Unit] = {
      ref.get.flatMap(_.reply(to, message))
    }

    override def peers() = ref.get.flatMap(_.peers())
  }

  def apply(peersById: Map[NodeId, RemoteClient]): Service = Fixed(peersById)

  final case class Fixed(peersById: Map[NodeId, RemoteClient]) extends Service {
    override def broadcast(message: Request) = {
      peersById.size match {
        case 0 => ZIO.unit
        case 1 =>
          val (name, peer) = peersById.head
          sendToPeer(name, message, peer).unit
        case _ =>
          val sent = IO.foreachPar[ClusterError, NodeId, NodeId, RemoteClient, RemoteClient](peersById) {
            case entry@(name, peer) => sendToPeer(name, message, peer).as(entry)
          }
          sent.unit
      }
    }

    override def reply(to: NodeId, message: Either[Request, Response]) = {
      peersById.get(to) match {
        case None => IO.fail(InvalidNode(to, peersById.keySet))
        case Some(remote) => remote(message).refineOrDie {
          case err => ComputerSaysNo(to, err.getMessage, message)
        }
      }
    }

    override def peers(): UIO[Set[NodeId]] = UIO(peersById.keySet)
  }

  private def sendToPeer(name: NodeId, message: Request, peer: RemoteClient) = {
    peer(Left(message)).refineOrDie {
      case err => ComputerSaysNo(name, err.getMessage, Left(message))
    }
  }
}

sealed trait ClusterError extends Exception

final case class InvalidNode(name: NodeId, validNodes: Set[NodeId]) extends Exception(s"Can't reply to node '$name'; valid nodes are ${validNodes.mkString("[", ",", "]")}") with ClusterError

final case class ComputerSaysNo(name: NodeId, message: String, input: Either[Request, Response]) extends Exception with ClusterError
