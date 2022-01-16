import xerial.sbt.Sonatype._

scalaVersion := "2.13.7"

name := "zio-tcp"
organization := "io.github.searler"
version := "0.3.1"

val zio_version ="2.0.0-RC1"

libraryDependencies += "dev.zio" %% "zio" % zio_version
libraryDependencies += "dev.zio" %% "zio-streams" % zio_version
libraryDependencies += "dev.zio" %% "zio-test"          % zio_version % "test"
libraryDependencies +=  "dev.zio" %% "zio-test-sbt"      % zio_version % "test"

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

publishMavenStyle := true
publishTo := sonatypePublishToBundle.value
licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
sonatypeProfileName    := "io.github.searler"
sonatypeProjectHosting := Some(GitHubHosting("searler", "zio-tcp", "eggsearle@verizon.net"))
sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
