package searler.zio_tcp


import zio.stream.{Sink, Stream, ZSink, ZStream}
import zio.{Chunk, IO, UIO, ZIO, ZManaged}

import java.io.IOException
import java.net.{InetSocketAddress, SocketAddress, StandardSocketOptions}
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, ClosedChannelException, CompletionHandler}
import java.nio.{Buffer, ByteBuffer}
import scala.util.Try
import zio.ZIO.{ attemptBlocking, attemptBlockingIO }

object TCP {


  /**
   * Create a stream of accepted connection from server socket
   * Emit socket `Channel` from which you can read / write and ensure it is closed after it is used
   */

  def fromSocketServer(
                        port: Int,
                        host: Option[String] = None,
                        noDelay: Boolean = false
                      ): ZStream[Any, IOException, Channel] = create(host.fold(new InetSocketAddress(port))(new InetSocketAddress(_, port)), noDelay)

  def fromSocketAddressServer(
                               address: SocketAddress,
                               noDelay: Boolean = false
                             ): ZStream[Any, IOException, Channel] = create(address, noDelay)

  private def create(
                      address: => SocketAddress,
                      noDelay: Boolean
                    ): ZStream[Any, IOException, Channel] =
    (for {

      server <- ZStream.managed(ZManaged.fromAutoCloseable(attemptBlocking {
        AsynchronousServerSocketChannel
          .open()
          .bind(address)
      }))

      conn <- ZStream.repeatZIO {
        IO.async[IOException, Channel] { callback =>
          server.accept(
            null,
            new CompletionHandler[AsynchronousSocketChannel, Void]() {
              self =>
              override def completed(socket: AsynchronousSocketChannel, attachment: Void): Unit =
                callback(ZIO.succeed(new Channel(socket.setOption(StandardSocketOptions.TCP_NODELAY, noDelay.asInstanceOf[java.lang.Boolean]))))

              override def failed(exc: Throwable, attachment: Void): Unit = callback(ZIO.fail(exc).refineToOrDie[IOException])
            }
          )
        }
      }
    } yield conn).refineToOrDie[IOException]

  /**
   * Accepted connection made to a specific channel `AsynchronousServerSocketChannel`
   */
  class Channel(socket: AsynchronousSocketChannel) {

    /**
     * The remote address
     */
    def remoteAddress: IO[IOException, SocketAddress] = IO
      .attempt(socket.getRemoteAddress)
      .refineToOrDie[IOException]

    /**
     * The local address
     */
    def localAddress: IO[IOException, SocketAddress] = IO
      .attempt(socket.getLocalAddress)
      .refineToOrDie[IOException]

    /**
     * Read the entire `AsynchronousSocketChannel` by emitting a `Chunk[Byte]`
     */
    val read: Stream[IOException, Byte] =
      ZStream.unfoldChunkZIO(0) {
        case -1 => ZIO.succeed(Option.empty)
        case _ =>
          val buff = ByteBuffer.allocate(ZStream.DefaultChunkSize)

          IO.async[IOException, Option[(Chunk[Byte], Int)]] { callback =>
            socket.read(
              buff,
              null,
              new CompletionHandler[Integer, Void] {
                override def completed(bytesRead: Integer, attachment: Void): Unit = {
                  (buff: Buffer).flip()
                  callback(ZIO.succeed(Option(Chunk.fromByteBuffer(buff) -> bytesRead.toInt)))
                }

                override def failed(error: Throwable, attachment: Void): Unit = error match {
                  case _: ClosedChannelException => callback(ZIO.succeed(Option.empty))
                  case _ => callback(ZIO.fail(error).refineToOrDie[IOException])
                }
              }
            )
          }.refineToOrDie[IOException]
      }

    /**
     * Write the entire Chuck[Byte] to the socket channel.
     *
     * The sink will yield the count of bytes written.
     */
    val write: Sink[IOException, Byte, Nothing, Int] =
      ZSink.foldLeftChunksZIO(0) {
        case (nbBytesWritten, c) => {
          ZIO.debug(s"write $c")
          val buffer = ByteBuffer.wrap(c.toArray)
          IO.async[IOException, Int] { callback =>
            var totalWritten = 0
            socket.write(
              buffer,
              buffer,
              new CompletionHandler[Integer, ByteBuffer] {
                override def completed(result: Integer, attachment: ByteBuffer): Unit = {
                  totalWritten += result
                  if (attachment.hasRemaining)
                    socket.write(attachment, attachment, this)
                  else
                    callback(ZIO.succeed(nbBytesWritten + totalWritten))
                }

                override def failed(error: Throwable, attachment: ByteBuffer): Unit = error match {
                  case _: ClosedChannelException => callback(ZIO.succeed(0))
                  case _ => callback(ZIO.fail(error).refineToOrDie[IOException])
                }
              }
            )
          }
        }
      }

