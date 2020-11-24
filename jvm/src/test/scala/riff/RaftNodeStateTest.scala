package riff

import riff.jvm.NioDisk
import riff.test.FixedLocalCluster
import zio.ZIO
import zio.console.putStrLn

class RaftNodeStateTest extends BaseTest {

  def becomeLeader(client: Raft) = {
    val apply = for {
      _ <- client.scheduleFollowerHB
      _ <- client.applyNextBlocking
      role <- client.currentRole
    } yield role
    apply.repeatUntil(_.isLeader)
  }

  "RaftNode cluster" should {

    "append distributed data in a three node cluster" in withTmpDir { dir =>
      val test = for {
        cluster: FixedLocalCluster <- FixedLocalCluster.ofSize(dir, 3)
        _ <- cluster.sendHeartbeatTimeout()
        _ <- cluster.advanceUntil {
          cluster.leaderId.map(_.isDefined)
        }
        Some(leaderId) <- cluster.leaderId
        leader = cluster.nodes(leaderId)
        _ <- leader.raft.append("first")
        _ <- leader.raft.append("second")
        _ <- leader.raft.append("third")
        _ <- leader.raft.applyNextBlocking
        disk1 <- leader.raft.disk.latestUncommitted()
        _ = disk1 shouldBe LogCoords(1, Offset(1))
        _ <- leader.raft.applyNextBlocking
        disk2 <- leader.raft.disk.latestUncommitted()
        _ = disk2 shouldBe LogCoords(1, Offset(2))
        _ <- leader.raft.applyNextBlocking
        disk3 <- leader.raft.disk.latestUncommitted()
        _ = disk3 shouldBe LogCoords(1, Offset(3))
        _ <- cluster.advanceUntil {
          leader.raft.disk.readUncommitted(Offset(0), 3).map(_.size == 3).orDie
        }
        aFollowerId = (cluster.nodes.keySet - leaderId).head
        follower = cluster.nodes(aFollowerId)
        _ <- cluster.advanceUntil {
          follower.raft.disk.readCommitted(Offset(0), 3).map(_.size == 3).orDie
        }
        bFollowerId = (cluster.nodes.keySet - leaderId - aFollowerId).head
        follower2 = cluster.nodes(bFollowerId)
        _ <- cluster.advanceUntil {
          follower2.raft.disk.readCommitted(Offset(0), 3).map(_.size == 3).orDie
        }
        readA <- follower.raft.disk.readCommitted(Offset(0), 3)
        readB <- follower.raft.disk.readCommitted(Offset(0), 3)
      } yield (readA.toList.map(_.dataAsString), readB.toList.map(_.dataAsString))

      val (listA, listB) = test.value()
      listA shouldBe List("first", "second", "third")
      listB shouldBe List("first", "second", "third")
    }
  }

  "RaftNode" should {
    "become the leader in a single-node cluster" in withTmpDir { dir =>

      val test: ZIO[zio.ZEnv, Throwable, (Term, Term, List[Record])] = for {
        client <- Raft.apply(disk = NioDisk(dir))
        _ <- putStrLn("Starting....")
        _ <- client.run.fork
        _ <- putStrLn("Running....")
        role <- client.currentRole.repeatUntil(_.isLeader)
        _ = role.isLeader shouldBe true
        _ <- client.append("first entry!")
        _ <- client.append("second entry!")
        read <- client.disk.readUncommitted(Offset(0), 3).repeatUntil(_.size == 2)
        diskTerm <- client.disk.currentTerm()
        raftTerm <- client.nodeRef.map(_.term).get
      } yield (raftTerm, diskTerm, read.toList)

      test.map {
        case (raftTerm, diskTerm, read) =>
          val List(first, second) = read
          first.term shouldBe raftTerm
          first.dataAsString shouldBe "first entry!"
          second.term shouldBe raftTerm
          second.dataAsString shouldBe "second entry!"

          raftTerm shouldBe 1
          raftTerm shouldBe diskTerm
      }.timeout(testTimeout).value()
    }
  }
}
