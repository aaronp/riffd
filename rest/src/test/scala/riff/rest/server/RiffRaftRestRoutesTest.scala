package riff.rest.server

import io.circe.syntax._
import org.scalatest.GivenWhenThen
import riff.RiffRequest.AppendEntries
import riff.RiffResponse.AppendEntriesResponse
import riff._
import riff.json.RiffCodec._
import zio.{Ref, UIO}

class RiffRaftRestRoutesTest extends BaseRouteTest with GivenWhenThen {
  "RiffRaftRestRoutes" should {
    "POST /riff/<name> to send requests" in {
      val request: RiffRequest = AppendEntries.heartbeat(1, "two", LogCoords(3, Offset(4)), Offset(2))

      val testCase = for {
        disk <- DiskMap("test")
        msgs <- Ref.make[List[AddressedMessage]](Nil)
        testCluster = ClusterLocal("testNode") { msg =>
          msgs.update(msg :: _)
        }
        raft <- Raft(disk, testCluster.ourNodeId, raftCluster = UIO(testCluster))
        //
        //  our routes under test:
        // vvvvvvvvvvvvvvvvvvvvvvvvv
        routeUnderTest = RiffRaftRestRoutes(raft, zenv)
        response <- routeUnderTest(post("/riff/someSenderId", request.asJson.noSpaces)).value
        clusterMessages <- msgs.get
      } yield (response, clusterMessages)

      val (Some(response), clusterMessages: List[AddressedMessage]) = testCase.provide(zenv).value()
      response.status.code shouldBe 200
      val Some(contentType) = response.headers.find(_.name.value == "Content-Type")
      contentType.value shouldBe "application/json"
      response.bodyAsString shouldBe "true"

      val Seq(DirectMessage("testNode", "someSenderId", Right(nodeResponse: AppendEntriesResponse))) = clusterMessages
      nodeResponse.matchIndex shouldBe Offset(0)
    }

    "POST /riff/<name> to send response messages" in {
      val riffResponse: RiffResponse = AppendEntriesResponse(2, false, Offset(3))

      val testCase = for {
        disk <- DiskMap("test")
        msgs <- Ref.make[List[AddressedMessage]](Nil)
        testCluster = ClusterLocal("testNode") { msg =>
          msgs.update(msg :: _)
        }
        raft <- Raft(disk, testCluster.ourNodeId, raftCluster = UIO(testCluster))
        //
        //  our routes under test:
        // vvvvvvvvvvvvvvvvvvvvvvvvv
        routeUnderTest = RiffRaftRestRoutes(raft, zenv)
        response <- routeUnderTest(post("/riff/someSenderId", riffResponse.asJson.noSpaces)).value
        clusterMessages <- msgs.get
      } yield (response, clusterMessages)

      val (Some(response), clusterMessages: List[AddressedMessage]) = testCase.provide(zenv).value()
      response.status.code shouldBe 200
      val Some(contentType) = response.headers.find(_.name.value == "Content-Type")
      contentType.value shouldBe "application/json"
      response.bodyAsString shouldBe "true"

      clusterMessages.size shouldBe 0
    }
  }
}
