val dottyVersion = "3.0.0-M2"
val scala213Version = "2.13.4"

ThisBuild / scalaVersion := scala213Version
val zioVersion = "1.0.3"
val Http4sVersion = "0.21.13"
val circeVersion = "0.13.0"

/**
 * This doesn't work locally:
 * {{{
 *   sbt:riff> previewSite
 *   [info] SitePreviewPlugin server started on port 4000. Press any key to exit.
 *   [error] /usr/local/lib/node_modules/gitbook-cli/node_modules/npm/node_modules/graceful-fs/polyfills.js:287
 *   [error]       if (cb) cb.apply(this, arguments)
 *   [error]                  ^
 *   [error] TypeError: cb.apply is not a function
 *   [error]     at /usr/local/lib/node_modules/gitbook-cli/node_modules/npm/node_modules/graceful-fs/polyfills.js:287:18
 *   [error]     at FSReqCallback.oncomplete (fs.js:177:5)
 * }}}
 */
// enablePlugins(GitBookPlugin)

enablePlugins(SiteScaladocPlugin)

ghpagesNoJekyll := true

val ScalaTest = "org.scalatest" %% "scalatest" % "3.2.3" % "test"

git.remoteRepo := "git@github.com:{your username}/{your project}.git"
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
    libraryDependencies += (ScalaTest).withDottyCompat(scalaVersion.value),
    git.remoteRepo := "git@github.com:aaronp/riffd.git"
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

lazy val rest = project
  .in(file("./rest"))
  .dependsOn(riffJVM)
  .settings(
    libraryDependencies ++= List("io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-generic-extras" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion).map(_.withDottyCompat(scalaVersion.value)),
    libraryDependencies ++= List(
      "com.typesafe" % "config" % "1.4.0",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "dev.zio" %% "zio-interop-cats" % "2.2.0.1",
      "com.github.aaronp" %% "args4c" % "0.7.0",
      "com.github.aaronp" %% "eie" % "1.0.0",
      "org.http4s" %% "http4s-blaze-server" % Http4sVersion,
      "org.http4s" %% "http4s-blaze-client" % Http4sVersion,
      "org.http4s" %% "http4s-circe" % Http4sVersion,
      "org.http4s" %% "http4s-core" % Http4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sVersion,
      "org.http4s" %% "http4s-prometheus-metrics" % Http4sVersion,
      ScalaTest
    ).map(_.withDottyCompat(scalaVersion.value))
  )
