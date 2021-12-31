package searler.zio_tcp

import zio.ZIO

import zio.stream.ZStream

import java.io.IOException
import java.net.InetAddress
import zio.ZIO._

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
  def byName(names: Set[String], parallelism: Int = 1): ZIO[Any, Nothing, Map[String, InetAddress]] = for {
    mapping <- ZStream.fromIterable(names).mapZIOPar(parallelism)(name =>
      attemptBlockingIO(name -> InetAddress.getByName(name)).either).
      collectRight.runCollect
  } yield mapping.iterator.toMap

  val localhost: ZIO[Any, IOException, InetAddress] = attemptBlockingIO(InetAddress.getLocalHost)

}
