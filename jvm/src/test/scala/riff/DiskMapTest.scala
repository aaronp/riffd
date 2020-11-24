package riff

class DiskMapTest extends BaseTest with DiskTest {

  override def withDisk(callback: Disk.Service => Unit): Unit = {
    val service = Disk.inMemory("test").value
    callback(service)
  }
}
