package riff.js.ui

import org.scalajs.dom.Element
import riff.{NodeId, RaftNodeState}
import riff.Role.{Candidate, Follower, Leader}
import scalatags.JsDom.all.{canvas, dd, dt}
import scalatags.JsDom.all._

case class Header(nodeName: String) {

  private def value(): Element = dd().render.asInstanceOf[Element]

  val role = dt("Role:").render.asInstanceOf[Element]
  val ourNodeId = value()
  ourNodeId.innerText = nodeName

  val cluster = value()
  val term = value()
  val leader = value()

  val canvasElm = canvas(id := "canvas", style := "display: inline-block;width:80px").render

  def updateRole(name: String) = {
    role.innerText = name
  }

  def updateFromState(state: RaftNodeState, currentPeers: Set[NodeId]): Unit = {
    ourNodeId.innerText = s"${state.ourNodeId}"
    term.innerText = s"${state.term}"

    def clusterDesc(nodes: Int) = nodes match {
      case 1 => ""
      case n => s" $n nodes"
    }


    val bannerStyle = state.role match {
      case _: Leader => "background-color:red"
      case _: Candidate => "background-color:orange"
      case Follower => "background-color:green"
    }
    leader.innerText = if (state.role.isLeader) "" else s" Leader: ${state.currentLeaderId.getOrElse("?")}"
    banner.style = bannerStyle

    cluster.innerText = {
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

  //
  //  val bannerDiv = div(style := "display: inline-block; vertical-align: top;")(
  //    div(style := "display: inline-block")(canvasElm),
  //    div(id := "RoleAndCluster", style := "display: inline-block")(
  //      span(style := blockStyle)(b(style := blockStyle)(roleDiv), ":", ourNodeDiv),
  //      span(style := blockStyle)(span(style := blockStyle)(b("Cluster:")), clusterDiv)),
  //    div(id := "TermAndLeader", style := "display: inline-block")(
  //      span(style := blockStyle)(b("Term:"), termDiv),
  //      span(style := blockStyle)(leaderDiv)),
  //  ).render

  val banner = div(style := "display: inline-block; vertical-align: top;")(
    div(style := "display: inline-block")(canvasElm),
    dl(
      role, ourNodeId,
      dt("Leader:"), leader,
      dt("Term:"), term,
      dt("Cluster:"), cluster
    )).render
}