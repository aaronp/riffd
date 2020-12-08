package riff.rest

import args4c.implicits._
import zio._
import zio.console.putStrLn
import zio.interop.catz._

/**
 * A REST application which will drive
 */
object Main extends CatsApp {

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    val config = args.toArray.asConfig()

    import args4c.implicits._


    for {
      _ <- putStrLn("⭐⭐ Starting Rest Service ⭐⭐")
      _ <- putStrLn(config.getConfig("riff").summaryEntries().map("\triff." + _).mkString("\n"))
      _ <- putStrLn(s"PID:${ProcessHandle.current().pid()}")
      _ <- putStrLn("")
      restServer <- RiffRaftRest(config)
      exitCode <- restServer.serve
    } yield exitCode
  }
}
