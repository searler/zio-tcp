package searler.zio_tcp

import zio.ZIO
import zio.blocking.{Blocking, effectBlocking}
import zio.stream.ZStream

import java.net.InetAddress

object Address {

  def byName(names: Set[String], parallelism: Int = 1): ZIO[Blocking, Nothing, Map[String, InetAddress]] = for {
    mapping <- ZStream.fromIterable(names).mapMPar(parallelism)(name =>
      effectBlocking(name -> InetAddress.getByName(name)).either).
      collectRight.runCollect
  } yield mapping.iterator.toMap

}
