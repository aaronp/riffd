package riff

import riff.json.RiffCodec.{AddressedMessage, Broadcast, DirectMessage}
import zio.{IO, Task, UIO}

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * Our cluster which uses the BroadcastChannel to communicate between peers (tabs)
 *
 * @param initialPeers
 * @param ourNodeId
 */
final class ClusterLocal(initialPeers: Set[NodeId], val ourNodeId: String, postMessage: AddressedMessage => Task[Unit]) extends Cluster.Service {
  private var peersSet = initialPeers

  def currentPeers: Set[NodeId] = peersSet

  override def peers(): UIO[Set[NodeId]] = UIO(peersSet)

  private var paused = false

  def isRunning() = !paused

  def isPaused() = paused

  def pause() = paused = true

  def togglePause() = paused = !paused

  private def addNodes(nodes: NodeId*) = {
    peersSet = peersSet ++ nodes.toSet - ourNodeId
  }

  /**
   * register a callback when messages are received
   *
   * @param msg
   */
  def onMessage(msg: String): Option[AddressedMessage] = {
    io.circe.parser.parse(msg).toTry.flatMap(_.as[AddressedMessage].toTry) match {
      case Success(broadcast@Broadcast(from, _)) =>
        addNodes(from)
        if (from == ourNodeId) {
          None
        } else {
          if (isRunning()) {
            Some(broadcast)
          } else {
            None
          }
        }
      case Success(direct@DirectMessage(from, to, _)) =>
        addNodes(from, to)
        if (from == ourNodeId || to != ourNodeId) {
          None
        } else {
          if (isRunning()) {
            Some(direct)
          } else {
            None
          }
        }
      case Failure(err) =>
        sys.error(s"$ourNodeId couldn't handle $msg, $err")
    }
  }

  override def broadcast(message: RiffRequest): IO[ClusterError, Unit] = {
    send(Broadcast(ourNodeId, message))
  }

  override def reply(to: NodeId, message: Either[RiffRequest, RiffResponse]): IO[ClusterError, Unit] = {
    send(DirectMessage(ourNodeId, to, message))
  }

  private def send(message: AddressedMessage) = {
    postMessage(message).refineOrDie {
      case NonFatal(e) =>
        val msg = message match {
          case Broadcast(_, m) => Left(m)
          case DirectMessage(_, _, m) => m
        }
        ComputerSaysNo(ourNodeId, s"Oops: $e, message: $message", msg)
    }.unless(paused)
  }
}

object ClusterLocal {
  def apply(nodeId: NodeId)(
    postMessage: AddressedMessage => Task[Unit]
  ): ClusterLocal = new ClusterLocal(Set.empty[NodeId], nodeId, postMessage)

}