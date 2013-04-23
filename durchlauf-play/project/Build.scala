import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {

  val appName = "durchlauf-play"
  val appVersion = "1.0-SNAPSHOT"

  val appDependencies = Seq(
    "org.apache.httpcomponents" % "httpasyncclient" % "4.0-beta3"
  )


  val main = play.Project(appName, appVersion, appDependencies).settings(
    // Add your own project settings here      
  )

}
