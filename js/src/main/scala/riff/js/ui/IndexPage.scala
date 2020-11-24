package riff.js.ui

import org.scalajs.dom.html.Div
import org.scalajs.dom.{document, window}
import riff._
import riff.js.ui.IntroJSHandle.IntroQueryKey
import riff.js.ui.JSRuntime.implicits.asRichZIO
import riff.js.{BroadcastChannel, JSDisk, RiffJS}
import riff.json.RiffCodec.{Broadcast, DirectMessage}
import scalatags.JsDom.all._
import zio.duration.{Duration, durationInt}
import zio.{Task, ZIO}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.util.{Failure, Success}

@JSExportTopLevel("IndexPage")
case class IndexPage(targetDivId: String) {
  val nodeName = {
    val SearchName = ".*name=(.*)".r
    document.location.search match {
      case SearchName(name) => name
      case _ => s"node-${System.currentTimeMillis().toString.takeRight(7)}"
    }
  }

  /**
   * Our timeout ranges for leaders/followers
   */
  val followerRange: TimeRange = TimeRange(8.seconds, 12.seconds)
  val leaderHBFreq: Duration = (followerRange.min.getSeconds.toInt / 2).seconds

  val targetDiv = document.getElementById(targetDivId).asInstanceOf[Div]

  val header = Header(nodeName)
  val appendTextTextArea = textarea(cols := 100, rows := 8).render // TextArea
  val appendDataDiv = div(
    h2("Data to append:"),
    appendTextTextArea).render
  val buttonRowDiv = div().render
  val diskDiv = div().render
  val messagesDiv = div().render

  targetDiv.innerHTML = ""
  targetDiv.appendChild(div(
    header.forkMe,
    header.banner,
    div(`class` := "content")(
      appendDataDiv,
      buttonRowDiv,
      diskDiv,
      messagesDiv
    )).render)

  val heartbeatTimer = Timer(header.canvasElm, followerRange.nextDuration.value()).value()

  val messagesTable = new MessagesTable()
  messagesDiv.appendChild(messagesTable.render)

  // start the timeouts, etc
  val logger = {
    val roleChange = Logging.withRoleChange {
      case (_, to) =>
        header.updateRole(to.role)
        appendDataDiv.style.display = if (to.role.isLeader) "block" else "none"
        appendButton.style.display = if (to.role.isLeader) "inline" else "none"
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
    riffJS <- RiffJS(disk, cluster, logger, heartbeatTimer.heartbeatService(followerRange, leaderHBFreq))
    // let's immediately schedule a follower HB after init, which will just keep working indefinitely thereafter
    _ <- riffJS.scheduleFollowerHB
  } yield riffJS

  init.future().onComplete {
    case Success(raft: Raft) =>
      raftJS = Some(raft)
      val role = raft.currentRole().value()
      header.updateRole(role)
      appendDataDiv.style.display = if (role.isLeader) "block" else "none"
      appendButton.style.display = if (role.isLeader) "inline" else "none"
      val tick = for {
        _ <- heartbeatTimer.update()
        updated <- raft.applyNextInput
        _ <- refresh.unless(!updated)
      } yield ()

      // having issues with calling '.fork' in js :-(
      // we'll just hack it manually for now
      window.setInterval(() => tick.future(), 100)

      def handleMsg(from: String, reqOrResp: Either[RiffRequest, RiffResponse]) = {
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

  val pauseButton = {
    val buttonHint =
      """Use this button to ignore/unignore requests (e.g. drop messages).
        |This feature helps you test scenarios such as network partitions or otherwise unresponsive nodes.
        |""".stripMargin
    button(style := "margin:8px; padding:5px", attr("data-title") := "Pause/Resume messages", attr("data-intro") := buttonHint)("⏸ Pause").render
  }
  pauseButton.onclick = e => {
    e.preventDefault()
    cluster.togglePause()
    pauseButton.innerText = if (cluster.isPaused()) "▶️ Resume" else "⏸ Pause"
  }

  val triggerVoteButton = {
    val buttonHint =
      """Use this button to send a 'RequestVote' message to the other nodes in the cluster.
        |Will they elect you leader? It depends on your term and commit log!
        |""".stripMargin
    button(style := buttonStyle, attr("data-title") := "Can we become the leader?", attr("data-intro") := buttonHint)("Trigger Vote").render
  }
  triggerVoteButton.onclick = e => {
    e.preventDefault()
    raftJS.foreach(_.push(Input.HeartbeatTimeout(None)).future())
  }

  val addNodeButton = {
    val addButtonHint =
      """This demo uses dynamic clusters.
        |The implication being that single-node clusters aren't allowed to become leaders.
        |Use this button to add a new node to your cluster in a new tab
        |""".stripMargin

    button(style := buttonStyle, attr("data-title") := "Grow Your Cluster", attr("data-intro") := addButtonHint)("Add Node To Cluster").render
  }
  addNodeButton.onclick = e => {
    e.preventDefault()
    val path = window.location.pathname
    val noIntro = s"${IntroQueryKey}=false"
    val url = s"$path?$noIntro"
    window.open(url)
  }

  val mainButtons = div(
    appendButton,
    triggerVoteButton,
    addNodeButton,
    pauseButton
  )
  buttonRowDiv.appendChild(mainButtons.render)

  val diskTable = DiskTable(disk) {
    update()
  }

  def refresh: ZIO[Any, DiskError, Unit] = for {
    _ <- ZIO.fromOption(raftJS).flatMap(_.nodeRef.get.map { state =>
      val peers: Set[NodeId] = cluster.currentPeers
      header.updateFromState(state, peers: Set[NodeId])
    }).either
    _ <- diskTable.update
    _ = messagesTable.update()
  } yield ()

  def update(): Future[Unit] = refresh.future()

  diskDiv.innerHTML = ""
  diskDiv.appendChild(diskTable.render)

  // do an initial refresh
  update()

  // start our demo...
  IntroJSHandle.start()
}