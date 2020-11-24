package riff

import riff.Input.HeartbeatTimeout
import zio._
import zio.console.putStrLn
import zio.duration._

class HeartbeatTest extends BaseTest {

  val leaderTimeout = 250.millis
  val repeatFreq = (leaderTimeout.toMillis / 2).millis
  val testWindow = (leaderTimeout.toMillis * 50).millis

  "Heartbeat.period" should {
    "repeat a timeout" in {
      val queue = Queue.bounded[Input](10).value()
      val queueLayer = UIO(queue).toLayer

      def pull(received: Ref[List[Input]]) = {
        for {
          input <- queue.takeAll
          _ <- received.update(input ::: _)
          list <- received.get
        } yield list
      }

      // while we're a leader, we should keep enqueueing timeouts for our follower nodes
      def pullExpected(received: Ref[List[Input]]): ZIO[Any, Nothing, List[Input]] = {
        pull(received).repeatUntil { list =>
          val ids = list.collect {
            case Input.HeartbeatTimeout(Some(id)) => id
          }
          ids.count(_ == "foo") > 4
        }
      }


      val test = for {
        // create our HB under test
        heartbeat <- Heartbeat.period(
          followerRange = TimeRange(500.millis),
          leaderTimeout
        ).provideCustomLayer(queueLayer)
        // schedule a HB within the leader timeout -- we still expect to see HB messages regardless
        _ <- heartbeat.scheduleHeartbeat(Some("foo")).schedule(Schedule.spaced(repeatFreq)).fork
        receivedRef <- Ref.make(List[Input]())
        receivedBeforeFollower <- pullExpected(receivedRef)
        // now schedule a follower HB timeout - we shouldn't see any more HBs timeouts for follower nodes
        _ <- heartbeat.scheduleHeartbeat(None).schedule(Schedule.spaced(repeatFreq)).fork
        _ <- ZIO.sleep(leaderTimeout)
        _ <- pull(receivedRef)
        afterFollowerList <- receivedRef.get
      } yield (receivedBeforeFollower, afterFollowerList)

      val (receivedBeforeFollower, afterFollowerList) = test.value()
      receivedBeforeFollower.size should be >= 4
      val diff = afterFollowerList.size - receivedBeforeFollower.size
      diff should be <= 1
    }
  }
  "Heartbeat.scheduleHeartbeat" should {
    "cancel the timeouts" in {
      val queue = Queue.bounded[Input](10).value()
      val queueLayer = UIO(queue).toLayer
      val test = for {
        // create our HB under test
        heartbeat <- Heartbeat(
          followerRange = TimeRange(500.millis),
          leaderRange = TimeRange(leaderTimeout),
        ).provideCustomLayer(queueLayer)
        // schedule a HB for a node, repeating every 1/2 leader range
        _ <- heartbeat.scheduleHeartbeat(Some("foo")).schedule(Schedule.spaced(repeatFreq))
      } yield ()

      test.timeout(testWindow).value()
      queue.size.value shouldBe 0
    }
    "trigger a HB timeout message if scheduleHeartbeat isn't called frequently enough" in {
      val queue = Queue.bounded[Input](10).value()
      val queueLayer = UIO(queue).toLayer

      val test: ZIO[zio.ZEnv, Nothing, (Input, Input)] = for {
        // create our HB under test
        heartbeat <- Heartbeat(
          followerRange = TimeRange(500.millis),
          leaderRange = TimeRange(leaderTimeout),
        ).provideCustomLayer(queueLayer)
        // schedule a HB for a node, repeating every 1/2 leader range
        _ <- heartbeat.scheduleHeartbeat(Some("foo")).delay(repeatFreq)
        _ <- heartbeat.scheduleHeartbeat(Some("bar")).delay(repeatFreq)
        timeout1 <- queue.take
        timeout2 <- queue.take
      } yield (timeout1, timeout2)

      val Some((HeartbeatTimeout(Some(t1)), HeartbeatTimeout(Some(t2)))) = test.timeout(testWindow).value()
      Set(t1, t2) shouldBe Set("foo", "bar")
    }
  }
}
