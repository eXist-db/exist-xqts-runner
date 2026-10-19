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

import cats.effect.unsafe.IORuntime

import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import cats.effect.{IO, Resource}
import net.sf.saxon.TransformerFactoryImpl
import org.apache.tools.ant.Project
import org.exist.xqts.runner.TestCaseRunnerActor._
import org.exist.xqts.runner.XQTSResultsSerializerActor.{FinalizeSerialization, FinishedSerialization, SerializedTestSetResults, TestSetResults}
import org.apache.tools.ant.taskdefs.optional.junit.XMLResultAggregator
import org.apache.tools.ant.types.FileSet
import org.exist.xqts.runner.XQTSParserActor.TestSetName

/**
 * Actor which serialized test results to the JUnit XML report format.
 * It can also aggregate a number of XML reports into a HTML report.
 *
 * @author Adam Retter <adam@evolvedbinary.com>
 */
class JUnitResultsSerializerActor(styleDir: Option[Path], outputDir: Path) extends XQTSResultsSerializerActor {

  private val logger = Logger(classOf[JUnitResultsSerializerActor])

  // Captured at actor creation rather than at first message so the timestamp
  // brackets the full XQTS run, not just the serialization phase.
  private val runStarted: Instant = Instant.now()
  private var observedXqtsVersion: Option[String] = None
  private var observedTestCount: Long = 0L

  override def receive: Receive = {

    case testSetResults: TestSetResults =>
      logger.info(s"Serializing results for TestSet: ${testSetResults.testSetRef.name} (${testSetResults.testSetRef.file})...")
      if (observedXqtsVersion.isEmpty) {
        observedXqtsVersion = Some(XQTSVersion.label(testSetResults.testSetRef.xqtsVersion))
      }
      observedTestCount += testSetResults.results.size

      val serializeIo: IO[Option[Throwable]] = dataDir
        .flatMap(dataFile(_, testSetResults.testSetRef.name))
        .flatMap(dataFileOutput(_)
          .use(formatJunitTestSet(testSetResults, _)))
        .map(_ => None)
        .handleErrorWith(t => IO.pure {
          Some(t)
        })

      implicit val runtime = IORuntime.global

      serializeIo.unsafeRunSync() match {
        case None =>
          logger.info(s"Serialized results for TestSet: ${testSetResults.testSetRef.name} OK.")
        case Some(t) =>
          logger.error(s"Could not serialize results for TestSet: ${testSetResults.testSetRef.name}. ${t.getMessage}", t)
      }

      // notify the sender that we have completed serializing the results
      sender() ! SerializedTestSetResults(testSetResults.testSetRef)

    case FinalizeSerialization =>
      val start = System.currentTimeMillis()
      val aggregateIO: IO[Either[Throwable, Path]] = dataDir
        .flatTap(dd => IO {
          logger.info(s"Aggregating results report from: ${dd.toAbsolutePath}...")
        })
        .product(htmlDir)
        .flatTap { case (dataDir, htmlDir) => aggregateJUnitReports(dataDir, htmlDir) }
        .map { case (_, htmlDir) => htmlDir }
        .attempt

      implicit val runtime = IORuntime.global

      aggregateIO.unsafeRunSync() match {
        case Right(htmlDir) =>
          logger.info(s"Aggregated results report to: ${htmlDir.toAbsolutePath} (${System.currentTimeMillis() - start} ms).")

        case Left(t) =>
          logger.error(s"Could not aggregate results report. ${t.getMessage}", t)
      }

      writeRunnerInfo()

      // notify the sender that we have completed serialization
      sender() ! FinishedSerialization
  }

  /**
   * Emit `runner-info.xml` at the output directory root, capturing the runner
   * JAR's build SHA + sha256 and the embedded `exist-core` version. Consumed
   * by `compare-results.xslt` to surface runner-drift warnings.
   *
   * Failures here are logged but never propagated -- emitting metadata must
   * not affect the run's exit status.
   */
  private def writeRunnerInfo(): Unit = {
    val runInfo = RunnerInfo.RunInfo(
      started = runStarted,
      completed = Instant.now(),
      xqtsVersion = observedXqtsVersion,
      testCount = observedTestCount
    )
    RunnerInfo.write(outputDir, runInfo) match {
      case Right(path) =>
        logger.info(s"Wrote runner metadata to: ${path.toAbsolutePath}")
      case Left(t) =>
        logger.warn(s"Could not write runner-info.xml. ${t.getMessage}", t)
    }
  }

  private def dataDir: IO[Path] = IO {
    val dataDir = outputDir.resolve("junit").resolve("data")
    Files.createDirectories(dataDir)
    dataDir
  }

  private def htmlDir: IO[Path] = IO {
    val htmlDir = outputDir.resolve("junit").resolve("html")
    Files.createDirectories(htmlDir)
    htmlDir
  }

  private def dataFile(dir: Path, testSetName: TestSetName): IO[Path] = IO {
    dir.resolve("TEST-" + testSetName + ".xml")
  }

  private def dataFileOutput(dataFile: Path): Resource[IO, _ <: Writer] = {
    Resource.make(IO {
      Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)
    })(writer => IO {
      writer.close()
    })
  }

  private def formatJunitTestSet(testSetResults: TestSetResults, writer: Writer) = IO {
    val testSetRef = testSetResults.testSetRef
    val xqtsVersion = XQTSVersion.label(testSetRef.xqtsVersion)

    val testCases = testSetResults.results.map { result =>
      val outcome = result match {
        case PassResult(_, _, _, _) => JUnitXmlReport.Passed
        case AssumptionFailedResult(_, _, _, _, reason) => JUnitXmlReport.Skipped(reason)
        case FailureResult(_, _, _, _, reason) => JUnitXmlReport.Failed(reason)
        case ErrorResult(_, _, _, _, throwable) => JUnitXmlReport.Errored(throwable)
      }
      JUnitXmlReport.TestCase(result.testCase, result.compilationTime + result.executionTime, outcome)
    }

    JUnitXmlReport.write(
      writer,
      s"$xqtsVersion.${testSetRef.name}",
      Seq(
        "file" -> testSetRef.file.toString,
        "name" -> testSetRef.name,
        "version" -> xqtsVersion),
      testCases)
  }

  private def aggregateJUnitReports(dataDir: Path, htmlDir: Path): IO[Unit] = IO {
    val project = new Project()
    project.setProperty("java.io.tmpdir", System.getProperty("java.io.tmpdir"))

    val fileSet = new FileSet()
    fileSet.setProject(project)
    fileSet.setDir(dataDir.toFile)
    fileSet.setIncludes("TEST-*.xml")

    val aggregator = new XMLResultAggregator()
    aggregator.setProject(project)
    aggregator.addFileSet(fileSet)
    aggregator.setTodir(dataDir.toFile) // for the `TESTS-TestSuites.xml` aggregate file

    val aggregateTransformer = aggregator.createReport()
    aggregateTransformer.setTodir(htmlDir.toFile) // for the HTML reports
    aggregateTransformer.createFactory().setName(classOf[TransformerFactoryImpl].getName)

    styleDir.map(dir => aggregateTransformer.setStyledir(dir.toAbsolutePath.toFile))

    aggregator.execute()
  }
}
