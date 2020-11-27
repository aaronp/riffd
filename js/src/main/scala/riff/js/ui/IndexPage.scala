package riff.js.ui

import org.scalajs.dom.html.{Div, TextArea}
import org.scalajs.dom.{document, window}
import riff.Role.Leader
import riff.{ClusterLocal, DiskError, Input, Logging, NodeId, Raft, RaftNodeState, Request, Response}
import riff.js.{BroadcastChannel, JSDisk, RiffJS}
import riff.js.ui.Main.implicits.asRichZIO
import riff.json.RiffCodec.{Broadcast, DirectMessage}
import scalatags.JsDom.all._
import zio.{Task, UIO, ZIO}
import zio.duration.durationInt

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.util.{Failure, Random, Success}

@JSExportTopLevel("IndexPage")
case class IndexPage(targetDivId: String) {
  val roleDiv = span().render
  val ourNodeDiv = span().render
  val clusterDiv = span().render
  val termDiv = span().render
  val leaderDiv = span().render
  val logDiv = div().render
  val appendTextTextArea = textarea().render // TextArea
  val buttonRowDiv = div().render
  val diskDiv = div().render
  val messagesDiv = div().render

  //<div style="display: inline; float: right; padding:20px"><canvas id="canvas" style="display: block" width="100" height="100"/></div>

  val canvasElm = canvas(id := "canvas", style := "display: block", width := "100", height := "100").render

  val targetDiv = document.getElementById(targetDivId).asInstanceOf[Div]

  targetDiv.innerHTML = ""
  targetDiv.appendChild(div()(
    div(style := "display: inline; float: right; padding-right:10px")(canvasElm),
    div(roleDiv, ":", ourNodeDiv),
    div("Cluster:", clusterDiv),
    div("Term:", termDiv),
    div("Leader:", leaderDiv),
    logDiv,
    div(
      h2("Data to append:"),
      appendTextTextArea),
    buttonRowDiv,
    diskDiv,
    messagesDiv
  ).render)

  val heartbeatFreq = UIO(Random.nextLong(10000) + 4000)
  val heartbeatTimer = Timer(canvasElm, heartbeatFreq).value()

  val messagesTable = new MessagesTable()
  messagesDiv.appendChild(messagesTable.render)

  // start the timeouts, etc
  val logger = {
    val roleChange = Logging.withRoleChange {
      case (_, to) =>
        roleDiv.innerText = to.role.name
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
      roleDiv.innerText = raft.currentRole().value().name
      val tick = for {
        _ <- heartbeatTimer.update()
        updated <- raft.applyNextInput
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

  val buttonStyle = "margin:8px; padding:8px"

  appendTextTextArea.onkeyup = (e) => {
    if (e.keyCode == 13) {
      e.preventDefault()
      onAppend()
    }
  }
  val appendButton = button(style := buttonStyle)("Append").render
  appendButton.onclick = e => {
    e.preventDefault()
    onAppend().future()
  }

  val pauseButton = button(style := buttonStyle)("⏸").render
  pauseButton.onclick = e => {
    e.preventDefault()
    cluster.togglePause()
    pauseButton.innerText = if (cluster.isPaused()) "▶️" else "⏸"
  }

  val triggerVoteButton = button(style := buttonStyle)("Trigger Vote").render
  triggerVoteButton.onclick = e => {
    e.preventDefault()
    raftJS.foreach(_.push(Input.HeartbeatTimeout(None)).future())
  }

  val addNodeButton = button(style := buttonStyle)("Add Node To Cluster").render
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

    def clusterDesc(nodes: Int) = nodes match {
      case 1 => ""
      case n => s" $n nodes"
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