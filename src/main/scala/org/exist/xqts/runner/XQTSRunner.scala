/*
 * Copyright (C) 2018  The eXist Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.exist.xqts.runner

import java.io.IOException
import java.net.{URI, URL}
import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import grizzled.slf4j.Logger
import org.exist.xqts.runner.Checksum.SHA256
import org.exist.xqts.runner.XQTSRunner.CmdConfig
import org.exist.xqts.runner.XQTSRunnerActor.RunXQTS
import scalaz._
import syntax.either._
import XQTSRunner._
import org.exist.xqts.runner.XQTSParserActor.Feature
import org.exist.xqts.runner.XQTSParserActor.Feature._
import org.exist.xqts.runner.XQTSParserActor.Spec
import org.exist.xqts.runner.XQTSParserActor.Spec._
import org.exist.xqts.runner.XQTSParserActor.XmlVersion
import org.exist.xqts.runner.XQTSParserActor.XmlVersion._
import org.exist.xqts.runner.XQTSParserActor.XsdVersion
import org.exist.xqts.runner.XQTSParserActor.XsdVersion._
import org.exist.xqts.runner.qt3.XQTS3CatalogParserActor

import scala.language.postfixOps
import scala.util.Try

/**
  * This is the Entry Point to the command line XQTS Runner application.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
object XQTSRunner {

  /**
    * Container for command line options.
    */
  private case class CmdConfig(
      xqtsVersion: XQTSVersion = XQTS_3_1,
      localDir: Option[Path] = None,
      enableFeatures : Seq[Feature] = Seq.empty,
      disableFeatures : Seq[Feature] = Seq.empty,
      enableSpecs: Seq[Spec] = Seq.empty,
      disableSpecs: Seq[Spec] = Seq.empty,
      enableXmlVersions: Seq[XmlVersion] = Seq.empty,
      disableXmlVersions: Seq[XmlVersion] = Seq.empty,
      enableXsdVersions: Seq[XsdVersion] = Seq.empty,
      disableXsdVersions: Seq[XsdVersion] = Seq.empty,
      testSetPattern: Option[Pattern] = None,
      testSets: Seq[String] = Seq.empty,
      testCases: Seq[String] = Seq.empty,
      excludeTestSets: Seq[String] = Seq.empty,
      excludeTestCases: Seq[String] = Seq.empty,
      outputDir: Option[Path] = None
  )

  /*
    Exit codes of the application.
   */
  private val EXIT_CODE_OK = 0
  private val EXIT_CODE_INVALID_ARGS = 1
  private val EXIT_CODE_NO_XQTS = 2
  private val EXIT_CODE_EXIST_START_FAILED = 3

  /**
    * XQTS Features which are enabled by default.
    */
  private val DEFAULT_FEATURES = Seq(
    CollectionStability,
    DirectoryAsCollectionUri,
    HigherOrderFunctions,
    ModuleImport,
    NamespaceAxis,
    Serialization,
    StaticTyping,
    TypedData,
    XPath_1_0_Compatibility
  )

  /**
    * XQTS Specs which are enabled by default.
    */
  private val DEFAULT_SPECS = Seq(
    XP10,
    XP20,
    XP30,
    XP31,
    XQ10,
    XQ30,
    XQ31,
    XT30
  )

  /**
    * XQTS XML Versions which are enabled by default.
    */
  private val DEFAULT_XML_VERSIONS = Seq(
    XML10,
    XML10_4thOrEarlier,
    XML10_5thOrLater,
    XML11
  )

  /**
    * XQTS XSD Versions which are enabled by default.
    */
  private val DEFAULT_XSD_VERSIONS = Seq.empty

  // Converters used for parsing the command line arguments.
  private implicit val xqtsVersionRead: scopt.Read[XQTSVersion] = scopt.Read.reads(XQTSVersion.from(_))
  private implicit val pathRead: scopt.Read[Path] = scopt.Read.reads(Paths.get(_))
  private implicit val patternRead: scopt.Read[Pattern] = scopt.Read.reads(Pattern.compile(_))
  private implicit val featureRead: scopt.Read[Feature] = scopt.Read.reads(Feature.fromXqtsName(_))
  private implicit val specRead: scopt.Read[Spec] = scopt.Read.reads(Spec.withName(_))
  private implicit val xmlVersionRead: scopt.Read[XmlVersion] = scopt.Read.reads(XmlVersion.withName(_))
  private implicit val xsdVersionRead: scopt.Read[XsdVersion] = scopt.Read.reads(XsdVersion.withName(_))
  private def fileExists(f: Path) : Either[String, Unit] = if(Files.exists(f)) { Right(()) } else Left(s"${f.toAbsolutePath} does not exist")
  private def isDirOrNotExists(f: Path) : Either[String, Unit] = if(Files.isDirectory(f) || !Files.exists(f)) { Right(()) } else { Left(s"${f.toAbsolutePath} is not a writable directory")}


  /**
    * MAIN
    *
    * @param args the command line arguments
    */
  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[CmdConfig]("xqtsrunner") {
      head("xqtsrunner", "1.0")

      opt[XQTSVersion]('x', "xqts-version")
        .text("The version of XQTS to run")
        .validate(x => if (x == XQTS_3_1 || x == XQTS_HEAD) success else failure("only version 3.1, or HEAD is currently supported"))
        .action((x, c) => c.copy(xqtsVersion = x))

      opt[Path]('l', "local-dir")
        .text ("A directory where downloaded copies of XQTS are cached. If not provided then 'work' is assumed")
        .validate(fileExists)
        .action((x, c) => c.copy(localDir = Some(x)))

      opt[Pattern]("test-set-pattern").abbr("tsptn")
        .text("A regular expression that matches one or more test set names to run. The default is to run all of them")
        .action((x,c) => c.copy(testSetPattern = Some(x)))

      opt[Seq[String]]("test-set").abbr("ts")
          .valueName("<test-set-1>,<test-set-2>...")
          .text("The name of one or more test sets to run. The default is to run all of them")
          .action((x,c) => c.copy(testSets = x))

      opt[Seq[String]]("test-case").abbr("tc")
        .valueName("<test-case-1>,<test-case-2>...")
        .text("The name of one or more test cases (within the test sets) to run. The default is to run all of them")
        .action((x,c) => c.copy(testCases = x))

      opt[Seq[String]]("exclude-test-set").abbr("xts")
        .valueName("<test-set-1>,<test-set-2>...")
        .text("The name of one or more test sets to exclude from the run. The default is to run all of them")
        .action((x,c) => c.copy(excludeTestSets = x))

      opt[Seq[String]]("exclude-test-case").abbr("xtc")
        .valueName("<test-case-1>,<test-case-2>...")
        .text("The name of one or more test cases to exclude (within the test sets) from the run. The default is to run all of them")
        .action((x,c) => c.copy(excludeTestCases = x))

      opt[Seq[Feature]]("enable-feature").abbr("ef")
          .valueName("<feature-1>,<feature-2>...")
          .text("The name of one or more XQTS features to enable.")
          .action((x, c) => c.copy(enableFeatures = x))

      opt[Seq[Feature]]("disable-feature").abbr("df")
        .valueName("<feature-1>,<feature-2>...")
        .text("The name of one or more XQTS features to disable.")
        .action((x, c) => c.copy(disableFeatures = x))

      opt[Seq[Spec]]("enable-spec").abbr("es")
        .valueName("<feature-1>,<feature-2>...")
        .text("The name of one or more XQTS specifications to enable.")
        .action((x, c) => c.copy(enableSpecs = x))

      opt[Seq[Spec]]("disable-spec").abbr("ds")
        .valueName("<spec-1>,<spec-2>...")
        .text("The name of one or more XQTS specifications to disable.")
        .action((x, c) => c.copy(disableSpecs = x))

      opt[Seq[XmlVersion]]("enable-xml-version").abbr("exml")
        .valueName("<xml-version-1>,<xml-version-2>...")
        .text("The name of one or more XQTS XML versions to enable.")
        .action((x, c) => c.copy(enableXmlVersions = x))

      opt[Seq[XmlVersion]]("disable-xml-version").abbr("dxml")
        .valueName("<xml-version-1>,<xml-version-2>...")
        .text("The name of one or more XQTS XML versions to disable.")
        .action((x, c) => c.copy(disableXmlVersions = x))

      opt[Seq[XsdVersion]]("enable-xsd-version").abbr("exsd")
        .valueName("<xsd-version-1>,<xsd-version-2>...")
        .text("The name of one or more XQTS XSD versions to enable.")
        .action((x, c) => c.copy(enableXsdVersions = x))

      opt[Seq[XsdVersion]]("disable-xsd-version").abbr("dxsd")
        .valueName("<xml-version-1>,<xml-version-2>...")
        .text("The name of one or more XQTS XSD versions to disable.")
        .action((x, c) => c.copy(disableXsdVersions = x))

      opt[Path]('o', "output-dir")
          .text("A directory where the results of the XQTS are written. If not provided then 'target' is assumed")
          .validate(isDirOrNotExists)
          .action((x, c) => c.copy(outputDir = Some(x)))

      note("The test-set-pattern argument takes preference over the test-set argument")
      note(s"By default the following features are enabled: ${DEFAULT_FEATURES.mkString(", ")}")
      note(s"By default the following specifications are enabled: ${DEFAULT_SPECS.mkString(", ")}")
      note(s"By default the following XML versions are enabled: ${DEFAULT_XML_VERSIONS.mkString(", ")}")
      note(s"By default the following XSD versions are enabled: ${DEFAULT_XSD_VERSIONS.mkString(", ")}")
      note(s"On success, the application exits with the exit code $EXIT_CODE_OK, otherwise: $EXIT_CODE_INVALID_ARGS for invalid arguments, $EXIT_CODE_NO_XQTS if the XQTS could not be found, $EXIT_CODE_EXIST_START_FAILED if eXist-db could not be started.")

      help("help").text("prints this usage text")
    }

    parser.parse(args, CmdConfig()) match {
      case Some(cmdConfig) =>
        new XQTSRunner().run(cmdConfig)

      case None =>
        // arguments are bad, error message will have been displayed
        sys.exit(EXIT_CODE_INVALID_ARGS)
    }
  }
}

