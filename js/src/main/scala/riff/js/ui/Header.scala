package riff.js.ui

import org.scalajs.dom.{Element, document}
import riff.Role.{Candidate, Follower, Leader}
import riff.{NodeId, RaftNodeState, Role}
import scalatags.JsDom.all.{canvas, _}

case class Header(nodeName: String) {

  private def value(): Element = document.createElement("dd")

  private def dt(label: String): Element = {
    val elm = document.createElement("dt")
    elm.innerText = label
    elm
  }

  val roleElm = dt("Role:")
  val ourNodeId = value()
  ourNodeId.innerText = nodeName

  val clusterElm = value()
  val termElm = value()
  val leaderElm = value()
  val canvasElm = canvas(id := "canvas", style := "display: inline-block;width:80px").render

  lazy val banner = div(style := "display: inline-block; vertical-align: top;")(
    div(style := "display: inline-block; padding:10px")(canvasElm),
    dl(style := "display: inline-block")(
      roleElm, ourNodeId,
      dt("Leader:"), leaderElm,
      dt("Term:"), termElm,
      dt("Cluster:"), clusterElm
    )).render

  def updateRole(newRole: Role) = {
    roleElm.innerText = newRole.name
    val bannerStyle = newRole match {
      case _: Leader => "background-color:red"
      case _: Candidate => "background-color:orange"
      case Follower => "background-color:green"
    }
    banner.style = bannerStyle
  }

  def updateFromState(state: RaftNodeState, currentPeers: Set[NodeId]): Unit = {
    ourNodeId.innerText = s"${state.ourNodeId}"
    termElm.innerText = s"${state.term}"

    def clusterDesc(nodes: Int) = nodes match {
      case 1 => ""
      case n => s" $n nodes"
    }

    updateRole(state.role)
    leaderElm.innerText = if (state.role.isLeader) "US!" else s" ${state.currentLeaderId.getOrElse("?")}"

    clusterElm.innerText = {
      state.role match {
        case Leader(view) =>
          val allPeers = currentPeers ++ view.keySet

          def fmt(peer: NodeId) = {
            val clusterSuffix = if (currentPeers.contains(peer)) "" else "?"
            val leaderSuffix = if (view.keySet.contains(peer)) "✅" else "❌"
            s"$peer$clusterSuffix$leaderSuffix"
          }

          s"${allPeers.toList.sorted.map(fmt).mkString(s" ${clusterDesc(allPeers.size)} : [", ",", "]")}"
        case _ =>
          s"${currentPeers.toList.sorted.mkString(s"${clusterDesc(currentPeers.size)} : [", ",", "]")}"
      }
    }
  }
}