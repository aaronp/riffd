package riff.js.ui

import java.time.Duration
import java.util.concurrent.TimeUnit

import org.scalajs.dom.html.Canvas
import org.scalajs.dom.{CanvasRenderingContext2D, document, window}
import riff._
import riff.js.ui.Main.implicits.asRichZIO
import riff.js.ui.Timer.{FollowerState, LeaderState, State}
import zio._
import zio.clock._
import zio.console.Console
import zio.duration.{Duration, durationInt}

import scala.util.Random

case class Timer(canvas: Canvas,
                 nextTimeout: Ref[Timer.Range],
                 nextExpiryMs: UIO[Long],
                 radius: Int = 30,
                 color: Double => String,
                 width: Double => Int
                ) {

  val context: CanvasRenderingContext2D = {
    canvas.getContext("2d") match {
      case value: CanvasRenderingContext2D => value
    }
  }

  private val endRads = 1.5 * Math.PI

  def renderCircle(remainingMs: Long, totalPeriodDuration: Long) = {
    val (centerX, centerY) = (50, 50)

    val pcnt = {
      val left = remainingMs.min(totalPeriodDuration).max(0)
      val ratio = left.toDouble / totalPeriodDuration.toDouble
      1.0 - (ratio)
    }
    val startRads = {
      val offset = {
        pcnt * 2 * Math.PI
      }

      (-0.5 * Math.PI) + offset
    }

    context.clearRect(0, 0, canvas.width, canvas.height)
    context.strokeStyle = color(pcnt)
    context.lineWidth = width(pcnt)
    context.beginPath
    context.arc(centerX, centerY, radius, startRads, endRads)
    context.stroke()
  }

  def draw(now: Long, started: Long, expiry: Long) = {
    val remaining = expiry - now
    val totalDuration = expiry - started
    if (remaining <= 0) {
      //      reset()
      UIO(renderCircle(0, totalDuration))
    } else {
      UIO(renderCircle(remaining, totalDuration))
    }
  }

  def update() = {
    for {
      now <- currentTime(TimeUnit.MILLISECONDS)
      (started, expiry) <- nextTimeout.get
      _ <- draw(now, started, expiry)
    } yield ()
  }

  def reset() = nextExpiryMs.flatMap(resetAfter)

  def resetAfter(delayInMS: Long): ZIO[Clock, Nothing, Unit] = for {
    now <- currentTime(TimeUnit.MILLISECONDS)
    expiry = now + delayInMS
    _ <- nextTimeout.set(now -> expiry)
  } yield renderCircle(delayInMS, delayInMS)


  def heartbeatService(followerRange: TimeRange = TimeRange(10.seconds, 15.seconds),
                       leaderHBFreq: Duration = 5.seconds) = {
    for {
      queue <- ZIO.service[Queue[Input]]
      ref <- Ref.make[Option[State]](Option.empty)
    } yield asHeartbeatService(followerRange, leaderHBFreq, queue, ref)
  }

  def asHeartbeatService(followerRange: TimeRange,
                         leaderHBFrequency: zio.duration.Duration,
                         inputQueue: Queue[Input],
                         currentStateRef: Ref[Option[State]]
                        ): Heartbeat.Service = {
    new Heartbeat.Service {
      def tick() = {
        resetAfter(leaderHBFrequency.toMillis).future()
        currentStateRef.get.value() match {
          case Some(LeaderState(_, peers)) =>
            val inputs = peers.map { peer =>
              Input.HeartbeatTimeout(Some(peer))
            }
            inputQueue.offerAll(inputs).value()
          case Some(FollowerState(_)) =>
            inputQueue.offer(Input.HeartbeatTimeout(None)).value()
          case None =>
        }
      }


      override def scheduleHeartbeat(key: Option[NodeId]): ZIO[Console with Clock with random.Random, Nothing, Unit] = {

        def resetFollower(): Option[State] = {
          val timeoutMS = followerRange.nextDuration.value()
          resetAfter(timeoutMS).future()
          val handle = window.setTimeout(() => tick, timeoutMS)
          Option(FollowerState(handle))
        }

        def startLeader(peer: NodeId): Option[State] = {
          val timeoutMS = leaderHBFrequency.toMillis
          resetAfter(leaderHBFrequency.toMillis).future()
          val handle = window.setInterval(() => tick, timeoutMS)
          Option(LeaderState(handle, Set(peer)))
        }

        val update = currentStateRef.update { opt =>
          (opt, key) match {
            case (Some(leader@LeaderState(_, _)), Some(peer)) =>
              Some(leader.update(peer))
            case (Some(LeaderState(tickHandle, _)), None) =>
              window.clearInterval(tickHandle)
              resetFollower()
            case (Some(FollowerState(tickHandle)), Some(peer)) =>
              window.clearTimeout(tickHandle)
              startLeader(peer)
            case (Some(FollowerState(tickHandle)), None) =>
              window.clearTimeout(tickHandle)
              resetFollower()
            case (None, Some(peer)) => startLeader(peer)
            case (None, None) => resetFollower()
          }
        }
        update.unit
      }
    }
  }
}

object Timer {
  type Started = Long
  type Expiry = Long
  type Range = (Started, Expiry)


  def apply(canvasId: String): ZIO[Clock, Nothing, Timer] = {
    val default = UIO(Random.nextLong(10000) + 4000)
    val canvas: Canvas = document.getElementById(canvasId).asInstanceOf[Canvas]
    apply(canvas, default)
  }

  def apply(canvas: Canvas, nextExpiryMs: UIO[Long]): ZIO[Clock, Nothing, Timer] = {
    def color(pcnt: Double) = {
      if (pcnt > 0.8) {
        "#FF0000"
      } else if (pcnt > 0.6) {
        "#E6652B"
      } else "#00FF00"
    }

    val radius = 20

    def width(pcnt: Double) = {
      val d = 1 + (pcnt * 10)
      d.toInt
    }

    for {
      time <- currentTime(TimeUnit.SECONDS)
      next <- nextExpiryMs
      ref <- Ref.make(time -> (time + next))
    } yield new Timer(canvas, ref, nextExpiryMs, radius, color, width)
  }


  private sealed trait State

  private case class LeaderState(handle: Int, peers: Set[NodeId] = Set.empty) extends State {
    def update(peer: NodeId) = copy(peers = peers + peer)
  }

  private case class FollowerState(handle: Int) extends State


}