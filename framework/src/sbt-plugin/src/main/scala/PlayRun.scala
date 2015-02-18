/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package play

import java.io.Closeable

import com.typesafe.sbt.web.SbtWeb
import sbt._
import Keys._
import play.PlayImport._
import PlayKeys._
import annotation.tailrec
import scala.collection.JavaConverters._
import java.net.URLClassLoader
import java.util.jar.JarFile
import com.typesafe.sbt.SbtNativePackager._
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.web.SbtWeb.autoImport._
import play.core.{ Build, BuildLink, BuildDocHandler }
import play.sbtplugin.Colors
import play.runsupport.{ AssetsClassLoader, FileWatchService, Reloader }
import play.runsupport.classloader._
import play.sbtplugin.run._

/**
 * Provides mechanisms for running a Play application in SBT
 */
trait PlayRun extends PlayInternalKeys {

  /**
   * Configuration for the Play docs application's dependencies. Used to build a classloader for
   * that application. Hidden so that it isn't exposed when the user application is published.
   */
  val DocsApplication = config("docs").hide

  val createURLClassLoader: ClassLoaderCreator = Reloader.createURLClassLoader
  val createDelegatedResourcesClassLoader: ClassLoaderCreator = Reloader.createDelegatedResourcesClassLoader

  val playDefaultRunTask = playRunTask(playRunHooks, playDependencyClasspath, playDependencyClassLoader,
    playReloaderClasspath, playReloaderClassLoader, playAssetsClassLoader)

  /**
   * This method is public API, used by sbt-echo, which is used by Activator:
   *
   * https://github.com/typesafehub/sbt-echo/blob/v0.1.3/play/src/main/scala-sbt-0.13/com/typesafe/sbt/echo/EchoPlaySpecific.scala#L20
   *
   * Do not change its signature without first consulting the Activator team.  Do not change its signature in a minor
   * release.
   */
  def playRunTask(runHooks: TaskKey[Seq[play.PlayRunHook]],
    dependencyClasspath: TaskKey[Classpath], dependencyClassLoader: TaskKey[ClassLoaderCreator],
    reloaderClasspath: TaskKey[Classpath], reloaderClassLoader: TaskKey[ClassLoaderCreator],
    assetsClassLoader: TaskKey[ClassLoader => ClassLoader]): Def.Initialize[InputTask[Unit]] = Def.inputTask {

    val args = Def.spaceDelimited().parsed

    val state = Keys.state.value
    val interaction = playInteractionMode.value

    val reloadCompile = () => PlayReload.compile(
      () => Project.runTask(playReload, state).map(_._2).get,
      () => Project.runTask(reloaderClasspath, state).map(_._2).get,
      () => Project.runTask(streamsManager, state).map(_._2).get.toEither.right.toOption
    )

    val runSbtTask: String => AnyRef = (task: String) => {
      val parser = Act.scopedKeyParser(state)
      val Right(sk) = complete.DefaultParsers.result(parser, task)
      val result = Project.runTask(sk.asInstanceOf[Def.ScopedKey[Task[AnyRef]]], state).map(_._2)
      result.flatMap(_.toEither.right.toOption).orNull
    }

    lazy val devModeServer = Reloader.startDevMode(
      runHooks.value,
      (javaOptions in Runtime).value,
      dependencyClasspath.value.files,
      dependencyClassLoader.value,
      reloadCompile,
      reloaderClassLoader.value,
      assetsClassLoader.value,
      playCommonClassloader.value,
      playMonitoredFiles.value,
      fileWatchService.value,
      (managedClasspath in DocsApplication).value.files,
      playDocsJar.value,
      playDefaultPort.value,
      baseDirectory.value,
      devSettings.value,
      args,
      runSbtTask
    )

    interaction match {
      case nonBlocking: PlayNonBlockingInteractionMode =>
        nonBlocking.start(devModeServer)
      case blocking =>
        devModeServer

        println()
        println(Colors.green("(Server started, use Ctrl+D to stop and go back to the console...)"))
        println()

        // If we have both Watched.Configuration and Watched.ContinuousState
        // attributes and if Watched.ContinuousState.count is 1 then we assume
        // we're in ~ run mode
        val maybeContinuous = for {
          watched <- state.get(Watched.Configuration)
          watchState <- state.get(Watched.ContinuousState)
          if watchState.count == 1
        } yield watched

        maybeContinuous match {
          case Some(watched) =>
            // ~ run mode
            interaction doWithoutEcho {
              twiddleRunMonitor(watched, state, devModeServer.buildLink, Some(WatchState.empty))
            }
          case None =>
            // run mode
            interaction.waitForCancel()
        }

        devModeServer.close()
        println()
    }
  }

