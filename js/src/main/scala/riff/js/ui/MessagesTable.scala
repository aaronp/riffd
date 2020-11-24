package riff.js.ui

import org.scalajs.dom.document
import riff.RiffRequest.{AppendEntries, RequestVote}
import riff.RiffResponse.{AppendEntriesResponse, RequestVoteResponse}
import riff.js.Dialog
import riff.{Input, RaftNodeState}
import scalatags.JsDom.all._

import scala.util.Try

/**
 *
 * Show: {{{
 *
 *  | Msg Type | from | to | request | response | Node Term | Leader Id | Last Applied | Last Commit |
 * }}}
 *
 */
class MessagesTable {
  val DeltaLimit = 200

  object headers {
    val At = "At"
    val Term = "Term"
    val Leader = "Leader"
    val Commit = "Commit"
    val Applied = "Applied"
    val Role = "Role"
    val Event = "Event"
    val From = "From"
    val Message = "Message"
  }

  private val columns = {
    import headers._
    List(
      Term,
      Leader,
      Commit,
      Applied,
      Role,
      Event,
      From,
      Message,
    )
  }

  def headerRow = tr(columns.map(v => td(v)): _*)

  case class Row(values: Map[String, String]) {
    def render = {
      val cells = columns.map { k =>
        val text: String = values.get(k).getOrElse("-")
        td(text)
      }
      tr(cells: _*)
    }
  }

  object Row {
    def apply(kv: (String, String)*) = new Row(kv.toMap)
  }

  private var deltas = Vector[Delta]()

  private def rows(records: Seq[Delta]) = {
    records.zipWithIndex.flatMap {
      case (Delta(input, from, to), 0) => List(asRow(from), asRow(input), asRow(to))
      case (Delta(input, _, to), _) => List(asRow(input), asRow(to))
    }
  }

  def asRow(state: RaftNodeState): Row = {
    Row(
      headers.Term -> state.term.toString,
      headers.Leader -> state.currentLeaderId.getOrElse("?"),
      headers.Commit -> state.commitIndex.offset.toString,
      headers.Role -> state.role.name,
      headers.Applied -> state.lastApplied.offset.toString
    )
  }

  def asRow(input: Input): Row = {
    input match {
      case Input.HeartbeatTimeout(Some(id)) => Row(headers.Event -> s"$id HB Timeout")
      case Input.HeartbeatTimeout(None) => Row(headers.Event -> "Follower HB Timeout")
      case Input.UserInput(from, Left(request: AppendEntries)) =>
        import request._
        Row(
          headers.Event -> "Append",
          headers.From -> from,
          headers.Message -> s"term:$term, leader:$leaderId, previous:$previous, leaderCommit:$leaderCommit, entries:${entries.size}",
        )
      case Input.UserInput(from, Left(request: RequestVote)) =>
        import request._
        Row(
          headers.Event -> "Request Vote",
          headers.From -> from,
          headers.Message -> s"term:$term, candidate:$candidateId, latestLog:$latestLog",
        )
      case Input.UserInput(from, Right(response: AppendEntriesResponse)) =>
        import response._
        Row(
          headers.Event -> "Append Response",
          headers.From -> from,
          headers.Message -> s"term:$term, success:$success, matchIndex:$matchIndex",
        )
      case Input.UserInput(from, Right(response: RequestVoteResponse)) =>
        import response._
        Row(
          headers.Event -> "Vote Response",
          headers.From -> from,
          headers.Message -> s"term:$term, granted:$granted",
        )
      case Input.Append(data) =>
        Row(
          headers.Event -> "User Append",
          headers.Message -> new String(data, "UTF-8")
        )
    }
  }

  def onInput(input: Input, from: RaftNodeState, to: RaftNodeState): Unit = {
    synchronized {
      deltas = (Delta(input, from, to) +: deltas).take(DeltaLimit)
    }
    update()
  }


  def update(): Unit = {
    val view: Seq[Delta] = {
      val from: Int = Paging.currentOffset()
      val limit: Int = Paging.currentLimit()
      deltas.drop(from).take(limit)
    }
    val all = rows(view).map(_.render)
    val trs = headerRow +: all

    val generateTest = {
      val buttonHint =
        s"""The "Create Test Code" button exports the shown messages as code block for a unit test
           |
           |This test code can then be copy/pasted into a test for reproduction/debugging --
           |An easy way to create test scenarios just by clicking through this UI!
           |""".stripMargin
      button("Create Test Code for Messages", attr("data-title") := "Can you reproduce it?", attr("data-intro") := buttonHint).render
    }
    generateTest.disabled = view.isEmpty
    generateTest.onclick = (e) => {
      e.preventDefault()
      testDialog.innerHTML = ""

      val testGen = TestGen(view.reverse)
      //      val copyText: Element = document.createElement("p")
      //      copyText.select()
      //      copyText.setSelectionRange(0, 99999)
      //      document.execCommand("copy")

      val link = a(href := "https://github.com/aaronp/riffd/blob/master/jvm/src/test/scala/riff/UserScenarioTest.scala")("jvm/src/test/scala/riff/UserScenarioTest.scala").render
      testDialog.appendChild(h2("copy/paste this test code into ", link).render)
      testDialog.appendChild(pre(testGen).render)
      testDialog.asInstanceOf[Dialog].showModal()
    }
    tableDiv.innerHTML = ""

    val hasData = view.nonEmpty
    val showData = !Paging.canShow || hasData
    Paging.toggle(showData)

    if (true || showData) {
      tableDiv.appendChild(div(
        h2("Messages"),
        div(generateTest),
        table(trs: _*).render,
      ).render)
    }
  }

  private object Paging {
    val offset = input(`type` := "text", `class` := "input-field", value := "0").render

    offset.onkeyup = _ => {
      for {
        _ <- Try(limit.value.toInt)
        _ <- Try(offset.value.toInt)
      } yield update()
    }
    val limit = input(`type` := "text", `class` := "input-field", value := "10").render
    limit.onkeyup = _ => {
      for {
        _ <- Try(limit.value.toInt)
        _ <- Try(offset.value.toInt)
      } yield update()
    }

    def toggle(visible: Boolean) = {
      val disp = if (visible) "block" else "none"
      render.style.display = disp
    }

    val render = div(`class` := "form-style")(
      label(`for` := offset.id)(
        span("Offset:"), offset
      ),
      label(`for` := limit.id)(
        span("Limit:"), limit
      )
    ).render

    def canShow() = currentOffset == 0 && currentLimit > 0

    def currentOffset(): Int = Try(offset.value.toInt).getOrElse(0)

    def currentLimit(): Int = Try(limit.value.toInt).getOrElse(0)
  }


  val testDialog = document.createElement("dialog")
  testDialog.setAttribute("id", "testDialog")
  val tableDiv = div().render
  val render = span(
    tableDiv,
    div(Paging.render),
    testDialog,
  ).render

}