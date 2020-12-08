import zio._
import zio.clock.Clock
import zio.console.Console
import zio.random.Random

package object riff {
  type NodeId = String
  type Term = Int
  type Disk = Has[Disk.Service]
  type Heartbeat = Has[Heartbeat.Service]
  type Cluster = Has[Cluster.Service]
  type Logging = Has[Logging.Service]

  type Env = Cluster with Disk with Logging with Heartbeat
  type FullEnv = Env with Clock with Console with Random

  /**
   * The general means to send some request to a raft node
   */
  type RemoteClient = Either[RiffRequest, RiffResponse] => Task[Unit]

  type HB = Fiber[Nothing, Unit]
}