  /**
   * Monitor changes in ~run mode.
   */
  @tailrec
  private def twiddleRunMonitor(watched: Watched, state: State, reloader: BuildLink, ws: Option[WatchState] = None): Unit = {
    val ContinuousState = AttributeKey[WatchState]("watch state", "Internal: tracks state for continuous execution.")
    def isEOF(c: Int): Boolean = c == 4

    @tailrec def shouldTerminate: Boolean = (System.in.available > 0) && (isEOF(System.in.read()) || shouldTerminate)

    val sourcesFinder = PathFinder { watched watchPaths state }
    val watchState = ws.getOrElse(state get ContinuousState getOrElse WatchState.empty)

    val (triggered, newWatchState, newState) =
      try {
        val (triggered, newWatchState) = SourceModificationWatch.watch(sourcesFinder, watched.pollInterval, watchState)(shouldTerminate)
        (triggered, newWatchState, state)
      } catch {
        case e: Exception =>
          val log = state.log
          log.error("Error occurred obtaining files to watch.  Terminating continuous execution...")
          (false, watchState, state.fail)
      }

    if (triggered) {
      //Then launch compile
      Project.synchronized {
        val start = System.currentTimeMillis
        Project.runTask(compile in Compile, newState).get._2.toEither.right.map { _ =>
          val duration = System.currentTimeMillis - start
          val formatted = duration match {
            case ms if ms < 1000 => ms + "ms"
            case seconds => (seconds / 1000) + "s"
          }
          println("[" + Colors.green("success") + "] Compiled in " + formatted)
        }
      }

      // Avoid launching too much compilation
      Thread.sleep(Watched.PollDelayMillis)

      // Call back myself
      twiddleRunMonitor(watched, newState, reloader, Some(newWatchState))
    } else {
      ()
    }
  }

  val playPrefixAndAssetsSetting = playPrefixAndAssets := {
    assetsPrefix.value -> (WebKeys.public in Assets).value
  }

  val playAllAssetsSetting = playAllAssets := Seq(playPrefixAndAssets.value)

  val playAssetsClassLoaderSetting = playAssetsClassLoader := { parent =>
    new AssetsClassLoader(parent, playAllAssets.value)
  }

  val playStartCommand = Command.args("start", "<port>") { (state: State, args: Seq[String]) =>

    val extracted = Project.extract(state)

    val interaction = extracted.get(playInteractionMode)
    // Parse HTTP port argument
    val (properties, httpPort, httpsPort) = Reloader.filterArgs(args, defaultHttpPort = extracted.get(playDefaultPort))
    require(httpPort.isDefined || httpsPort.isDefined, "You have to specify https.port when http.port is disabled")

    Project.runTask(stage, state).get._2.toEither match {
      case Left(_) =>
        println()
        println("Cannot start with errors.")
        println()
        state.fail
      case Right(_) =>
        val stagingBin = Some(extracted.get(stagingDirectory in Universal) / "bin" / extracted.get(normalizedName in Universal)).map {
          f =>
            if (System.getProperty("os.name").toLowerCase.contains("win")) f.getAbsolutePath + ".bat" else f.getAbsolutePath
        }.get
        val javaProductionOptions = Project.runTask(javaOptions in Production, state).get._2.toEither.right.getOrElse(Seq[String]())

        // Note that I'm unable to pass system properties along with properties... if I do then I receive:
        //  java.nio.charset.IllegalCharsetNameException: "UTF-8"
        // Things are working without passing system properties, and I'm unsure that they need to be passed explicitly. If def main(args: Array[String]){
        // problem occurs in this area then at least we know what to look at.
        val args = Seq(stagingBin) ++
          properties.map {
            case (key, value) => s"-D$key=$value"
          } ++
          javaProductionOptions ++
          Seq("-Dhttp.port=" + httpPort.getOrElse("disabled"))
        val builder = new java.lang.ProcessBuilder(args.asJava)
        new Thread {
          override def run() {
            System.exit(Process(builder).!)
          }
        }.start()

        println(Colors.green(
          """|
            |(Starting server. Type Ctrl+D to exit logs, the server will remain in background)
            | """.stripMargin))

        interaction.waitForCancel()

        println()

        state.copy(remainingCommands = Seq.empty)
    }

  }

}
