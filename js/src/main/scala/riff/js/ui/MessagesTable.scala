package riff.js.ui

import org.scalajs.dom.document
import riff.Request.{AppendEntries, RequestVote}
import riff.Response.{AppendEntriesResponse, RequestVoteResponse}
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
      deltas = Delta(input, from, to) +: deltas
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


    val generateTest = button("Create Test").render
    generateTest.onclick = (e) => {
      e.preventDefault()
      testDialog.innerHTML = ""
      testDialog.appendChild(pre(TestGen(view.reverse)).render)
      testDialog.asInstanceOf[Dialog].showModal()
    }
    tableDiv.innerHTML = ""
    tableDiv.appendChild(div(
      h4("Messages"),
      div(generateTest),
      table(trs: _*).render,
    ).render)
  }

  private object Paging {
    val offset = input(`type` := "text", value := "1").render

    offset.onkeyup = (e) => {
      for {
        _ <- Try(limit.value.toInt)
        _ <- Try(offset.value.toInt)
      } yield update()
    }
    val limit = input(`type` := "text", value := "10").render
    limit.onkeyup = (e) => {
      for {
        _ <- Try(limit.value.toInt)
        _ <- Try(offset.value.toInt)
      } yield update()
    }
    val render = span(
      div(
        span(span("From:"), offset)
      ),
      div(
        span(span("Limit:"), limit)
      ),
    ).render

    def currentOffset() = Try(offset.value.toInt).getOrElse(0)

    def currentLimit() = Try(limit.value.toInt).getOrElse(0)
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