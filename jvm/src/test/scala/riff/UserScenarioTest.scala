package riff

import io.circe.parser.decode
import riff.json.RiffCodec._

class UserScenarioTest extends BaseTest {

  def verifyTest(from: Snapshot, expected: Snapshot, inputs: Seq[AddressedMessage]): Unit = ???

  def verify(initialStateJson: String,
             expectedStateJson: String,
             messagesJson: Seq[String]
            ): Unit = {
    val from = decode[Snapshot](initialStateJson).toTry.get
    val expected = decode[Snapshot](expectedStateJson).toTry.get
    val inputs = messagesJson.map { input =>
      decode[AddressedMessage](input).toTry.get
    }
    verifyTest(from, expected, inputs)
  }

  "User Scenarios" should {

    "update the internal state" in {

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
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":0,"offset":0},"leaderCommit":0,"entries":[]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":0,"offset":0},"leaderCommit":0,"entries":[]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":0,"offset":0},"leaderCommit":0,"entries":[]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":0,"offset":0},"leaderCommit":0,"entries":[{"term":2,"data":"aGVsbG8="}]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":2,"offset":1},"leaderCommit":1,"entries":[]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":2,"offset":1},"leaderCommit":1,"entries":[]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":2,"offset":1},"leaderCommit":1,"entries":[{"term":2,"data":"SSBhbSB5b3VyIGxlYWRlcg=="}]}}""",
        """{"fromNode":"node-7846875","request":{"term":2,"leaderId":"node-7846875","previous":{"term":2,"offset":2},"leaderCommit":2,"entries":[]}}""")

      verify(before, expected, messages)
    }
  }
}
