package riff.test

import java.nio.file.Path

import riff.jvm.NioDisk
import riff.test.FixedLocalCluster.TestNode
import riff.test.TestCluster._
import riff._
import zio.console.putStrLn
import zio.{UIO, URIO, ZEnv, ZIO}

case class FixedLocalCluster(cluster: TestCluster, nodes: Map[NodeId, TestNode]) {

  def leaderId: ZIO[Any, Nothing, Option[NodeId]] = {
    currentRolesById.map(_.collectFirst {
      case (id, role) if role.isLeader => id
    })
  }

  def currentRolesById: ZIO[Any, Nothing, Map[NodeId, Role]] = {
    ZIO.foreach(nodes) {
      case (id, node) =>
        node.raft.nodeRef.get.map(_.role).map(id -> _)
    }
  }

  def applyOpt(msg: Option[NextMsg]) = {
    msg match {
      case None => ZIO.unit
      case Some(NextMsg(targetId, _, Reply(from, to, either))) =>
        require(targetId == to, s"targetId $targetId != $to")
        for {
          _ <- nodes(targetId).raft.input(from)(either)
          _ <- nodes(targetId).raft.applyNextBlocking
        } yield ()
      case Some(NextMsg(targetId, _, BroadcastSent(from, request))) =>
        for {
          _ <- nodes(targetId).raft.input(from)(Left(request))
          _ <- nodes(targetId).raft.applyNextBlocking
        } yield ()
    }
  }

  def sendHeartbeatTimeout(forNode: NodeId = nodes.head._1) = {
    val node = nodes(forNode).raft
    for {
      _ <- node.push(Input.HeartbeatTimeout(None))
      _ <- node.applyNextBlocking
    } yield ()
  }

  override def toString: String = {
    val strings = nodes.toList.sortBy(_._1).map {
      case (name, node) =>
        s"""$name
           |${node.raft}""".stripMargin
    }
    strings.mkString("\n")
  }

  def advanceUntil(predicate: URIO[ZEnv, Boolean], iteration: Int = 0, retriesLeft: Int = 10): ZIO[zio.ZEnv, Any, Unit] = {
    for {
      next <- popNext()
      _ <- next match {
        case None if retriesLeft > 0 =>
          for {
            _ <- putStrLn("Pop returned no messages, sending heartbeat...")
            desc <- cluster.pretty
            _ <- putStrLn(desc)
//            _ <- sendHeartbeatTimeout()
          } yield ()

        case None => ZIO.fail(new Exception(s"No messages left to send, and retriesLeft is $retriesLeft"))
        case Some(_) => applyOpt(next)
      }
      updateStr = s"! UPDATE $iteration UPDATE $iteration UPDATE $iteration UPDATE $iteration UPDATE $iteration UPDATE $iteration UPDATE $iteration UPDATE $iteration UPDATE $iteration UPDATE $iteration !"
      _ <- putStrLn(updateStr.map(_ => '!'))
      _ <- putStrLn(updateStr)
      _ <- putStrLn(updateStr.map(_ => '!'))
      _ <- putStrLn("")
      allMsgs <- cluster.pretty
      _ <- putStrLn(allMsgs)
      done <- predicate
      updatedState <- pretty(next)
      _ <- putStrLn(updatedState)
      _ <- putStrLn("")
      _ <- advanceUntil(predicate, iteration + 1, retriesLeft - 1).unless(done)
    } yield ()
  }

  /**
   * pops the next message to be sent to a client
   *
   * @return the next node/message pair
   */
  def popNext(): ZIO[Any, Nothing, Option[NextMsg]] = {

    // figure out which is the next ordered message
    val pending: ZIO[Any, Nothing, List[Option[NextMsg]]] = ZIO.foreach(nodes.keySet.toList) { name =>
      cluster.messagesFor(name, false, 1).map {
        case (i, msg) :: _ => Some(NextMsg(name, i, msg))
        case _ => None
      }
    }

    // now that we have the sorted messages, remove it and update the 'last modified' index
    pending.flatMap { messages =>
      messages.flatten.sortBy(_.index).headOption match {
        case Some(next@NextMsg(from, index, _)) =>
          cluster.messagesFor(from, true, 1).flatMap { all =>
            require(all.size == 1, "messagesFor bug")
            require(all.head._1 == index, "messagesFor bug")
            // just another belt-and-braces, let's be sure our test jobbie workes...
            val check = cluster.messagesFor(from, false, 1).map {
              case Seq() => ZIO.unit
              case (nextIdx, _) +: _ => ZIO.fail(new Exception(s"Next index $nextIdx should be after $index")).unless(nextIdx > index)
            }

            check.as(Some(next))
          }
        case None => ZIO.none
      }
    }
  }


  def pretty(lastAppliedOpt: Option[NextMsg], verbose: Boolean = false) = {
    val lastAppliedAsMap = lastAppliedOpt.map(n => n.from -> n).toMap
    val pears = ZIO.foreach(nodes.toList.sortBy(_._1)) {
      case (name, node) =>
        for {
          nodeState <- node.pretty
          from <- cluster.messagesFrom(name)
          pending <- cluster.messagesFor(name, false)
          prettyLogs <- node.testLogging.pretty
          heartbeats <- node.testHeartbeat.pretty
          data <- node.raft.disk.ascii().orDie
        } yield {

          val lastAppliedStr = lastAppliedAsMap.get(name).fold("") { msg =>
            s"""
               |💥💥💥 APPLIED: ${msg} 💥💥💥
               |""".stripMargin
          }

          val detail =
            s""" - - LOGS - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
               |${prettyLogs}
               | - - Heartbeats - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
               |${heartbeats}""".stripMargin

          val body =
            s"""${nodeState}$lastAppliedStr
               | - - DATA - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
               |${data}
               | - - IN-FLIGHT FROM US - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
               |${TestCluster.format(from)}
               | - - IN-FLIGHT TO US - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
               |${TestCluster.format(pending)}
               |${if (verbose) detail else ""}""".stripMargin

          val header = s"=== $name ===================================================================================================="
          val desc = body.linesIterator.map { line =>
            s"    $line"
          }.mkString(s"$header\n", "\n", "\n")
          (name, desc)
        }
    }
    pears.map(_.map(_._2).mkString("\n"))
  }
}

