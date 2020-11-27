package riff

import java.util.concurrent.atomic.AtomicInteger

import riff.Input.UserInput
import zio._
import zio.console.{Console, putStrLn}
import zio.duration.Duration

/**
 * @param nodeRef the current state of this node
 */
final case class Raft(nodeRef: Ref[RaftNodeState],
                      heartbeat: Heartbeat.Service,
                      disk: Disk.Service,
                      cluster: Cluster.Service,
                      logger: Logging.Service,
                      inputQueue: Queue[Input]) {
  def push(input: Input): ZIO[Any, Nothing, Unit] = inputQueue.offer(input).unit

  def currentRole(): ZIO[Any, Nothing, Role] = nodeRef.get.map(_.role)

  def append(data: Array[Byte]): ZIO[Any, Nothing, Unit] = push(Input.Append(data))

  def append(data: String): ZIO[Any, Nothing, Unit] = append(data.getBytes("UTF-8"))

  def input(from: NodeId): RemoteClient = {
    case Left(request) => push(UserInput(from, request))
    case Right(response) => push(UserInput(from, response))
  }

  val dependencies = {
    UIO(cluster).toLayer ++
      UIO(disk).toLayer ++
      UIO(logger).toLayer ++
      UIO(heartbeat).toLayer
  }

  def applyInput(input: Input): ZIO[FullEnv, RaftNodeError, Unit] = {
    for {
      node <- nodeRef.get
      updated <- node.update(input)
      _ <- Logging.onUpdate(input, node, updated)
      _ <- nodeRef.set(updated)
    } yield ()
  }

  private val applyNextBlockingEnv: ZIO[FullEnv, RaftNodeError, Unit] = {
    for {
      _ <- putStrLn("inputQueue.take")
      input <- inputQueue.take // <-- pull an input to this node
      _ <- applyInput(input)
    } yield ()
  }
  val applyNextBlocking = applyNextBlockingEnv.provideCustomLayer(dependencies)

  val scheduleFollowerHB = Heartbeat.scheduleFollowerHeartbeat.provideCustomLayer(dependencies)

  val applyNextInputEnv: ZIO[FullEnv, RaftNodeError, Boolean] = {
    for {
      inputs <- inputQueue.takeAll // <-- pull an input to this node
      _ <- ZIO.foreach(inputs)(applyInput)
    } yield inputs.nonEmpty
  }
  val applyNextInput: ZIO[zio.ZEnv, Any, Boolean] = applyNextInputEnv.provideCustomLayer(dependencies)

  val run = scheduleFollowerHB *> applyNextInput.forever
}

object Raft {

  def defaultQueue: ZLayer[Console, Nothing, Has[Queue[Input]]] = Queue.bounded[Input](100).toLayer

  def make(disk: Disk.Service,
           nodeId: NodeId = nextNodeId(),
           raftCluster: ZIO[ZEnv, Nothing, Cluster.Service] = Cluster(),
           logger: ZIO[ZEnv, Nothing, Logging.Service] = Logging.StdOut(),
           newHeartbeat: ZIO[Has[Queue[Input]], Nothing, Heartbeat.Service] = Heartbeat(),
           clusterRetrySchedule: Schedule[Any, Any, Duration] = Schedule.exponential(Duration.fromMillis(2000)),
           maxRecordsToSend: Int = 100,
           queueLayer: ZLayer[Console, Nothing, Has[Queue[Input]]] = defaultQueue,
           clusterSize: Option[Int] = None
          ): ZIO[zio.ZEnv, NoSuchElementException, Raft] = {

    def newNode(commitIndex: LogCoords, initialIterm: Term): RaftNodeState = {
      RaftNodeState(
        nodeId,
        initialIterm,
        commitIndex.offset,
        commitIndex.offset,
        maxRecordsToSend,
        Role.Follower,
        None,
        clusterRetrySchedule,
        clusterSize)
    }

    val newNodeIO: ZIO[Has[Queue[Input]] with zio.ZEnv, NoSuchElementException, Raft] = for {
      hb <- newHeartbeat
      cluster <- raftCluster
      (latest, term) <- disk.latestCommitted().zip(disk.currentTerm()).orDie
      log <- logger
      nodeRef <- Ref.make(newNode(latest, term))
      queue <- ZIO.service[Queue[Input]]
    } yield Raft(nodeRef, hb, disk, cluster, log, queue)
    newNodeIO.provideCustomLayer(queueLayer)
  }

  private val nodeIdCounter = new AtomicInteger(0)

  private def nextNodeId() = s"node-${nodeIdCounter.incrementAndGet()}"

}