    /**
     * Close the underlying socket
     */
    def close(): UIO[Unit] = ZIO.succeed(Try(socket.close()))

    /**
     * Close only the write, so the remote end will see EOF
     */
    def closeWrite():IO[IOException,Unit] = ZIO.attempt(socket.shutdownOutput()).unit.refineToOrDie[IOException]

  }


  /**
   * Create a Connection from client socket
   */
  final def fromSocketClient(
                              port: Int,
                              host: String,
                              bind: Option[String] = None,
                              noDelay: Boolean = false
                            ): ZIO[Any, IOException, Channel] = for {
    address <- attemptBlockingIO(new InetSocketAddress(host, port))
    bound <- attemptBlockingIO(bind.map(h => new InetSocketAddress(h, 0)))
    conn <- fromSocketAddressClient(address, bound, noDelay)
  } yield conn

  final def fromSocketAddressClient(
                                     address: SocketAddress,
                                     bind: Option[SocketAddress] = None,
                                     noDelay: Boolean = false
                                   ): ZIO[Any, IOException, Channel] =
    for {
      socket <- attemptBlockingIO(AsynchronousSocketChannel.open().bind(bind.orNull).setOption(StandardSocketOptions.TCP_NODELAY, noDelay.asInstanceOf[java.lang.Boolean]))

      conn <-
        IO.async[IOException, Channel] { callback =>
          socket.connect(
            address,
            socket,
            new CompletionHandler[Void, AsynchronousSocketChannel]() {
              self =>
              override def completed(ignored: Void, attachment: AsynchronousSocketChannel): Unit =
                callback(ZIO.succeed(new Channel(attachment)))

              override def failed(exc: Throwable, attachment: AsynchronousSocketChannel): Unit =
                callback(ZIO.fail(exc).refineToOrDie[IOException])
            }
          )
        }

    } yield conn

  // --- Server only ---

  /**
   * The request stream of bytes from the client is effectfully mapped to a  response stream of bytes
   *
   * The mapping is determined by the client remote address
   */
  final def handlerServer(
                           f: SocketAddress => Stream[IOException, Byte] => Stream[IOException, Byte]
                         )(c: Channel): ZIO[Any, IOException, Unit] =
    (for {
      remote <- c.remoteAddress
      _ <- f(remote)(c.read).run(c.write)
    } yield ())
      .ensuring(c.close())

  /**
   * The request stream of bytes from the client is effectfully mapped to a  response stream of bytes
   *
   * The mapping is determined by the client remote address
   */
  final def handlerServerZIO(
                            f: SocketAddress => Stream[IOException, Byte] => IO[IOException, Stream[IOException, Byte]]
                          )(c: Channel): ZIO[Any, IOException, Unit] =
    (for {
      remote <- c.remoteAddress
      end <- f(remote)(c.read)
      _ <- end.run(c.write)
    } yield ())
      .ensuring(c.close())

  /**
   * Independently connect the request and response streams from/to the client.
   *
   * The stream and sink are determined by the client remote address
   *
   * Socket is closed when both streams complete
   */
  final def bidiServer(
                        f: SocketAddress => (
                          Stream[IOException, Byte] => ZIO[Any, IOException, Unit],
                            Sink[IOException, Byte, IOException, Int] => ZIO[Any, IOException, Unit]
                          )
                      )(c: Channel): ZIO[Any, IOException, Unit] = for {
    remote <- c.remoteAddress
    (up, down) = f(remote)
    result <- bidi(up, down)(c)
  } yield result

  // --- client or server

  /**
   * Independently connect the request and response streams from/to the client.
   *
   * Socket is closed when both streams complete
   */
  final def bidi(
                  request: Stream[IOException, Byte] => ZIO[Any, IOException, Unit],
                  response: Sink[IOException, Byte, IOException, Int] => ZIO[Any, IOException, Unit]
                )(c: Channel): ZIO[Any, IOException, Unit] = (for {
    forked <- response(c.write).fork
    _ <- request(c.read)
    _ <- forked.await
  } yield ())
    .ensuring(c.close())

  //  -- client only ---

  /**
   * Send request to distant end and return complete response.
   *
   * Corresponds to HTTP 1.0 interaction.
   */
  final def requestChunk(request: Chunk[Byte])(c: Channel): ZIO[Any, IOException, Chunk[Byte]] =
    (for {
      _ <- (ZStream.fromChunk(request).run(c.write) *> c.closeWrite()).fork
      response <- c.read.runCollect
    } yield response).ensuring(c.close())

  /**
   * Send request to distant end and return streamed response
   *
   * Corresponds to Server Sent Event
   */
  final def requestStream(request: Chunk[Byte])(c: Channel): ZIO[Any, Nothing, ZStream[Any, IOException, Byte]] = for {
    _ <- (ZStream.fromChunk(request).run(c.write) *> c.closeWrite()).fork
  } yield c.read.ensuring(c.close())


}
