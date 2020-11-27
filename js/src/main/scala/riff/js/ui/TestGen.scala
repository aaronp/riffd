package riff.js.ui

import io.circe.syntax._
import riff.json.RiffCodec._

object TestGen {

  def apply(deltas: Seq[Delta]) = {
    if (deltas.isEmpty) {
      " // no messages specified"
    } else {
      val beforeState = deltas.head.from
      val expectedState = deltas.last.to
      deltas.map(_.input.asJson.noSpaces)
    }
  }
}
