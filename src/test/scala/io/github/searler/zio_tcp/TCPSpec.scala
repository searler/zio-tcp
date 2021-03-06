package io.github.searler.zio_tcp

import TCP._
import zio.blocking.{Blocking, effectBlockingIO}
import zio.duration._
import zio.stream.{Stream, Transducer, ZSink, ZStream, ZTransducer}
import zio.test.Assertion.equalTo
import zio.test.environment.Live
import zio.test._
import zio.{Chunk, Hub, Promise, Queue, Ref, Schedule, ZHub, ZIO}

import java.io.IOException
import java.net.{InetSocketAddress, SocketAddress}
import scala.util.Try

/**
 * Contains interesting examples of how the API can be applied
 */
object TCPSpec extends DefaultRunnableSpec {

  override def aspects: List[TestAspectAtLeastR[Live]] = List(TestAspect.timeout(4.seconds))


  def spec: ZSpec[Environment, Failure] = suite("ZStream JVM")(
    suite("socket")(

      testM(" server socketAddress") {
        val message = "XABCDEFGHIJKMNOP"
        for {
          address <- effectBlockingIO(new InetSocketAddress("localhost", 8873))
          _ <- runServer(address, TCP.handlerServer(_ => Predef.identity))

          receive <- requestChunk(8874, message)
        } yield assert(receive)(equalTo(message))

      },
      testM(" bind client 127.0.0.1") {
        val message = "XABCDEFGHIJKMNOP"
        for {
          //echo
          _ <- runServer(8875, TCP.handlerServer(_ => Predef.identity))

          receive <- requestChunk(8875, message, bind = Some("127.0.0.1"))
        } yield assert(receive)(equalTo(message))

      },
      testM(" bind client localhost") {
        val message = "XABCDEFGHIJKMNOP"
        for {
          //echo
          _ <- runServer(8874, TCP.handlerServer(_ => Predef.identity))

          receive <- requestChunk(8874, message, bind = Some("localhost"))
        } yield assert(receive)(equalTo(message))

      },
      testM("write large") {
        val message = "XABCDEFGHIJKMNOP" * 180000
        for {
          //echo
          _ <- runServer(8885, TCP.handlerServer(_ => Predef.identity))

          receive <- requestChunk(8885, message)
        } yield assert(receive)(equalTo(message))

      },
      testM("Server responds with length of request") {
        val message = "message"
        for {
          _ <- runServer(
            6867,
            TCP.handlerServerM(_ =>
              _.runCollect.map(_.size).map(length => ZStream.fromIterable(s"length: $length".getBytes()))
            )
          )

          receive <- requestChunk(6867, message)

        } yield assert(receive)(equalTo(s"length: ${message.length}"))
      },
      testM("Server echoes the request") {
        val message = "XABCDEFGHIJKMNOP"
        for {
          //echo
          _ <- runServer(8887, TCP.handlerServer(_ => Predef.identity))

          receive <- requestChunk(8887, message)
        } yield assert(receive)(equalTo(message))
      },
      testM("Server ignores the request, using fixed response") {
        for {
          server <-
            runServer(
              6877,
              TCP.handlerServerM(_ => _.runDrain >>> ZIO.succeed(ZStream.fromIterable(("Fixed").getBytes())))
            )

          receive <- requestChunk(6877, "message")

          _ <- server.interrupt
        } yield assert(receive)(equalTo("Fixed"))
      },
      testM("server responds with every request byte incremented by one") {
        val message = "012345678"
        for {
          //increment byte
          server <- runServer(8888, TCP.handlerServer(_ => _.map(b => (b + 1).toByte)))

          conn <- TCP.fromSocketClient(8888, "localhost").retry(Schedule.forever)
          stream <- TCP.requestStream(Chunk.fromArray(message.getBytes()))(conn)
          receive <- stream
            .transduce(ZTransducer.utf8Decode)
            .runCollect
            .map(_.mkString)

          _ <- server.interrupt
        } yield assert(receive)(equalTo("123456789"))
      },
      testM("Independent processing of request and response using bidi") {
        val message = "012345678"
        val command = "request"
        for {
          queue <- Queue.unbounded[Byte]

          server <- runServer(
            8889,
            TCP.bidi(
              _.run(ZSink.fromQueue(queue)),
              down => ZStream.fromIterable(message.getBytes).run(down).unit
            )
          )

          response <- requestChunk(8889, command)

          contents <- queue.takeAll
          request = new String(Chunk.fromIterable(contents).toArray)

          _ <- server.interrupt
        } yield assert(response)(equalTo(message)) && assert(request)(equalTo(command))

      },
      testM("Independent processing of request and response using bidiServer") {
        val message = "012345678"
        val command = "request"
        for {
          queue <- Queue.unbounded[Byte]

          server <- runServer(
            8899,
            TCP.bidiServer(_ => (
              _.run(ZSink.fromQueue(queue)).debug,
              down => ZStream.fromIterable(message.getBytes).run(down).unit
            )
            )
          )

          response <- requestChunk(8899, command)

          contents <- queue.takeAll
          request = new String(Chunk.fromIterable(contents).toArray)

          _ <- server.interrupt
        } yield assert(response)(equalTo(message)) && assert(request)(equalTo(command))

      },
      testM("Server maintains running count, incremented by client requests") {
        def incrementer(state: Ref[Int]): SocketAddress => Stream[IOException, Byte] => Stream[IOException, Byte] = { _ =>
          _.transduce(ZTransducer.utf8Decode)
            .transduce(ZTransducer.splitLines)
            .map(s => Try(s.toInt).getOrElse(0))
            .mapM(bump => state.update(_ + bump))
            .mapM(_ => state.get)
            .mapConcatChunk(i => Chunk.fromIterable((i.toString).getBytes))
        }

        val incrementOne = 12
        val incrementTwo = 123
        for {
          count <- Ref.make(0)

          server <- runServer(8881, TCP.handlerServer(incrementer(count)))

          responseOne <- requestChunk(8881, incrementOne.toString)

          responseTwo <- requestChunk(8881, incrementTwo.toString)

          _ <- server.interrupt
        } yield assert(responseOne.toInt)(equalTo(incrementOne)) && assert(responseTwo.toInt)(
          equalTo(incrementOne + incrementTwo)
        )

      },

      /**
       * Mocks a chat service that copies all input to each client;
       * records the connect/disconnect of each client and the bytes received from each.
       *
       * Note this test uses Gen within the test to generate test messages
       */
      testM("Record client connectivity with all interactions delivered via Hub subscriber") {
        def chat(hub: Hub[String])(addr: SocketAddress)(in: Stream[IOException, Byte]) = {
          def notify(tag: String) = ZStream((s"$tag ${addr.asInstanceOf[InetSocketAddress].getPort}\n"))

          Stream
            .fromHub(hub)
            .interruptWhen(
              (notify("start") ++ in.transduce(Transducer.usASCIIDecode) ++ notify("end")).run(ZSink.fromHub(hub))
            )
            .mapConcatChunk(s => Chunk.fromArray(s.getBytes()))
        }

        for {
          expectedStore <- Ref.make("")
          promise <- Promise.make[Nothing, Unit]
          hub <- ZHub.unbounded[String]

          server <- runServer(6887, TCP.handlerServer(chat(hub)))
          managed = ZStream.fromHubManaged(hub).tapM(_ => promise.succeed(()))
          recorderStream = ZStream.unwrapManaged(managed)
          recorder <- recorderStream.runCollect.fork
          _ <- promise.await

          messages: Seq[String] <- Gen.alphaNumericString.filter(_.nonEmpty).runCollect
          _ <- ZIO.foreach(messages) { message =>
            for {
              connEcho <- TCP.fromSocketClient(6887, "localhost").retry(Schedule.forever)
              echoAddress <- connEcho.localAddress
              portEcho = echoAddress.asInstanceOf[InetSocketAddress].getPort
              _ <- TCP.requestChunk(Chunk.fromArray(message.getBytes()))(connEcho)
              _ <- expectedStore.update(_ + s"start $portEcho\n${message}end $portEcho\n")
            } yield ()
          }

          _ <- hub.shutdown

          recorderExit <- recorder.join
          recorded = recorderExit.mkString

          expected <- expectedStore.get
          _ <- server.interrupt
        } yield assert(recorded)(equalTo(expected))

      }
    )
  )

  /**
   * Note mapMParUnordered is appropriate since each client connection is independent and has different lifetimes
   */
  private final def runServer(port: Int, f: Channel => ZIO[Blocking, IOException, Unit]) =
    TCP
      .fromSocketServer(port, noDelay = true)
      .mapMParUnordered(4)(f)
      .runDrain
      .fork

  private final def requestChunk(port: Int, request: String, bind: Option[String] = None) = for {
    conn <- TCP.fromSocketClient(port, "localhost", bind, true).retry(Schedule.forever)
    receive <- TCP.requestChunk(Chunk.fromArray(request.getBytes()))(conn)
  } yield new String(receive.toArray)

  private final def runServer(address: SocketAddress, f: Channel => ZIO[Blocking, IOException, Unit]) =
    TCP
      .fromSocketAddressServer(address, true)
      .mapMParUnordered(4)(f)
      .runDrain
      .fork

}