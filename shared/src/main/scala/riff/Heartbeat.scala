package riff

import java.time.Duration

import cats.syntax.option._
import zio.clock.Clock
import zio.console.{Console, putStrLn}
import zio.duration.durationInt
import zio.random.Random
import zio.{Ref, _}

object Heartbeat {

  trait Service {
    def scheduleHeartbeat(key: Option[NodeId]): ZIO[Console with Clock with Random, Nothing, Unit]

    def zipRight(other: Service): Service = {
      val self = this
      new Service {
        override def scheduleHeartbeat(key: Option[NodeId]): ZIO[Console with Clock with random.Random, Nothing, Unit] = {
          for {
            _ <- self.scheduleHeartbeat(key)
            _ <- other.scheduleHeartbeat(key)
          } yield ()
        }
      }
    }
  }

  // given svcTag as izumi.reflect.Tag[Service] = Tag.auto.asInstanceOf[izumi.reflect.Tag[Service]]
  val scheduleFollowerHeartbeat: ZIO[Console with Clock with Random with Heartbeat, Nothing, Unit] = {
    for {
      _ <- putStrLn("Scheduling Follower HB Timeout")
      hb <- ZIO.environment[Heartbeat]
      _ <- hb.get.scheduleHeartbeat(None)
    } yield ()
  }

  def scheduleHeartbeat(key: NodeId): ZIO[Console with Clock with Random with Heartbeat, Nothing, Unit] = {
    for {
      hb <- ZIO.environment[Heartbeat]
      _ <- hb.get.scheduleHeartbeat(Some(key))
    } yield ()
  }

  def apply(followerRange: TimeRange = TimeRange.defaultFollower,
            leaderHBFrequency: Duration = TimeRange.defaultLeader): ZIO[Has[Queue[Input]], Nothing, Heartbeat.Service] = {
    Heartbeat(followerRange, TimeRange(leaderHBFrequency))
  }

  def apply(followerRange: TimeRange,
            leaderRange: TimeRange): ZIO[Has[Queue[Input]], Nothing, Instance] = {
    for {
      hbRef <- Ref.make(Map.empty[Option[NodeId], Long])
      queue <- ZIO.service[Queue[Input]]
    } yield new Instance(followerRange, leaderRange, queue, hbRef)
  }


  def period(followerRange: TimeRange,
             leaderHBPeriod: zio.duration.Duration) = {
    for {
      queue <- ZIO.service[Queue[Input]]
      peersRef <- Ref.make(Set.empty[NodeId])
      followerRef <- Ref.make(Option.empty[Fiber[Any, Any]])
      leaderRef <- Ref.make(Option.empty[HB])
    } yield new Periodic(
      followerRange,
      leaderHBPeriod,
      queue,
      peersRef,
      followerRef,
      leaderRef
    )
  }

  /**
   * a heartbeat which ALWAYS enqueues timeouts at a fixed schedule while leader
   *
   * @param followerRange
   * @param leaderHBFrequency
   * @param inputQueue
   * @param peersRef
   * @param followerRef
   * @param leaderRef
   */
  class Periodic(followerRange: TimeRange,
                 leaderHBFrequency: zio.duration.Duration,
                 inputQueue: Queue[Input],
                 peersRef: Ref[Set[NodeId]],
                 followerRef: Ref[Option[Fiber[Any, Any]]],
                 leaderRef: Ref[Option[HB]]
                ) extends Service {
    type Loop = ZIO[Any with Clock, Nothing, Nothing]

    /**
     * once a heartbeat for a node is scheduled, it just keeps pumping out HB timeouts until
     * a heartbeat for a 'None' is scheduled
     *
     * @param key
     * @return
     */
    override def scheduleHeartbeat(key: Option[NodeId]): ZIO[Console with Clock with Random, Nothing, Unit] = {
      key match {
        case None => updateFollowerHeartbeat
        case Some(node) => updateLeaderHeartbeat(node)
      }
    }

    private val cancelFollower = {
      for {
        followerFiberOpt <- followerRef.get
        _ <- ZIO.fromOption(followerFiberOpt).flatMap(_.interrupt).either
      } yield ()
    }

    private def updateLeaderHeartbeat(node: NodeId) = {
      val updateLeaderHB = leaderRef.get.flatMap {
        case None =>
          for {
            _ <- peersRef.update(_ + node)
            leaderHBFiber <- startLeaderHeartbeats()
            _ <- leaderRef.set(leaderHBFiber.some)
          } yield ()
        case Some(_) =>
          // we're already running - just update our peers refs
          peersRef.update(_ + node)
      }

      for {
        _ <- cancelFollower
        _ <- updateLeaderHB
        _ <- followerRef.set(None)
      } yield ()
    }

    private def startLeaderHeartbeats(): URIO[Console with Clock, HB] = {
      onLeaderHeartbeat.schedule(Schedule.fixed(leaderHBFrequency)).unit.fork
    }

    private def followerTimeout = {
      for {
        _ <- inputQueue.offer(Input.HeartbeatTimeout(None))
      } yield ()
    }
    private def onLeaderHeartbeat = {
      for {
        nodes <- peersRef.get
        timeouts = nodes.map { id => Input.HeartbeatTimeout(id.some) }
        _ <- inputQueue.offerAll(timeouts)
      } yield ()
    }
    private def enqueueFollowerTimeout = {
      for {
        _ <- cancelFollower
        delay <- followerRange.nextDuration
        newFiber <- scheduleFollowerExpiryAfter(delay.millis).fork
        _ <- followerRef.set(newFiber.some)
      } yield ()
    }

    def scheduleFollowerExpiryAfter(delay: Duration) = {
      followerTimeout.delay(delay)
    }

    private def updateFollowerHeartbeat = {
      // if we're sending HB's to our followers, stop it
      leaderRef.get.flatMap {
        case None => enqueueFollowerTimeout
        case Some(fiber) =>
          for {
            _ <- fiber.interrupt
            _ <- enqueueFollowerTimeout
          } yield ()
      }
    }
  }

  /**
   *
   * @param followerRange
   * @param leaderRange
   * @param inputQueue
   * @param hbRef
   */
  class Instance(followerRange: TimeRange,
                 leaderRange: TimeRange,
                 inputQueue: Queue[Input],
                 hbRef: Ref[Map[Option[NodeId], Long]]) extends Service {

    /**
     * key -> update the expiry time and schedule an 'enqueue' to happen.
     *
     * If at the point of scheduled enqueue the expiry is at the expected version, then the action will be performed
     *
     * @param key
     * @return
     */
    override def scheduleHeartbeat(key: Option[NodeId]) = enqueue(key).fork.unit

    private def enqueue(key: Option[NodeId]) = {
      val range = key match {
        case None => followerRange
        case _ => leaderRange
      }

      for {
        time <- range.nextDuration
        version <- hbRef.modify { byKey: Map[Option[NodeId], Long] =>
          val nextVersion = byKey.getOrElse(key, -1L) + 1L
          nextVersion -> byKey.updated(key, nextVersion)
        }

        _ <- putStrLn(s"enqueue($key) after $time")
        _ <- scheduleAfter(key, version, time)
      } yield time
    }

    def scheduleAfter(key: Option[String], expectedVersion: Long, delayInMS: Int) = {
      val hb = Input.HeartbeatTimeout(key)
      for {
        _ <- ZIO.sleep(delayInMS.millis)
        byId <- hbRef.get
        currentVersion = byId.getOrElse(key, -1)
        _ <- inputQueue.offer(hb).unless(currentVersion != expectedVersion)
      } yield ()
    }
  }
}