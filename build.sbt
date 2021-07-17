


scalaVersion := "2.13.1"


name := "zio-tcp"
organization := "searler"
version := "0.2"



val zio_version ="1.0.9"

libraryDependencies += "dev.zio" %% "zio" % zio_version
libraryDependencies += "dev.zio" %% "zio-streams" % zio_version
libraryDependencies += "dev.zio" %% "zio-test"          % zio_version % "test"
libraryDependencies +=  "dev.zio" %% "zio-test-sbt"      % zio_version % "test"

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

githubOwner := "searler"
githubRepository := "zio-tcp"
githubTokenSource := TokenSource.GitConfig("github.token")