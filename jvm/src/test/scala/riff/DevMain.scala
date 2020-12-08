package riff

import java.nio.file.{Files, Paths}

import riff.jvm.NioDisk
import zio.{ExitCode, URIO}

object DevMain extends zio.App {

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val disk = NioDisk(Paths.get("./target/disk").ensuring(p => Files.exists(p) || Files.exists(Files.createDirectories(p))))
    Raft(disk).run.exitCode
  }
}
