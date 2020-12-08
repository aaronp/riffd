package riff.rest

import riff.json.RiffCodec.AddressedMessage
import riff.rest.RiffRaftRest.Settings
import riff.{ClusterLocal, DiskMap, Raft}
import zio.{Ref, UIO}
import zio.interop.catz._

class RiffRaftRestClientRestTest extends BaseRestTest {

  "RiffRaftRestClient" should {
    "be able to send messages to a running server" in {

      val testCase = for {
        disk <- DiskMap("test")
        msgs <- Ref.make[List[AddressedMessage]](Nil)
        testCluster = ClusterLocal("testNode") { msg =>
          msgs.update(msg :: _)
        }
        raft <- Raft(disk, testCluster.ourNodeId, raftCluster = UIO(testCluster))
        server = RiffRaftRest(Settings.fromRootConfig(), raft)
        _ = println("starting...")
        runningServer <- server.serve.fork
        _ = println("Server is serving...")
        client = RiffRaftRestClient()
      } yield ()

      val done = testCase.value()
    }
  }
}
