package riff.rest

import args4c.implicits._
import zio._
import zio.interop.catz._

/**
 * A REST application which will drive
 */
object Main extends CatsApp {

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    val config = args.toArray.asConfig().getConfig("riff")
    for {
      restServer <- RiffRaftRest(config)
      exitCode <- restServer.serve
    } yield exitCode
  }
}
