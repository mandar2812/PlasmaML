import sbt._

val dynaMLVersion = settingKey[String]("The version of DynaML used.")

lazy val commonSettings = Seq(
  name := "PlasmaML",
  organization := "io.github.mandar2812",
  version := "0.1.0",
  scalaVersion in ThisBuild := "2.11.8",
  dynaMLVersion := "v1.4.2-beta.1",
  libraryDependencies in ThisBuild ++= Seq(
    "com.nativelibs4java" % "scalaxy-streams_2.11" % "0.3.4" % "provided",
    "io.github.mandar2812" % "dynaml_2.11" % dynaMLVersion.value,
    "org.jsoup" % "jsoup" % "1.9.1",
    "joda-time" % "joda-time" % "2.9.3",
    "org.json4s" % "json4s-native_2.11" % "3.3.0",
    "com.typesafe.slick" %% "slick" % "3.1.1",
    "org.slf4j" % "slf4j-nop" % "1.6.4"
  )
)

resolvers in ThisBuild ++= Seq(
  "jitpack" at "https://jitpack.io",
  "jzy3d-releases" at "http://maven.jzy3d.org/releases",
  "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
  "BeDataDriven" at "https://nexus.bedatadriven.com/content/groups/public",
  Resolver.sonatypeRepo("public")
)

lazy val root = (project in file(".")).settings(commonSettings: _*)
  .aggregate(core, omni, vanAllen)
  .settings(aggregate in update := false)

lazy val core = (project in file("core")).settings(
  initialCommands in console :=
    """import io.github.mandar2812.PlasmaML._;"""+
    """import io.github.mandar2812.PlasmaML.cdf.CDFUtils""",
  scalacOptions ++= Seq("-optimise", "-Yclosure-elim", "-Yinline"))

lazy val omni =
  (project in file("omni")).settings(commonSettings: _*)
    .settings(
      initialCommands in console :=
        """import io.github.mandar2812.PlasmaML.omni._;"""+
          """import scalaxy.streams.optimize;"""+
          """import io.github.mandar2812.dynaml.kernels._;"""+
          """import io.github.mandar2812.dynaml.DynaMLPipe;"""+
          """import com.quantifind.charts.Highcharts._;"""+
          """import breeze.linalg.DenseVector;""" +
          """io.github.mandar2812.dynaml.DynaML.main(Array())"""
    ).dependsOn(core)

lazy val vanAllen =
  (project in file("vanAllen")).settings(commonSettings: _*)
    .settings(
      initialCommands in console :=
        """import io.github.mandar2812.PlasmaML.vanAllen._;"""+
          """import org.json4s._;"""+
          """import org.json4s.jackson.JsonMethods._;"""+
          """import io.github.mandar2812.dynaml.kernels._;"""+
          """import io.github.mandar2812.dynaml.pipes._;"""+
          """import com.quantifind.charts.Highcharts._;"""+
          """import org.jsoup._;"""+
          """import breeze.linalg.{DenseMatrix, DenseVector};""" +
          """io.github.mandar2812.dynaml.DynaML.main(Array())"""
    ).dependsOn(core)

lazy val streamer =
  (project in file("streamer")).settings(commonSettings: _*)
    .settings(
      initialCommands in console :=
        """import io.github.mandar2812.PlasmaML.streamer._;"""+
        """import io.github.mandar2812.dynaml.kernels._;"""+
          """import io.github.mandar2812.dynaml.DynaMLPipe;"""+
          """import com.quantifind.charts.Highcharts._;"""+
          """import breeze.linalg.DenseVector;""" +
          """io.github.mandar2812.dynaml.DynaML.main(Array())"""
    ).dependsOn(core)