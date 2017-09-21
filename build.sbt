import java.io.File

import sbt._

val mainVersion = "v0.1"

val status = "prod"

val dynaMLVersion = settingKey[String]("The version of DynaML used.")

val dataDirectory = settingKey[File]("The directory holding the data files for running example scripts")

val dynamlGroupID = settingKey[String]("Group ID for DynaML dependency")

val dynamlArtifact = settingKey[String]("Artifact ID for DynaML dependency")

lazy val dynaMLSettings = if(status == "dev") {
  Seq(
    dynaMLVersion := "v1.5.2-beta.1",
    dynamlGroupID := "io.github.mandar2812",
    dynamlArtifact := "dynaml_2.11"
  )
} else {
  Seq(
    dynaMLVersion := "v1.5.1",
    dynamlGroupID := "com.github.transcendent-ai-labs.DynaML",
    dynamlArtifact := "dynaml_2.11"
  )
}


lazy val commonSettings = dynaMLSettings ++ Seq(
  name := "PlasmaML",
  organization := "io.github.mandar2812",
  version := mainVersion,
  scalaVersion in ThisBuild := "2.11.8",
  libraryDependencies in ThisBuild ++= Seq(
    "com.nativelibs4java" % "scalaxy-streams_2.11" % "0.3.4" % "provided",
    dynamlGroupID.value % dynamlArtifact.value % dynaMLVersion.value,
    "org.jsoup" % "jsoup" % "1.9.1",
    "joda-time" % "joda-time" % "2.9.3",
    "org.json4s" % "json4s-native_2.11" % "3.3.0",
    "com.typesafe.slick" %% "slick" % "3.1.1"
  )
)

resolvers in ThisBuild ++= Seq(
  "jitpack" at "https://jitpack.io",
  "jzy3d-releases" at "http://maven.jzy3d.org/releases",
  "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
  "BeDataDriven" at "https://nexus.bedatadriven.com/content/groups/public",
  Resolver.sonatypeRepo("public")
)

lazy val mag_core = (project in file("mag-core")).enablePlugins(JavaAppPackaging, BuildInfoPlugin)
  .settings(
    initialCommands in console :=
      """import io.github.mandar2812.PlasmaML._;"""+
        """import io.github.mandar2812.PlasmaML.cdf.CDFUtils;"""+
        """import scalaxy.streams.optimize;"""+
        """import io.github.mandar2812.dynaml.kernels._;"""+
        """import io.github.mandar2812.dynaml.DynaMLPipe;"""+
        """import com.quantifind.charts.Highcharts._;"""+
        """import breeze.linalg.DenseVector;""" ,
    scalacOptions ++= Seq("-optimise", "-Yclosure-elim", "-Yinline"))


lazy val omni =
  (project in file("omni")).enablePlugins(JavaAppPackaging, BuildInfoPlugin).settings(commonSettings: _*)
    .settings(
      initialCommands in console :=
        """import io.github.mandar2812.PlasmaML.omni._;"""+
          """import scalaxy.streams.optimize;"""+
          """import io.github.mandar2812.dynaml.kernels._;"""+
          """import io.github.mandar2812.dynaml.DynaMLPipe;"""+
          """import com.quantifind.charts.Highcharts._;"""+
          """import breeze.linalg.DenseVector;"""
    ).dependsOn(mag_core)

lazy val vanAllen =
  (project in file("vanAllen")).enablePlugins(JavaAppPackaging, BuildInfoPlugin)
    .settings(commonSettings: _*)
    .settings(
      initialCommands in console :=
        """import io.github.mandar2812.PlasmaML.vanAllen._;"""+
          """import org.json4s._;"""+
          """import org.json4s.jackson.JsonMethods._;"""+
          """import io.github.mandar2812.dynaml.kernels._;"""+
          """import io.github.mandar2812.dynaml.pipes._;"""+
          """import com.quantifind.charts.Highcharts._;"""+
          """import org.jsoup._;"""+
          """import breeze.linalg.{DenseMatrix, DenseVector};"""
    ).dependsOn(mag_core)

lazy val streamer =
  (project in file("streamer")).enablePlugins(JavaAppPackaging, BuildInfoPlugin)
    .settings(commonSettings: _*)
    .settings(
      initialCommands in console :=
        """import io.github.mandar2812.PlasmaML.streamer._;"""+
        """import io.github.mandar2812.dynaml.kernels._;"""+
          """import io.github.mandar2812.dynaml.DynaMLPipe;"""+
          """import com.quantifind.charts.Highcharts._;"""+
          """import breeze.linalg.DenseVector;"""
    ).dependsOn(mag_core)



lazy val PlasmaML = (project in file(".")).enablePlugins(JavaAppPackaging, BuildInfoPlugin)
  .settings(commonSettings: _*)
  .dependsOn(mag_core, omni, vanAllen, streamer).settings(
  name := "PlasmaML",
  version := mainVersion,
  fork in run := true,
  mainClass in Compile := Some("io.github.mandar2812.PlasmaML.PlasmaML"),
  buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
  buildInfoPackage := "io.github.mandar2812.PlasmaML",
  buildInfoUsePackageAsPath := true,
  mappings in Universal ++= Seq({
    // we are using the reference.conf as default application.conf
    // the user can override settings here
    val init = (resourceDirectory in Compile).value / "DynaMLInit.scala"
    init -> "conf/DynaMLInit.scala"
  }, {
    val banner = (resourceDirectory in Compile).value / "banner.txt"
    banner -> "conf/banner.txt"
  }),
  javaOptions in Universal ++= Seq(
    // -J params will be added as jvm parameters
    "-J-Xmx3048m",
    "-J-Xms64m"
  ),
  dataDirectory := new File("data/"),
  initialCommands in console :="""io.github.mandar2812.PlasmaML.PlasmaML.main(Array())""")
  .aggregate(mag_core, omni, vanAllen, streamer)
  .settings(aggregate in update := false)

