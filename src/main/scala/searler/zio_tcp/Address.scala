package searler.zio_tcp

import zio.ZIO
import zio.blocking.{Blocking, effectBlocking, effectBlockingIO}
import zio.stream.ZStream

import java.io.IOException
import java.net.InetAddress

/**
 * Module providing functionality related to InetAddress
 */
object Address {

  /**
   * Resolve a set of hostnames to ip addresses.
   *
   * Hostnames that cannot be resolved are silently ignored
   *
   * @param names Hostnames to be resolved
   * @param parallelism number of concurrent lookups
   * @return Map(hostname->address
   */
  def byName(names: Set[String], parallelism: Int = 1): ZIO[Blocking, Nothing, Map[String, InetAddress]] = for {
    mapping <- ZStream.fromIterable(names).mapMPar(parallelism)(name =>
      effectBlockingIO(name -> InetAddress.getByName(name)).either).
      collectRight.runCollect
  } yield mapping.iterator.toMap

  val localhost: ZIO[Blocking, IOException, InetAddress] = effectBlockingIO(InetAddress.getLocalHost)

}
