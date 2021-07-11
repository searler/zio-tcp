package searler.zio_tcp

import zio.blocking.{Blocking, effectBlocking, effectBlockingIO}
import zio.stream.{Sink, Stream, ZSink, ZStream}
import zio.{Chunk, IO, UIO, ZIO, ZManaged}

import java.io.IOException
import java.net.{InetSocketAddress, SocketAddress}
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel, ClosedChannelException, CompletionHandler}
import java.nio.{Buffer, ByteBuffer}

object TCP {


  /**
   * Create a stream of accepted connection from server socket
   * Emit socket `Channel` from which you can read / write and ensure it is closed after it is used
   */
  def fromSocketServer(
                       port: Int,
                       host: Option[String] = None
                      ): ZStream[Blocking, Throwable, Channel] =
    for {
      server <- ZStream.managed(ZManaged.fromAutoCloseable(effectBlocking {
        AsynchronousServerSocketChannel
          .open()
          .bind(
            host.fold(new InetSocketAddress(port))(new InetSocketAddress(_, port))
          )
      }))

      conn <- ZStream.repeatEffect {
        IO.effectAsync[Throwable, Channel] { callback =>
          server.accept(
            null,
            new CompletionHandler[AsynchronousSocketChannel, Void]() {
              self =>
              override def completed(socket: AsynchronousSocketChannel, attachment: Void): Unit =
                callback(ZIO.succeed(new Channel(socket)))

              override def failed(exc: Throwable, attachment: Void): Unit = callback(ZIO.fail(exc))
            }
          )
        }
      }
    } yield conn

  /**
   * Accepted connection made to a specific channel `AsynchronousServerSocketChannel`
   */
  class Channel(socket: AsynchronousSocketChannel) {

    /**
     * The remote address
     */
    def remoteAddress: IO[IOException, SocketAddress] = IO
      .effect(socket.getRemoteAddress)
      .refineToOrDie[IOException]

    /**
     * The local address
     */
    def localAddress: IO[IOException, SocketAddress] = IO
      .effect(socket.getLocalAddress)
      .refineToOrDie[IOException]

    /**
     * Read the entire `AsynchronousSocketChannel` by emitting a `Chunk[Byte]`
     */
    val read: Stream[Throwable, Byte] =
      ZStream.unfoldChunkM(0) {
        case -1 => ZIO.succeed(Option.empty)
        case _ =>
          val buff = ByteBuffer.allocate(ZStream.DefaultChunkSize)

          IO.effectAsync[Throwable, Option[(Chunk[Byte], Int)]] { callback =>
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
                  case _ => callback(ZIO.fail(error))
                }
              }
            )
          }
      }

    /**
     * Write the entire Chuck[Byte] to the socket channel.
     *
     * The sink will yield the count of bytes written.
     */
    val write: Sink[Throwable, Byte, Nothing, Int] =
      ZSink.foldLeftChunksM(0) {
        case (nbBytesWritten, c) => {
          val buffer = ByteBuffer.wrap(c.toArray)
          IO.effectAsync[Throwable, Int] { callback =>
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
                  case _ => callback(ZIO.fail(error))
                }
              }
            )
          }
        }
      }

    /**
     * Close the underlying socket
     */
    def close(): UIO[Unit] = ZIO.effectTotal(socket.close())

    /**
     * Close only the write, so the remote end will see EOF
     */
    def closeWrite(): UIO[Unit] = ZIO.effectTotal(socket.shutdownOutput()).unit
  }


  /**
   * Create a Connection from client socket
   */
  final def fromSocketClient(
                              port: Int,
                              host: String
                            ): ZIO[Blocking, Throwable, Channel] =
    for {
      socket <- effectBlockingIO(AsynchronousSocketChannel.open())

      conn <-
        IO.effectAsync[Throwable, Channel] { callback =>
          socket.connect(
            new InetSocketAddress(host, port),
            socket,
            new CompletionHandler[Void, AsynchronousSocketChannel]() {
              self =>
              override def completed(ignored: Void, attachment: AsynchronousSocketChannel): Unit =
                callback(ZIO.succeed(new Channel(attachment)))

              override def failed(exc: Throwable, attachment: AsynchronousSocketChannel): Unit =
                callback(ZIO.fail(exc))
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
                           f: SocketAddress => Stream[Throwable, Byte] => Stream[Throwable, Byte]
                         )(c: Channel): ZIO[Blocking, Throwable, Unit] =
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
  final def handlerServerM(
                            f: SocketAddress => Stream[Throwable, Byte] => IO[Throwable, Stream[Throwable, Byte]]
                          )(c: Channel): ZIO[Blocking, Throwable, Unit] =
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
                          Stream[Throwable, Byte] => ZIO[Any, Throwable, Unit],
                            Sink[Throwable, Byte, Throwable, Int] => ZIO[Any, Throwable, Unit]
                          )
                      )(c: Channel): ZIO[Any, Throwable, Unit] = for {
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
                  request: Stream[Throwable, Byte] => ZIO[Any, Throwable, Unit],
                  response: Sink[Throwable, Byte, Throwable, Int] => ZIO[Any, Throwable, Unit]
                )(c: Channel): ZIO[Any, Throwable, Unit] = (for {
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
  final def requestChunk(request: Chunk[Byte])(c: Channel): ZIO[Blocking, Throwable, Chunk[Byte]] =
    (for {
      _ <- (ZStream.fromChunk(request).run(c.write) *> c.closeWrite()).fork
      response <- c.read.runCollect
    } yield response).ensuring(c.close())

  /**
   * Send request to distant end and return streamed response
   *
   * Corresponds to Server Sent Event
   */
  final def requestStream(request: Chunk[Byte])(c: Channel): ZIO[Any, Nothing, ZStream[Any, Throwable, Byte]] = for {
    _ <- (ZStream.fromChunk(request).run(c.write) *> c.closeWrite()).fork
  } yield c.read.ensuring(c.close())


}
