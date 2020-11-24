package riff.js

import org.scalajs.dom.MessageEvent

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native
@JSGlobal
class BroadcastChannel(var name: String = js.native) extends js.Object {
  var onmessage: js.Function1[MessageEvent, _] = js.native
  var onmessageerror: js.Function1[MessageEvent, _] = js.native
  def postMessage(message: js.Any): Unit = js.native
  def close(): Unit = js.native
}