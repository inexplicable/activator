/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator

import xsbti.{ AppMain, AppConfiguration }
import activator.properties.ActivatorProperties._
import java.io.File
import java.net.HttpURLConnection
import scala.util.control.NonFatal
import java.util.Properties
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset

/** Expose for SBT launcher support. */
class ActivatorLauncher extends AppMain {

  val currentLauncherGeneration = ACTIVATOR_LAUNCHER_GENERATION

  def run(configuration: AppConfiguration) =
    // TODO - Detect if we're running against a local project.
    try configuration.arguments match {
      case Array("ui") => RebootToUI(configuration, version = checkForUpdatedVersion.getOrElse(APP_VERSION))
      case Array("new") => Exit(ActivatorCli(configuration))
      case Array("shell") => RebootToSbt(configuration, useArguments = false)
      case _ if Sbt.looksLikeAProject(new File(".")) => RebootToSbt(configuration, useArguments = true)
      case _ => displayHelp(configuration)
    } catch {
      case e: Exception => generateErrorReport(e)
    }
  // Wrapper to return exit codes.
  case class Exit(val code: Int) extends xsbti.Exit

  def displayHelp(configuration: AppConfiguration) = {
    System.err.println(s"""| Did not detect an ${SCRIPT_NAME} project in this directory.
                          |
                          | There are three ways to run ${SCRIPT_NAME}:
                          |
                          | 1. Recommended: try `${SCRIPT_NAME} ui` to create a project in the UI
                          | 2. Use `${SCRIPT_NAME} new` to create a project on the command line
                          | 3. Load an existing project by re-running ${SCRIPT_NAME} in a project directory
                          |""".stripMargin)
    Exit(1)
  }

  def generateErrorReport(e: Exception) = {
    // TODO - Make a real error report.
    e.printStackTrace()
    Exit(2)
  }

  private def slurp(reader: BufferedReader): String = {
    val sb = new StringBuilder
    var next = reader.readLine()
    while (next ne null) {
      sb.append(next)
      next = reader.readLine()
    }
    sb.toString
  }

  val latestUrl = new java.net.URL(ACTIVATOR_LATEST_URL)

  def downloadLatestVersion(): Option[String] = {
    System.out.println(s"Checking for a newer version of Activator (current version ${APP_VERSION})...")
    try {
      val connection = latestUrl.openConnection() match {
        case c: HttpURLConnection => c
        case whatever =>
          throw new Exception(s"Unknown connection type: ${whatever.getClass.getName}")
      }
      // we don't want to wait too long
      val timeout = 4000 // milliseconds
      connection.setConnectTimeout(timeout)
      connection.setReadTimeout(timeout)
      connection.connect()

      val in = connection.getInputStream()
      val reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), Charset.forName("UTF-8")))

      val line = try {
        slurp(reader)
      } finally {
        reader.close()
      }

      // sue me, not worth a JSON library
      val versionRegex = """.*"version" *: *"([^"]+)".*""".r
      val versionOption = line match {
        case versionRegex(v) =>
          if (v != APP_VERSION)
            System.out.println(s"   ... found updated version of Activator ${v} (replacing ${APP_VERSION})")
          else
            System.out.println(s"   ... our current version ${APP_VERSION} looks like the latest.")
          Some(v)
        case other =>
          throw new Exception(s"JSON at ${latestUrl} doesn't seem to have the version in it: '${line}'")
      }

      versionOption flatMap { version =>
        val launcherGenerationRegex = """.*"launcherGeneration" *: *([0-9]+).*""".r
        val latestLauncherGeneration = line match {
          case launcherGenerationRegex(g) => g
          case other => 0 // typesafe.com didn't include launcherGeneration in its json for gen 0
        }
        if (currentLauncherGeneration == latestLauncherGeneration) {
          versionOption
        } else {
          System.out.println(s"   ... Please download a new Activator by hand at http://typesafe.com/ (the latest version ${version} isn't compatible with this launcher, generation ${currentLauncherGeneration} vs. ${latestLauncherGeneration}).")
          None
        }
      }
    } catch {
      case NonFatal(e) =>
        System.out.println(s"   ... failed to get latest version information: ${e.getClass.getName}: ${e.getMessage}")
        None
    }
  }

  def checkForUpdatedVersion(): Option[String] = {
    downloadLatestVersion() map { version =>
      if (version != APP_VERSION()) {
        val file = new File(ACTIVATOR_VERSION_FILE)
        try {
          if (file.getParentFile() != null)
            file.getParentFile().mkdirs()
          val props = new Properties()
          props.setProperty("activator.version", version)
          val tmpFile = new File(file.getPath() + ".tmp")
          val out = new FileOutputStream(tmpFile)
          try {
            props.store(out, s"Activator version downloaded from ${latestUrl}")
          } finally {
            out.flush()
            out.close()
          }
          sbt.IO.move(tmpFile, file)
          Some(version)
        } catch {
          case NonFatal(e) =>
            System.out.println(s"   ... failed to write ${file}: ${e.getMessage}")
            None
        }
      } else {
        None
      }
    } getOrElse None
  }
}

/**
 * If we're rebooting into a non-cross-versioned app, we can leave off the scala
 *  version declaration, and Ivy will figure it out for us.
 */
trait AutoScalaReboot extends xsbti.Reboot {
  def scalaVersion = null
}

// Wrapper to return the UI application.
case class RebootToUI(configuration: AppConfiguration, version: String = APP_VERSION) extends AutoScalaReboot {
  val arguments = Array.empty[String]
  val baseDirectory = configuration.baseDirectory
  val app = ApplicationID(
    groupID = configuration.provider.id.groupID,
    // TODO - Pull this string from somewhere else so it's only configured in the build?
    name = "activator-ui",
    version = version,
    mainClass = "activator.UIMain")
}

// Wrapper to reboot into SBT.
// TODO - See if we can just read the configuration from the boot properties of sbt itself...
// TODO - This doesn't handle sbt < 0.12
case class RebootToSbt(configuration: AppConfiguration, useArguments: Boolean = false) extends AutoScalaReboot {

  val arguments = if (useArguments) configuration.arguments else Array.empty[String]
  val baseDirectory = configuration.baseDirectory
  val app = ApplicationID(
    groupID = "org.scala-sbt",
    name = "sbt",
    // TODO - Pull sbt version from file...
    version = RebootToSbt.determineSbtVersion(baseDirectory),
    mainClass = "sbt.xMain",
    mainComponents = Array("xsbti", "extra"))
}
object RebootToSbt {
  def determineSbtVersion(baseDirectory: File): String = {
    try {
      val buildPropsFile = new java.io.File(baseDirectory, "project/build.properties")
      val props = new java.util.Properties
      sbt.IO.load(props, buildPropsFile)
      props.getProperty("sbt.version", SBT_DEFAULT_VERSION)
    } catch {
      case e: java.io.IOException =>
        // TODO - Should we error out here, or just default?  For now, just default....
        System.err.println("WARNING:  Could not read build.properties file.  Defaulting sbt version to " + SBT_DEFAULT_VERSION + ".  \n  Reason: " + e.getMessage)
        SBT_DEFAULT_VERSION
    }

  }
}
// Helper class to make using ApplicationID in xsbti easier.
case class ApplicationID(
  groupID: String,
  name: String,
  version: String,
  mainClass: String,
  mainComponents: Array[String] = Array("xsbti"),
  crossVersioned: Boolean = false,
  crossVersionedValue: xsbti.CrossValue = xsbti.CrossValue.Disabled,
  classpathExtra: Array[java.io.File] = Array.empty) extends xsbti.ApplicationID
