package riff.jvm

import riff._

class NioDiskTest extends DiskTest {
  override def withDisk(test: Disk.Service => Unit): Unit = withTmpDir { dir =>
    test(NioDisk(dir))
  }
}
