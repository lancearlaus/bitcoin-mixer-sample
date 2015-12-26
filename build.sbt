name := "bitcoin-mixer-sample"

organization := "com.lancearlaus"

version := "0.1"

scalaVersion := "2.11.7"

scalacOptions ++= Seq("-deprecation", "-feature")

libraryDependencies ++= Seq(
  "com.typesafe.akka"      %% "akka-agent"                         % "2.4.1",
  "com.typesafe.akka"      %% "akka-stream-experimental"           % "2.0-M2",
  "com.typesafe.akka"      %% "akka-http-experimental"             % "2.0-M2",
  "com.typesafe.akka"      %% "akka-http-spray-json-experimental"  % "2.0-M2",

  "org.scalatest"     %% "scalatest"                          % "2.2.1" % "test",
  "com.typesafe.akka" %% "akka-stream-testkit-experimental"   % "2.0-M2"   % "test",
  "com.typesafe.akka" %% "akka-http-testkit-experimental"     % "2.0-M2"   % "test"
)

homepage := Some(url("https://github.com/lancearlaus/bitcoin-mixer-sample"))

licenses := Seq("Apache 2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

mainClass in (Compile, run) := Some("Main")