# zio-tcp

## Summary

The zio-tcp library implements for both client and server:
* non-blocking NIO2 backed TCP streams for pure [ZStream](https://zio.dev/docs/datatypes/stream/stream) implementations.
* Standard [message exchange patterns](https://en.wikipedia.org/wiki/Enterprise_Integration_Patterns)

Supporting functionality:
* Host name resolution

## Related projects

* Peer-to-peer clustering [library](https://github.com/searler/zio-peer)

## Comparisons

 [zio-nio](https://zio.github.io/zio-nio/) has the explicit goal of providing acccess to Java NIO 
 functionality via a ZIO API.

ZIO has an implementation for a TCP 
[server](https://javadoc.io/static/dev.zio/zio-streams_2.12/1.0.9/zio/stream/ZStream$.html#fromSocketServer(port:Int,host:Option[String]):zio.stream.ZStream[zio.blocking.Blocking,Throwable,ZStreamPlatformSpecificConstructors.this.Connection]), 
that is currently incomplete and has life cycle management that is not suitable for a long lived server instance. There
is no client implementation.
 

## Documentation

### Examples

Echo server 
```scala
import searler.zio_tcp.TCP
import zio._

object ServerExample extends App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val program = for {
      server <- TCP
        .fromSocketServer(8888) // standard server listening on 8888 and wildcard bound
        .mapMParUnordered(4)( //Execute up to 4 requests concurrently
          TCP.handlerServer( // standard MEP
            _ =>   // ignore the identity of the connecting client
              Predef.identity)) // echo the request
        .runDrain
    } yield ()

    program.exitCode
  }
}
```

Client that makes a fixed request

```scala
import searler.zio_tcp.TCP
import zio._

object ClientExample extends App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {

    val program = for {
      conn <- TCP.fromSocketClient(8888, "localhost"). // connect to server
        retry(Schedule.forever) // retry connection until successful
      receive <- TCP.requestChunk( // Send Chunk[Byte] and receive a Chunk[Byte]
        Chunk.fromArray("Request".getBytes()) // create request Chunk
      )(conn) // request via connection
      _ <- console.putStrLn(new String(receive.toArray))
    } yield ()

    program.exitCode
  }
}
```

See the test cases for further examples

## Releases
_still to be implemented_

## Planned enhancements

* TLS 

## Background
This repository captures functionality from ZIO pull requests that are not (yet) accepted:

https://github.com/zio/zio/pull/5176
https://github.com/zio/zio/pull/5186

The code was identical, modulo module name so there is no naming conflict.
The implementation has since been improved and additional functionality has been provided.


Copyright 2021 Richard Searle. All rights reserved.