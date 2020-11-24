package riff.js.ui

import org.scalajs.dom.html.TextArea
import org.scalajs.dom.{document, window}
import riff.Role.Leader
import riff._
import riff.js.ui.Main.implicits._
import riff.js.{BroadcastChannel, JSDisk, RiffJS}
import riff.json.RiffCodec.{Broadcast, DirectMessage}
import scalatags.JsDom.all._
import zio.console.putStrLn
import zio.duration.durationInt
import zio.{Task, ZIO}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.util.{Failure, Success}

@JSExportTopLevel("IndexPage")
case class IndexPage(roleId: String,
                     nodeid: String,
                     canvasId: String,
                     clusterId: String,
                     termId: String,
                     leaderId: String,
                     logId: String,
                     appendTextId: String,
                     buttonsId: String,
                     diskId: String,
                     messagesId: String) {
  private val divFor = (document.getElementById _).andThen { x =>
    require(x != null);
    x
  }

  val heartbeatTimer = Timer(canvasId).value()
  val roleIdDiv = divFor(roleId)
  val ourNodeDiv = divFor(nodeid)
  val clusterDiv = divFor(clusterId)
  val termDiv = divFor(termId)
  val leaderDiv = divFor(leaderId)
  val logDiv = divFor(logId)
  val appendTextTextArea = divFor(appendTextId).asInstanceOf[TextArea]
  val buttonRowDiv = divFor(buttonsId)
  val diskDiv = divFor(diskId)
  val messagesDiv = divFor(messagesId)

  val messagesTable = new MessagesTable()
  messagesDiv.appendChild(messagesTable.render)

  // start the timeouts, etc
  val logger = {
    val roleChange = Logging.withRoleChange {
      case (_, to) =>
        roleIdDiv.innerText = to.role.name
        refresh.delay(100.millis).future()
    }
    val requestResponse = Logging.withUpdate {
      case (input, from, to) => messagesTable.onInput(input, from, to)
    }
    roleChange.zipRight(requestResponse)
  }


  val nodeName = s"node-${System.currentTimeMillis().toString.takeRight(7)}"
  val channel = new BroadcastChannel("riff")
  val cluster: ClusterLocal = ClusterLocal(nodeName) { msg =>
    import io.circe.syntax._
    Task(channel.postMessage(msg.asJson.noSpaces))
  }
  ClusterLocal

  ourNodeDiv.innerText = cluster.ourNodeId
  val disk = JSDisk(cluster.ourNodeId)
  var raftJS: Option[Raft] = None

  val init = for {
    riffJS <- RiffJS(disk, cluster, logger, heartbeatTimer.heartbeatService())
    _ <- riffJS.scheduleFollowerHB
  } yield riffJS
  init.future().onComplete {
    case Success(raft: Raft) =>
      raftJS = Some(raft)
      roleIdDiv.innerText = raft.currentRole().value().name
      val tick = for {
        _ <- heartbeatTimer.update()
        updated <- raft.applyNextInput
        _ <- putStrLn(s"updated: $updated")
        _ <- refresh.unless(!updated)
      } yield ()

      window.setInterval(() => tick.future(), 100)

      def handleMsg(from: String, reqOrResp: Either[Request, Response]) = {
        raft.input(from)(reqOrResp).future()
      }

      channel.onmessage = (msg) => {
        cluster.onMessage(msg.data.toString) match {
          case Some(Broadcast(from, message)) => handleMsg(from, Left(message))
          case Some(DirectMessage(from, _, message)) => handleMsg(from, message)
          case None =>
          // we're paused or the message isn't for us
        }
      }

    case Failure(err) => sys.error(s"ERROR CREATING RAFT:$err")
  }


  def onAppend(data: String = appendTextTextArea.value) = for {
    _ <- ZIO.fromOption(raftJS).flatMap(r => r.append(data)).either
  } yield ()

  appendTextTextArea.onkeyup = (e) => {
    if (e.keyCode == 13) {
      e.preventDefault()
      onAppend()
    }
  }
  val appendButton = button()("Append").render
  appendButton.onclick = e => {
    e.preventDefault()
    onAppend().future()
  }

  val pauseButton = button()("⏸").render
  pauseButton.onclick = e => {
    e.preventDefault()
    cluster.togglePause()
    pauseButton.innerText = if (cluster.isPaused()) "▶️" else "⏸"
  }

  val triggerVoteButton = button()("Trigger Vote").render
  triggerVoteButton.onclick = e => {
    e.preventDefault()
    raftJS.foreach(_.push(Input.HeartbeatTimeout(None)).future())
  }

  val addNodeButton = button()("Add Node To Cluster").render
  addNodeButton.onclick = e => {
    e.preventDefault()
    window.open(window.location.pathname)
  }

  val mainButtons = div(
    appendButton,
    triggerVoteButton,
    pauseButton
  )
  val secondaryButtons = div(
    addNodeButton
  )
  buttonRowDiv.appendChild(mainButtons.render)
  buttonRowDiv.appendChild(secondaryButtons.render)

  val table = DiskTable(disk, logDiv) {
    update()
  }

  def refresh: ZIO[Any, DiskError, Unit] = for {
    _ <- ZIO.fromOption(raftJS).flatMap(_.nodeRef.get.map(updateFromState)).either
    _ <- table.update
    _ = messagesTable.update()
  } yield ()

  def update(): Future[Unit] = refresh.future()

  diskDiv.innerHTML = ""
  diskDiv.appendChild(table.render)

  // do an initial refresh
  update()

  def updateFromState(state: RaftNodeState): Unit = {
    ourNodeDiv.innerText = s"${state.ourNodeId}"
    termDiv.innerText = s"${state.term}"
    leaderDiv.innerText = s"${state.currentLeaderId.getOrElse("?")}"
    val peers = cluster.currentPeers

    def clusterDesc(nodes : Int) = nodes match {
      case 1 => ""
      case n => s"$n nodes"
    }
    clusterDiv.innerText = {
      state.role match {
        case Leader(view) =>

          val allPeers = cluster.currentPeers ++ view.keySet

          def fmt(peer: NodeId) = {
            val clusterSuffix = if (cluster.currentPeers.contains(peer)) "" else "?"
            val leaderSuffix = if (view.keySet.contains(peer)) "✅" else "❌"
            s"$peer$clusterSuffix$leaderSuffix"
          }

          s"${allPeers.toList.sorted.map(fmt).mkString(s" ${clusterDesc(peers.size)} : [", ",", "]")}"
        case _ =>
          s"${cluster.currentPeers.toList.sorted.mkString(s"${clusterDesc(peers.size)} : [", ",", "]")}"
      }
    }
  }
}