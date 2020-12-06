val dottyVersion = "3.0.0-M2"
val scala213Version = "2.13.4"

ThisBuild / scalaVersion := scala213Version

val zioVersion = "1.0.3"
val Http4sVersion = "0.21.13"
val circeVersion = "0.13.0"

lazy val riff = crossProject(JSPlatform, JVMPlatform)
  .in(file("."))
  .settings(
    name := "riff",
    version := "0.0.1",
    scalaVersion := scala213Version,
    crossScalaVersions := Seq(dottyVersion, scala213Version),
    scalacOptions ++= {
      if (isDotty.value) Seq("-source:3.0-migration")
      else Seq("-target:jvm-1.11")
    },
    javacOptions ++= Seq("-source", "11", "-target", "11"),
    libraryDependencies ++= List("io.circe" %%% "circe-generic" % circeVersion,
      "io.circe" %%% "circe-generic-extras" % circeVersion,
      "io.circe" %%% "circe-parser" % circeVersion).map(_.withDottyCompat(scalaVersion.value)),
    libraryDependencies ++= List(
      "dev.zio" %%% "zio" % zioVersion,
      "dev.zio" %%% "zio-test" % zioVersion % "test",
      "dev.zio" %%% "zio-test-sbt" % zioVersion % "test"
    ).map(_.withDottyCompat(scalaVersion.value)))
  .jvmSettings(
    name := "riffJVM",
    libraryDependencies += ("org.scalatest" %% "scalatest" % "3.2.3" % "test").withDottyCompat(scalaVersion.value)
  )
  .jsSettings(
    name := "riffJS",
    // Add JS-specific settings here
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= List(
      "com.lihaoyi" %%% "scalatags" % "0.9.2",
      "org.scala-js" %%% "scalajs-dom" % "1.1.0").map(_.withDottyCompat(scalaVersion.value))
  )

lazy val riffJVM = riff.jvm
lazy val riffJS = riff.js



lazy val docs = project
  .in(file("./docs"))
  .dependsOn(riffJVM)
  .enablePlugins(GitBookPlugin)


lazy val rest = project
  .in(file("./rest"))
  .dependsOn(riffJVM)
  .settings(
    libraryDependencies ++= List("io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-generic-extras" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion).map(_.withDottyCompat(scalaVersion.value)),
    libraryDependencies ++= List(
      "org.http4s" %% "http4s-circe" % Http4sVersion,
      "org.http4s" %% "http4s-core" % Http4sVersion,
    ).map(_.withDottyCompat(scalaVersion.value))
  )
