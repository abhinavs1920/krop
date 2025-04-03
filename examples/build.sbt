scalaVersion := "3.6.3"

lazy val examples = project
  .in(file("."))
  .settings(
    name := "krop-examples",
    scalaVersion := "3.6.3",
    libraryDependencies ++= Seq(
      "org.creativescala" %% "krop-core" % "0.9.0",
      "org.creativescala" %% "krop-sqlite" % "0.9.0",
      "com.lihaoyi" %% "scalatags" % "0.13.1",
      "ch.qos.logback" % "logback-classic" % "1.5.18" % Runtime
    ),
    // Set Krop into development mode
    run / javaOptions += "-Dkrop.mode=development",
    run / fork := true,
    Compile / run / mainClass := Some("examples.runHtmx")
  )