/**
  * Main application class.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
private class XQTSRunner {

  private val logger = Logger(classOf[XQTSRunner])
  @scala.annotation.unused private var existServer: Option[ExistServer] = None

  /**
    * Run's an XQTS against eXist-db.
    *
    * @param cmdConfig the command line arguments
    */
  private def run(cmdConfig: CmdConfig): Unit = {
    logger.info(s"eXist-db XQTS Runner starting for: ${XQTSVersion.label(cmdConfig.xqtsVersion)}")

    // 1) prepare the actor system
    val config = ConfigFactory.load().resolve()
    val system = ActorSystem("XQTSRunnerSystem", config)
    val settings = Settings(system)


    // TODO(AR) ant-junit:1.10.7 has buggy stylesheets for XML->HTML, remove this when a new version is available
    val styleDir = Some(Paths.get(settings.xqtsLocalDir).resolve("xsl"))
    if (!Files.exists(styleDir.get)) {
      Files.createDirectories(styleDir.get)
    }
    val framesPath = styleDir.get.resolve("junit-frames-saxon.xsl")
    if (!Files.exists(framesPath)) {
      val isFrames = getClass().getResourceAsStream("/xsl/junit-frames-saxon.xsl")
      try {
        Files.copy(isFrames, framesPath)
      } finally {
        isFrames.close()
      }
    }
    val noFramesPath = styleDir.get.resolve("junit-noframes-saxon.xsl")
    if (!Files.exists(noFramesPath)) {
      val isNoFrames = getClass().getResourceAsStream("/xsl/junit-noframes-saxon.xsl")
      try {
        Files.copy(isNoFrames, noFramesPath)
      } finally {
        isNoFrames.close()
      }
    }


    // 2) Ensure that we have a copy of the XQTS we need
    ensureXqtsPresent(cmdConfig.xqtsVersion, settings, cmdConfig.localDir) match {
      case -\/(throwable) =>
        logger.error("Could not access XQTS", throwable)
        sys.exit(EXIT_CODE_NO_XQTS)

      case \/-(localXqtsDir) =>
        // 3) start eXist-db
        logger.info("Starting an embedded instance of eXist-db...")
        ExistServer.start() match {
          case \/-(server) =>
            this.existServer = Some(server)
            logger.info(s"eXist-db ${ExistServer.getVersion()} (${ExistServer.getCommitAbbrev()}) OK.")

            // 4) register the shutdown process
            //TODO(AR) should likely switch to Coordinated Shutdown, see: https://doc.akka.io/docs/akka/2.5/actors.html#coordinated-shutdown
            system.registerOnTermination (() => {
              server.stopServer()
              sys.exit(EXIT_CODE_OK)
            })

            // 5) start the XQTSRunner actor
            val parserActorClass = getParserActorClass(cmdConfig.xqtsVersion)
            val serializerActorClass = getSerializerActorClass()
            val xqtsRunner = system.actorOf(Props(classOf[XQTSRunnerActor], settings.xmlParserBufferSize, server, parserActorClass, serializerActorClass, styleDir, cmdConfig.outputDir.getOrElse(Paths.get(settings.outputDir))), name = "XQTSRunner")
            xqtsRunner ! RunXQTS(cmdConfig.xqtsVersion, localXqtsDir, getEnabled(DEFAULT_FEATURES)(cmdConfig.enableFeatures, cmdConfig.disableFeatures).toSet, getEnabled(DEFAULT_SPECS)(cmdConfig.enableSpecs, cmdConfig.disableSpecs).toSet, getEnabled(DEFAULT_XML_VERSIONS)(cmdConfig.enableXmlVersions, cmdConfig.disableXmlVersions).toSet, getEnabled(DEFAULT_XSD_VERSIONS)(cmdConfig.enableXsdVersions, cmdConfig.disableXsdVersions).toSet, settings.commonResourceCacheMaxSize, cmdConfig.testSetPattern.map(_.right[Set[String]]).getOrElse(cmdConfig.testSets.toSet.left[Pattern]), cmdConfig.testCases.toSet, cmdConfig.excludeTestSets.toSet, cmdConfig.excludeTestCases.toSet)

          case -\/(throwable) =>
            logger.error("Unable to start eXist-db Server", throwable)
            sys.exit(EXIT_CODE_EXIST_START_FAILED)
        }
    }
  }

  /**
    * Get the dependencies which are enabled.
    *
    * @param defaultEnabled the dependencies which are enabled by default.
    *
    * @param enable the dependencies to enable.
    * @param disable the dependencies to disable.
    *
    * @return just the enabled dependencies.
    */
  private def getEnabled[T](defaultEnabled: Seq[T])(enable: Seq[T], disable: Seq[T]): Seq[T] = {
    (defaultEnabled ++ enable).filterNot(disable.contains(_)).toSet.toSeq
  }

  /**
    * Gets the parser for the XQTS version.
    *
    * @param xqtsVersion the version of XQTS to parse.
    *
    * @return the parser for the XQTS version.
    *
    * @throws IllegalArgumentException if we don't support the requested XQTS version.
    */
  @throws[IllegalArgumentException]
  private def getParserActorClass(xqtsVersion: XQTSVersion) : Class[_<: XQTSParserActor] = {
    xqtsVersion match {
      case XQTS_3_1 | XQTS_HEAD =>
        classOf[XQTS3CatalogParserActor]
      case _ => throw new IllegalArgumentException(s"We only support XQTS version 3.1 or HEAD, but version: ${XQTSVersion.label(xqtsVersion)} was requested")
    }
  }

  /**
    * Gets the serializer for the XQTS.
    *
    * @return the serializer for the XQTS.
    */
  private def getSerializerActorClass() : Class[_<: XQTSResultsSerializerActor] = {
    classOf[JUnitResultsSerializerActor]
  }

  //TODO(AR) use Cats IO for download and store ops
  /**
    * Ensure that an XQTS version is present, if not it will be downloaded.
    *
    * @param xqtsVersion the version of XQTS
    * @param settings the configured application settings.
    * @param overrideLocalDir a directory to use for storing the XQTS, overrides the default setting.
    *
    * @return Either the path to the XQTS, or an exception
    */
  private def ensureXqtsPresent(xqtsVersion: XQTSVersion, settings: SettingsImpl, overrideLocalDir: Option[Path]) : \/[Throwable, Path] = {
    def getLocalWorkDir(hasDir: Option[String]) = {
      hasDir match {
        case Some(_) =>
          overrideLocalDir.getOrElse(Paths.get(settings.xqtsLocalDir))
        case None =>
          overrideLocalDir.getOrElse(Paths.get(settings.xqtsLocalDir)).resolve(XQTSVersion.label(xqtsVersion))
      }
    }
    def hasLocalCopy(xqtsLocalPath: Path, xqtsCheckFile: String) = Files.exists(xqtsLocalPath) && Files.exists(xqtsLocalPath.resolve(xqtsCheckFile))
    def verifySha256(path: Path, sha256: String) : \/[Throwable, Path] = {
      Checksum.checksum(path, SHA256).map(_.map(_.toChar).mkString) match {
        case \/-(pathSha256) =>
          if(sha256.equals(sha256)) {
            path.right
          } else {
            -\/(new IOException(s"Downloaded file checksum is: $pathSha256 but expected $sha256"))
          }
        case -\/(e) => e.left[Path]
      }
    }

    def getFilename(xqtsUrl: String) = Paths.get(new URI(xqtsUrl).getPath).getFileName.toString
    def hasDownload(xqtsUrl: String, sha256: String, xqtsLocalPath: Path) : \/[Throwable, Option[Path]] = {
      val downloadFile = xqtsLocalPath.resolve(getFilename(xqtsUrl))
      if(Files.exists(downloadFile)) {
        verifySha256(downloadFile, sha256).map(Some(_))
      } else {
        Option.empty[Path].right[Throwable]
      }
    }

    def download(xqtsUrl: String, sha256: String, xqtsLocalPath: Path) : \/[Throwable, Path] = {
      def getFile(dest: Path): Path = {
        import sys.process._
        new URL(xqtsUrl) #> dest.toFile !!;
        dest
      }

      Try(getFile(xqtsLocalPath.resolve(getFilename(xqtsUrl)))) match {
        case scala.util.Success(tmpFile) => verifySha256(tmpFile, sha256)
        case scala.util.Failure(t) => t.left[Path]
      }
    }

    def expandToLocalCopy(xqtsZip: Path, xqtsLocalPath: Path) : \/[Throwable, Path] = {
        logger.info(s"Expanding XQTS from: $xqtsZip to: $xqtsLocalPath")
        try {
          // create dest if doesn't exist
          if(!Files.exists(xqtsLocalPath)) {
            Files.createDirectories(xqtsLocalPath)
          }

          // unzip the file
          org.exist.xqts.runner.Unzip.unzip(xqtsZip, xqtsLocalPath)

          xqtsLocalPath.right
        } catch {
          case e: IOException =>
            -\/(e)
        }
    }

    def processXqtsVersion(xqtsVersionConfig: settings.XqtsVersionConfig): \/[Throwable, Path] = {
      Try(Files.createDirectories(getLocalWorkDir(xqtsVersionConfig.hasDir))) match {
        case scala.util.Failure(t) =>
          -\/(t)
        case scala.util.Success(localDir) =>
          val localXqtsDir = xqtsVersionConfig.hasDir.map(hasDir => localDir.resolve(hasDir)).getOrElse(localDir.resolve(XQTSVersion.label(xqtsVersion)))
          if (hasLocalCopy(localXqtsDir, xqtsVersionConfig.checkFile)) {
            logger.info(s"Found XQTS at: $localXqtsDir")
            \/-(localXqtsDir)
          } else {
            logger.info(s"Could not find XQTS at: $localXqtsDir, checking for downloaded copy...")
            hasDownload(xqtsVersionConfig.url, xqtsVersionConfig.sha256, localDir) match {
              case \/-(Some(existingPath)) =>
                logger.info(s"Found downloaded XQTS at: $existingPath")
                expandToLocalCopy(existingPath, localDir)
                  .map(_ => localXqtsDir)
              case \/-(None) =>
                logger.info(s"No downloaded copy found, downloading XSTS from: ${xqtsVersionConfig.url} to $localDir...")
                download(xqtsVersionConfig.url, xqtsVersionConfig.sha256, localDir)
                  .flatMap(expandToLocalCopy(_, localDir))
                  .map(_ => localXqtsDir)
              case -\/(t) => -\/(t)
            }
          }
      }
    }

    // check for supported version
    settings.xqtsVersions.get(XQTSVersion.toVersionName(xqtsVersion)) match {
      case Some(xqtsVersionConfig) =>
        processXqtsVersion(xqtsVersionConfig)
      case None =>
        val supported = settings.xqtsVersions.keys.reduceLeft(_ + ", " + _)
        -\/(new IllegalArgumentException(s"We only support XQTS versions $supported, but version: ${XQTSVersion.label(xqtsVersion)} was requested"))
    }
  }
}