object FixedLocalCluster {

  case class TestNode(raft: Raft) {
    def pretty = raft.nodeRef.get.map(_.toString)

    def clientFor(from: NodeId): RemoteClient = raft.input(from)

    def testCluster: TestCluster = raft.cluster.asInstanceOf[TestCluster]

    def testHeartbeat: TestHeartbeat = raft.heartbeat.asInstanceOf[TestHeartbeat]

    def testLogging: TestLogging = raft.logger.asInstanceOf[TestLogging]
  }

  def ofSize(dir: Path, n: Int): ZIO[zio.ZEnv, Throwable, FixedLocalCluster] = {
    val nodeNames = (1 to n).map(i => s"node-$i").toSet
    val nodeList = for {
      cluster <- TestCluster(nodeNames)
      nodeList <- ZIO.foreach(nodeNames) { name =>
        for {
          raft <- Raft.apply(
            NioDisk(dir.resolve(name)),
            name,
            UIO(cluster.clientForNode(name)),
            TestLogging(),
            TestHeartbeat())
        } yield name -> (cluster, TestNode(raft))
      }
    } yield nodeList

    nodeList.map { byId: Set[(String, (TestCluster, TestNode))] =>
      val (_, (testCluster, _)) = byId.head
      FixedLocalCluster(testCluster, byId
        .toMap
        .view
        .mapValues(_._2)
        .toMap.ensuring(_.size == byId.size))
    }
  }
}
