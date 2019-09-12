name := "akka-streams-tcp-chat"

version := "1.0"

scalaVersion := "2.13.0"

lazy val akkaVersion = "2.5.25"

fork in run := true
run / connectInput := true

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "org.scodec" %% "scodec-core" % "1.11.4",
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.0.8" % Test
)
