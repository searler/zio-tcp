package io.github.searler.zio_tcp

import zio.duration._
import zio.test.Assertion.{equalTo, isEmpty}
import zio.test._
import zio.test.environment.Live

import java.net.InetAddress

object AddressSpec extends DefaultRunnableSpec {
  override def aspects: List[TestAspectAtLeastR[Live]] = List(TestAspect.timeout(1.seconds))

  def spec: ZSpec[Environment, Failure] = suite("Address")(

    testM("localhost is loopback") {

      for {
        addresses <- Address.byName(Set("localhost"))
      } yield assert(addresses)(equalTo(Map("localhost" -> InetAddress.getLoopbackAddress)))
    },

    testM("::1 is v6 loopback") {

      for {
        addresses <- Address.byName(Set("::1"))
      } yield assert(addresses)(equalTo(Map("::1" -> InetAddress.getByName("0:0:0:0:0:0:0:1"))))
    }
    ,
    testM("Non existent") {

      for {
        addresses <- Address.byName(Set("nonexistent_really"))
      } yield assert(addresses)(isEmpty)
    },

    testM("localhost AND nonexistent is loopback") {

      for {
        addresses <- Address.byName(Set("localhost", "nonexistent_really"))
      } yield assert(addresses)(equalTo(Map("localhost" -> InetAddress.getLoopbackAddress)))
    }

  )
}
