package riff.js.ui

import org.scalajs.dom.{Element, document}
import riff.Role.{Candidate, Follower, Leader}
import riff.{NodeId, RaftNodeState, Role}
import scalatags.JsDom.all.{canvas, _}

/**
 * The top 'header' block of the page which shows some log stats, etc
 *
 * @param nodeName
 */
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

  val forkMe = a(href := "https://github.com/aaronp/riffd")(
    img(attr("loading") := "lazy",
      width := "149",
      height := "149",
      src := "https://github.blog/wp-content/uploads/2008/12/forkme_right_red_aa0000.png?resize=149%2C149",
      style := "position:absolute;top:0px;right:0px",
      alt := "Fork me on GitHub",
      attr("data-recalc-dims") := "1"
    )).render

  lazy val banner = div(style := "display: inline-block; vertical-align: top;")(
    div(style := "display: inline-block; padding:10px")(canvasElm),
    dl(style := "display: inline-block", attr("data-title") := "You've started a cluster node!", attr("data-intro") := "This section displays the current Raft node state: our node ID, the leader ID, current term, our current role (follower, candidate or leader), and which nodes are in our cluster.")(
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