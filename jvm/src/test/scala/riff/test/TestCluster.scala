package riff.test

import java.util.concurrent.atomic.AtomicInteger

import riff._
import riff.test.TestCluster.{Counter, TestMsg}
import zio.{IO, Ref, UIO, ZIO}

final case class TestCluster(allNodes: Set[NodeId],
                             messagesRef: Ref[List[(Int, TestMsg)]],
                             lastConsumedById: Ref[Map[NodeId, Counter]]) {
  private val counter = new AtomicInteger(0)

  def pretty = {
    for {
      allMessages <- messagesRef.get
      sorted = allMessages.sortBy(_._1)
      lastConsumed <- lastConsumedById.get
    } yield {
      val consumedStr = lastConsumed.map {
        case (id, counter) => s"$id : $counter"
      }.mkString("Consumed:\n", "\n", "\nMessages:\n")

      sorted.mkString(consumedStr,"\n", "\n")
    }
  }

  def messagesFor(node: NodeId, updateLastConsumed: Boolean, limit: Int = Int.MaxValue) = {
    for {
      byId <- lastConsumedById.get
      list <- messagesRef.get
      counter = byId.getOrElse(node, Counter())
      lastConsumedIndex = counter.lastConsumedIndex
      filtered = list.sortBy(_._1).collect { case (idx, msg) if msg.isFor(node) && lastConsumedIndex < idx => idx -> msg }
      result <- lastConsumedById.update { map =>
        val min = filtered.map(_._1).min
        map.updated(node, counter.copy(lastConsumedIndex = min))

      }.unless(!updateLastConsumed || list.isEmpty).as(filtered.take(limit))
    } yield result.sortBy(_._1)
  }

  def messagesFrom(node: NodeId): ZIO[Any, Nothing, List[(Term, TestMsg)]] = {
    for {
      list <- messagesRef.get
      lastConsumedIndex <- nextIndexFrom(node)
      filtered = list.collect { case (idx, msg) if msg.from == node && lastConsumedIndex < idx => idx -> msg }
    } yield filtered.sortBy(_._1)
  }

  /**
   * @param node the sending node
   * @return the minimum message index of the remaining peers
   */
  private def nextIndexFrom(node: NodeId): ZIO[Any, Nothing, Term] = {
    lastConsumedById.get.map { byId =>
      (byId - node).values.map(_.lastConsumedIndex).minOption.getOrElse(0)
    }
  }

  def clientForNode(fromNodeId: NodeId) = new Cluster.Service {
    override def broadcast(message: Request): IO[ClusterError, Unit] = {
      val entry = (counter.incrementAndGet(), TestMsg.broadcast(fromNodeId, message))
      messagesRef.update(entry :: _).unit
    }

    override def reply(to: NodeId, message: Either[Request, Response]): IO[ClusterError, Unit] = {
      val entry = (counter.incrementAndGet(), TestMsg.reply(fromNodeId, to, message))
      messagesRef.update(entry :: _).unit
    }

    override def peers(): UIO[Set[NodeId]] = UIO(allNodes - fromNodeId)
  }
}

object TestCluster {

  def format(messages: Seq[(Int, TestCluster.TestMsg)]): String = {
    messages.map {
      case (i, Reply(from, to, Left(request))) =>
        s"$i) request $from -> $to :: $request"
      case (i, Reply(from, to, Right(response))) =>
        s"$i) response $from -> $to :: $response"
      case (i, BroadcastSent(from, request)) =>
        s"$i) broadcast from $from :: $request"
    }.mkString("\n")
  }

  case class Counter(lastConsumedIndex: Int = -1)

  case class NextMsg(from: NodeId, index: Int, msg: TestCluster.TestMsg) {
    override def toString: String = msg match {
      case Reply(from, to, Left(msg)) => s"$index) $from -> $to : $msg"
      case Reply(from, to, Right(msg)) => s"$index) $from -> $to : $msg"
      case BroadcastSent(from, msg) => s"$index) $from broadcasted $msg"
    }
  }

  sealed trait TestMsg {
    def from: NodeId

    def isFor(me: NodeId): Boolean
  }

  object TestMsg {
    def broadcast(from: NodeId, message: Request) = BroadcastSent(from, message)

    def reply(from: NodeId, to: NodeId, message: Either[Request, Response]) = Reply(from, to, message)
  }

  final case class Reply(override val from: NodeId, to: NodeId, message: Either[Request, Response]) extends TestMsg {
    override def isFor(me: NodeId): Boolean = me == to
  }

  final case class BroadcastSent(override val from: NodeId, message: Request) extends TestMsg {
    override def isFor(me: NodeId): Boolean = me != from
  }

  def apply(peers: Set[NodeId]): ZIO[Any, Nothing, TestCluster] = {
    for {
      messagesRef <- Ref.make(List.empty[(Int, TestMsg)])
      lastConsumed <- Ref.make(Map[NodeId, Counter]())
    } yield TestCluster(peers, messagesRef, lastConsumed)
  }
}
