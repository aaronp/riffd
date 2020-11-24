package riff.js.ui

import org.scalajs.dom.document
import scalatags.JsDom.all._
import zio._
import zio.duration._

import scala.scalajs.js.annotation.JSExportTopLevel

case class TimerTest(canvasId: String, controlsId: String) {

  import Main.implicits._

  val timer = Timer(canvasId).value()
  val reset = button()("Reset").render
  reset.onclick = e => {
    e.preventDefault()
    timer.reset().future()
  }
  val controls = document.getElementById(controlsId)
  controls.innerHTML = ""
  controls.appendChild(reset)

  val loop = timer.update().repeat(Schedule.spaced(100.millis))

  loop.future()
}
