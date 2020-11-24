package riff.js

import java.util.Base64

import org.scalajs.dom
import org.scalajs.dom.raw.Storage
import riff._
import zio.{IO, UIO}


/**
 * A local-storage-based disk (backed by a [[DiskMap]] logic for the distributed log)
 *
 * @param prefix the namespace against which the entries should be stored
 */
final case class JSDisk(prefix: String, storage: Storage = dom.window.sessionStorage) extends DiskMap(prefix) {
  private def bytesToString(value: Array[Byte]) = Base64.getEncoder.encodeToString(value)

  private def stringToBytes(base64: String) = Base64.getDecoder.decode(base64)

  override def upsert(key: String, value: Array[Byte]): UIO[Unit] = {
    IO.effectTotal(storage.setItem(key, bytesToString(value)))
  }

  override def readData(key: String): UIO[Option[Array[Byte]]] = {
    IO.effectTotal(Option(storage.getItem(key)).map(stringToBytes))
  }

  override def deleteKeys(keys: Set[String]): UIO[Unit] = UIO.foreach(keys) { key =>
    UIO.effectTotal(storage.removeItem(key))
  }.unit
}