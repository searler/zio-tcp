scalaVersion := "2.13.7"

name := "zio-tcp"
organization := "io.github.searler"
version := "0.2.1-SNAPSHOT"

val zio_version ="2.0.0-RC1"

libraryDependencies += "dev.zio" %% "zio" % zio_version
libraryDependencies += "dev.zio" %% "zio-streams" % zio_version
libraryDependencies += "dev.zio" %% "zio-test"          % zio_version % "test"
libraryDependencies +=  "dev.zio" %% "zio-test-sbt"      % zio_version % "test"

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")