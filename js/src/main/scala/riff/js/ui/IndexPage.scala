package riff.js.ui

import org.scalajs.dom.html.Div
import org.scalajs.dom.{document, window}
import riff._
import riff.js.ui.JSRuntime.implicits.asRichZIO
import riff.js.{BroadcastChannel, JSDisk, RiffJS}
import riff.json.RiffCodec.{Broadcast, DirectMessage}
import scalatags.JsDom.all._
import zio.duration.durationInt
import zio.{Task, UIO, ZIO}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.util.{Failure, Random, Success}


@JSExportTopLevel("IndexPage")
case class IndexPage(targetDivId: String) {
  val targetDiv = document.getElementById(targetDivId).asInstanceOf[Div]

  private val SearchName = ".*name=(.*)".r
  val nodeName = document.location.search match {
    case SearchName(name) => name
    case _ => s"node-${System.currentTimeMillis().toString.takeRight(7)}"
  }

  val header = Header(nodeName)
  val appendTextTextArea = textarea().render // TextArea
  val buttonRowDiv = div().render
  val diskDiv = div().render
  val messagesDiv = div().render

  targetDiv.innerHTML = ""
  targetDiv.appendChild(div()(
    header.banner,
    div(
      h2("Data to append:"),
      appendTextTextArea),
    buttonRowDiv,
    diskDiv,
    messagesDiv
  ).render)

  val heartbeatFreq = UIO(Random.nextLong(8000) + 2000)
  val heartbeatTimer = Timer(header.canvasElm, heartbeatFreq).value()

  val messagesTable = new MessagesTable()
  messagesDiv.appendChild(messagesTable.render)

  // start the timeouts, etc
  val logger = {
    val roleChange = Logging.withRoleChange {
      case (_, to) =>
        header.updateRole(to.role.name)
        refresh.delay(100.millis).future()
    }
    val requestResponse = Logging.withUpdate {
      case (input, from, to) => messagesTable.onInput(input, from, to)
    }
    roleChange.zipRight(requestResponse)
  }

  val channel = new BroadcastChannel("riff")
  val cluster: ClusterLocal = ClusterLocal(nodeName) { msg =>
    import io.circe.syntax._
    Task(channel.postMessage(msg.asJson.noSpaces))
  }

  val disk = JSDisk(cluster.ourNodeId)
  var raftJS: Option[Raft] = None

  val init = for {
    riffJS <- RiffJS(disk, cluster, logger, heartbeatTimer.heartbeatService())
    _ <- riffJS.scheduleFollowerHB
  } yield riffJS
  init.future().onComplete {
    case Success(raft: Raft) =>
      raftJS = Some(raft)
      header.updateRole(raft.currentRole().value().name)
      val tick = for {
        _ <- heartbeatTimer.update()
        updated <- raft.applyNextInput
        _ <- refresh.unless(!updated)
      } yield ()

      // having issues with calling '.fork' in js :-(
      // we'll just hack it manually for now
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

  val table = DiskTable(disk) {
    update()
  }

  def refresh: ZIO[Any, DiskError, Unit] = for {
    _ <- ZIO.fromOption(raftJS).flatMap(_.nodeRef.get.map { state =>
      val peers: Set[NodeId] = cluster.currentPeers
      header.updateFromState(state, peers: Set[NodeId])
    }).either
    _ <- table.update
    _ = messagesTable.update()
  } yield ()

  def update(): Future[Unit] = refresh.future()

  diskDiv.innerHTML = ""
  diskDiv.appendChild(table.render)

  // do an initial refresh
  update()

}