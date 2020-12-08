package riff.rest

import riff.json.RiffCodec.AddressedMessage
import riff.rest.RiffRaftRest.Settings
import riff.{BaseTest, ClusterLocal, DiskMap, Raft}
import zio.interop.catz._
import zio.{Ref, UIO}

class RiffRaftRestClientRestTest extends BaseTest {

  "RiffRaftRestClient" should {
    "check" in {

      val words =
        """Gallimaufry - A hodge-podge, or jumbled medley (can also refer to an edible dish)
          |Bunbury - An imaginary person whose name is used as an excuse to some purpose, especially to visit a place.
          |Pluviophile - A person who takes great joy and comfort in rainy days.
          |Librocubularist - One who reads in bed.
          |Mullock - Rubbish, nonsense, or waste matter.
          |Ultracrepidarianism - The habit of giving opinions and advice on matters outside of one’s knowledge.
          |Snollyguster - A person, especially a politician, who is guided by personal advantage rather than by consistent, respectable principles.
          |Mumpsimus - a stubborn person who insists on making an error in spite of being shown that it is wrong
          |Hobbledehoy - an awkward, gawky young man
          |Smellfungus - an excessively faultfinding person
          |Uhtceare - To lie awake in the period just before dawn because you’re worrying too much to be able to sleep.""".stripMargin.linesIterator.toList

      val defn = scala.util.Random.shuffle(words.map(_.split(" - ").last))
      val w = scala.util.Random.shuffle(words.map(_.split(" - ").head))
      println("""-----""")
      defn.zipWithIndex.map {
        case (d, i) => s"${i + 1}) $d"
      }.foreach(println)
      println("""-----""")
      w.zipWithIndex.map {
        case (d, i) => s"${('A' + i).toChar}) $d"
      }.foreach(println)

      println("")
      words.foreach(println)
      println("")

    }
    "be able to send messages to a running server" ignore {

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
        //        client = RiffRaftRestClient()
      } yield ()

      val done = testCase.value()
    }
  }
}
