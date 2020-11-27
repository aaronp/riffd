package riff.js.ui

import io.circe.syntax._
import riff.json.RiffCodec._

object TestGen {

  def apply(deltas: Seq[Delta]): String = {
    if (deltas.isEmpty) {
      " // no messages specified"
    } else {
      val beforeState = Snapshot(deltas.head.from).asJson.spaces2
      val expectedState = Snapshot(deltas.last.to).asJson.spaces2
      val msgs = deltas.map(d => InputCodec(d.input).noSpaces)
      val tripleQuotes = "\"\"\""
      s"""
         |val before = $tripleQuotes${beforeState.asJson.noSpaces}$tripleQuotes
         |
         |val expected = $tripleQuotes${expectedState.asJson.spaces2}$tripleQuotes
         |
         |val messages = ${msgs.mkString(s"List(\n\t$tripleQuotes", s"$tripleQuotes,\n\t$tripleQuotes", "$tripleQuotes)\n")}
         |
         |verify(before, expected, messages)
         |
         |""".stripMargin
    }
  }
}
