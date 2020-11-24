package riff.js.ui

import io.circe.syntax._
import riff.json.RiffCodec._

/**
 * Formats the message deltas into a test case
 */
object TestGen {

  def apply(deltas: Seq[Delta]): String = {
    if (deltas.isEmpty) {
      " // no messages specified"
    } else {
      val beforeState = Snapshot(deltas.head.from)
      val expectedState = Snapshot(deltas.last.to)
      val tripleQuotes = "\"\"\""
      val msgs = deltas.map{d =>
        s"""
          |$tripleQuotes${InputCodec(d.input).noSpaces}$tripleQuotes""".stripMargin
      }
      s""""update the internal state" in {
         |
         |  val before = $tripleQuotes${beforeState.asJson.noSpaces}$tripleQuotes
         |
         |  val expected = $tripleQuotes${expectedState.asJson.spaces2}$tripleQuotes
         |
         |  val messages = ${msgs.mkString(s"List(", ",", ")")}
         |
         |  verify(before, expected, messages)
         |}
         |""".stripMargin
    }
  }
}
