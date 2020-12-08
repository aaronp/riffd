package riff.js

import riff._
import zio.{Has, Queue, UIO, ZIO}

/**
 */
object RiffJS {
  def apply(disk: Disk.Service,
            cluster: ClusterLocal,
            logger: Logging.Service,
            newHeartbeat: ZIO[Has[Queue[Input]], Nothing, Heartbeat.Service]): ZIO[zio.ZEnv, NoSuchElementException, Raft] = {
    Raft(
      disk = disk,
      nodeId = cluster.ourNodeId,
      raftCluster = UIO(cluster),
      newHeartbeat = newHeartbeat,
      logger = UIO(logger),
      clusterSize = Option(2))
  }
}
