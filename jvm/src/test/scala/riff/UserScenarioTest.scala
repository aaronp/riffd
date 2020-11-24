package riff

import io.circe.parser.decode
import riff.json.RiffCodec._
import riff.test.{TestCluster, TestHeartbeat, TestLogging}
import zio.{UIO, ZIO}

class UserScenarioTest extends BaseTest {

  "User Scenarios" should {

    "first leader appends" in {

      val before = """{"ourNodeId":"node-5327946","term":0,"commitIndex":0,"lastApplied":0,"maxSendBatchSize":100,"role":"Follower","currentLeaderId":null}"""

      val expected =
        """{
  "ourNodeId" : "node-5327946",
  "term" : 2,
  "commitIndex" : 3,
  "lastApplied" : 0,
  "maxSendBatchSize" : 100,
  "role" : {
    "node-5330539" : {
      "id" : "node-5330539",
      "nextIndex" : 4,
      "matchIndex" : 3,
      "lastMessageReceived" : "2020-11-28T20:29:41.727Z",
      "lastHeartbeatSent" : "2020-11-28T20:29:40.727Z"
    },
    "node-5331161" : {
      "id" : "node-5331161",
      "nextIndex" : 4,
      "matchIndex" : 3,
      "lastMessageReceived" : "2020-11-28T20:29:40.729Z",
      "lastHeartbeatSent" : "2020-11-28T20:29:40.729Z"
    }
  },
  "currentLeaderId" : "node-5327946"
}"""

      val messages = List(
        """{"fromNode":"node-5330539","request":{"term":1,"candidateId":"node-5330539","latest":{"term":0,"offset":0}}}""",
        """{"heartbeat":null}""",
        """{"fromNode":"node-5331161","response":{"term":2,"granted":true}}""",
        """{"fromNode":"node-5330539","response":{"term":2,"granted":true}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":0}}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":0}}""",
        """{"heartbeat":"node-5330539"}""",
        """{"heartbeat":"node-5331161"}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":0}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":0}}""",
        """{"heartbeat":"node-5330539"}""",
        """{"heartbeat":"node-5331161"}""",
        """{"append":"aGVsbG8="}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":0}}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":1}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":0}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":1}}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":1}}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":1}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":0}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":1}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":1}}""",
        """{"append":"d29ybGQ="}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":2}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":2}}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":2}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":1}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":2}}""",
        """{"heartbeat":"node-5330539"}""",
        """{"heartbeat":"node-5331161"}""",
        """{"append":"SSdtIHRoZSBsZWFkZXIh"}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":2}}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":3}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":2}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":3}}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":3}}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":3}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":2}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":3}}""",
        """{"fromNode":"node-5331161","response":{"term":2,"success":true,"matchIndex":3}}""",
        """{"heartbeat":"node-5330539"}""",
        """{"heartbeat":"node-5331161"}""",
        """{"fromNode":"node-5330539","response":{"term":2,"success":true,"matchIndex":3}}""")

      verify(before, expected, messages, Set("node-5330539", "node-5331161"))

    }
    "initial scenario" in {

      val before = """{"ourNodeId":"node-7847509","term":2,"commitIndex":0,"lastApplied":0,"maxSendBatchSize":100,"role":"Follower","currentLeaderId":"node-7846875"}"""

      val expected =
        """{
  "ourNodeId" : "node-7847509",
  "term" : 2,
  "commitIndex" : 2,
  "lastApplied" : 2,
  "maxSendBatchSize" : 100,
  "role" : "Follower",
  "currentLeaderId" : "node-7846875"
}"""

      val messages = List(
        """{"fromNode":"node-7843863","response":{"term":2,"granted":false}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":0,"offset":0},"leaderCommit":0,"entries":[]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":0,"offset":0},"leaderCommit":0,"entries":[{"term":2,"data":"aGVsbG8="}]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":2,"offset":1},"leaderCommit":1,"entries":[]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":2,"offset":1},"leaderCommit":1,"entries":[]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":2,"offset":1},"leaderCommit":1,"entries":[{"term":2,"data":"SSBhbSB5b3VyIGxlYWRlcg=="}]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":2,"offset":2},"leaderCommit":2,"entries":[]}}""")

      verify(before, expected, messages, Set("node-7843863", "node-7846875"))
    }
  }

  def applyNextMsg(initialState: Raft)(message: Input) = initialState.applyInput(message)

  def verifyTest(from: Snapshot, expected: Snapshot, inputs: Seq[Input], clusterNodes: Set[NodeId]): Unit = {

    val riffRaftIO = for {
      testCluster <- TestCluster(clusterNodes)
      disk <- Disk.inMemory("test")
      logging = TestLogging()
      hb = TestHeartbeat()
      initialState <- Raft.apply(
        disk,
        nodeId = from.ourNodeId,
        raftCluster = UIO(testCluster.clientForNode(from.ourNodeId)),
        logger = logging,
        newHeartbeat = hb
      )
      _ <- initialState.nodeRef.set(from.asState())
      _ <- ZIO.foreach(inputs)(applyNextMsg(initialState)).provideCustomLayer(initialState.dependencies)
      endstate <- initialState.nodeRef.get
    } yield endstate

    val actualEndState = riffRaftIO.value()
    Snapshot(actualEndState) shouldBe expected
  }

  def verify(initialStateJson: String,
             expectedStateJson: String,
             messagesJson: Seq[String],
             clusterNodes: Set[NodeId]
            ): Unit = {
    val from = decode[Snapshot](initialStateJson).toTry.get
    val expected = decode[Snapshot](expectedStateJson).toTry.get
    val inputs = messagesJson.map { input =>
      decode[Input](input)(InputCodec).toTry.get
    }
    verifyTest(from, expected, inputs, clusterNodes)
  }

}
