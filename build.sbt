val dottyVersion = "3.0.0-M2"
val scala213Version = "2.13.4"

ThisBuild / scalaVersion := scala213Version
val zioVersion = "1.0.3"

val Http4sVersion = "0.21.13"

val circeVersion = "0.13.0"


lazy val rest = project
  .in(file("./rest"))
  .settings(
    libraryDependencies ++= List("io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-generic-extras" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion),
    libraryDependencies ++= List(
      "org.http4s" %% "http4s-circe" % Http4sVersion,
      "org.http4s" %% "http4s-core" % Http4sVersion,
    )
  )

lazy val riff = crossProject(JSPlatform, JVMPlatform)
  .in(file("."))
  .settings(
    name := "riff",
    version := "0.0.1",
    //scalaVersion := dottyVersion,
    scalaVersion := scala213Version,
    crossScalaVersions := Seq(scala213Version, dottyVersion),
    scalacOptions += "-target:jvm-1.11",
    javacOptions ++= Seq("-source", "11", "-target", "11"),
    libraryDependencies ++= List("io.circe" %%% "circe-generic" % circeVersion,
      "io.circe" %%% "circe-generic-extras" % circeVersion,
      "io.circe" %%% "circe-parser" % circeVersion),
    libraryDependencies ++= List(
      "dev.zio" %%% "zio" % zioVersion,
      "dev.zio" %%% "zio-test" % zioVersion % "test",
      "dev.zio" %%% "zio-test-sbt" % zioVersion % "test"
    ).map(_.withDottyCompat(scalaVersion.value)))
  .jvmSettings(
    name := "riffJVM",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.2" % "test"
  )
  .jsSettings(
    name := "riffJS",
    // Add JS-specific settings here
        scalaJSUseMainModuleInitializer := true,
    libraryDependencies += "com.lihaoyi" %%% "scalatags" % "0.8.5",
    libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "1.1.0"
  )
