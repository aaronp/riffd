package riff.js.ui

import org.scalajs.dom.document

import scala.scalajs.js

object IntroJSHandle extends js.Object {
  private def introJs(): IntroJSStartable = js.Dynamic.global.introJs().asInstanceOf[IntroJSStartable]

  val IntroQueryKey = "intro"
  private val ShowIntro = s".*$IntroQueryKey=(.*)".r

  def showStart(queryString: String) = {
    queryString match {
      case ShowIntro(theRest) => theRest.toLowerCase.startsWith("true")
      case _ => true
    }
  }

  def start() = {
    val showIntro = showStart(document.location.search)
    if (showIntro) {
      introJs().start()
    } else {
      println(s"Not showing intro: ${document.location.search}")
    }
  }
}

@js.native
trait IntroJSStartable extends js.Object {
  def start(): Unit = js.native
}
