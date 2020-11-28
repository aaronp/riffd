package riff.js.ui

import org.scalajs.dom.raw.Element
import riff.js.ui.JSRuntime.implicits._
import riff.{Disk, DiskError, Offset, Record}
import scalatags.JsDom.all._
import zio.ZIO

import scala.util.Try

case class DiskTable(disk: Disk.Service)(onRefresh: => Unit) {

  private object Paging {
    val offset = input(`type` := "text", value := "").render

    offset.onkeyup = (e) => {
      for {
        _ <- Try(limit.value.toInt)
        _ <- Try(offset.value.toInt)
      } yield onRefresh
    }
    val limit = input(`type` := "text", value := "6").render
    limit.onkeyup = (e) => {
      for {
        _ <- Try(limit.value.toInt)
        _ <- Try(offset.value.toInt)
      } yield onRefresh
    }
    val render = span(
      div(
        span(span("Offset:"), offset)
      ),
      div(
        span(span("Limit:"), limit)
      ),
    ).render

    def currentOffset(): Option[Offset] = Try(offset.value.toInt).toOption.map(Offset.apply)

    def currentLimit() = Try(limit.value.toInt).getOrElse(0)
  }

  def asRow(record: Record) = {
    tr(
      td(record.offset.toString),
      td(record.term.toString),
      td(record.dataAsString)
    )
  }

  val headerRow = tr(td("Offset"), td("Term"), td("Data"))

  def refresh = {
    update.future()
  }

  val logDiv = h2("Log").render
  val update: ZIO[Any, DiskError, Unit] = {
    for {
      uncommitted <- disk.latestUncommitted()
      committed <- disk.latestCommitted()
      limit = Paging.currentLimit()
      from = Paging.currentOffset().getOrElse {
        Offset((uncommitted.offset.offset - limit + 1).max(0))
      }
      records <- disk.readUncommitted(from, limit)
      rows = records.map(asRow).reverse
    } yield {
      tableDiv.innerHTML = ""
      tableDiv.appendChild(table((headerRow +: rows): _*).render)
      logDiv.innerHTML = ""
      logDiv.innerText = s"Log: ${committed.offset} / ${uncommitted.offset}"
    }
  }

  val tableDiv = div().render
  val render = div(
    logDiv,
    tableDiv,
    div(Paging.render),
  ).render

}
