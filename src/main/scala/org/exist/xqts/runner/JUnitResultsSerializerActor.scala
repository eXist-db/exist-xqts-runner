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

import java.io.{BufferedOutputStream, OutputStream}
import java.nio.file.{Files, Path}

import cats.effect.{IO, Resource}
import grizzled.slf4j.Logger
import junit.framework.{AssertionFailedError, Test => JUTest, TestResult => JUTestResult}
import net.sf.saxon.TransformerFactoryImpl
import org.apache.tools.ant.Project
import org.exist.xqts.runner.TestCaseRunnerActor._
import org.exist.xqts.runner.XQTSResultsSerializerActor.{FinalizeSerialization, FinishedSerialization, SerializedTestSetResults, TestSetResults}
import org.apache.tools.ant.taskdefs.optional.junit.{JUnitTest, XMLJUnitResultFormatter, XMLResultAggregator}
import org.w3c.dom.Element
import org.apache.tools.ant.taskdefs.optional.junit.XMLConstants.ATTR_TIME
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

  override def receive: Receive = {

    case testSetResults : TestSetResults =>
      logger.info(s"Serializing results for TestSet: ${testSetResults.testSetRef.name} (${testSetResults.testSetRef.file})...")
      dataDir
        .flatMap(dataFile(_, testSetResults.testSetRef.name))
        .flatMap(dataFileOutput(_)
          .use(formatJunitTestSet(testSetResults, _)))
        .map(_ => None)
        .handleErrorWith(t => IO.pure { Some(t) })
        .unsafeRunSync()
      match {
        case None =>
          logger.info(s"Serialized results for TestSet: ${testSetResults.testSetRef.name} OK.")
        case Some(t) =>
          logger.error(s"Could not serialize results for TestSet: ${testSetResults.testSetRef.name}. ${t.getMessage}", t)
      }

      // notify the sender that we have completed serializing the results
      sender() ! SerializedTestSetResults(testSetResults.testSetRef)

    case FinalizeSerialization =>
      val start = System.currentTimeMillis()
      logger.info(s"Aggregating results report...")
      dataDir
        .flatMap(dd => htmlDir.map(hd => (dd, hd)))
        .flatMap { case (dataDir, htmlDir) => aggregateJUnitReports(dataDir, htmlDir) }
        .map(_ => None)
        .handleErrorWith(t => IO.pure { Some(t) })
        .unsafeRunSync()
      match {
        case None =>
          logger.info(s"Aggregated results report OK (${System.currentTimeMillis() - start} ms).")

        case Some(t) =>
          logger.error(s"Could not aggregate results report. ${t.getMessage}", t)
      }

      // notify the sender that we have completed serialization
      sender() ! FinishedSerialization
  }

  private def dataDir : IO[Path] = IO {
    val dataDir = outputDir.resolve("junit").resolve("data")
    Files.createDirectories(dataDir)
    dataDir
  }

  private def htmlDir : IO[Path] = IO {
    val htmlDir = outputDir.resolve("junit").resolve("html")
    Files.createDirectories(htmlDir)
    htmlDir
  }

  private def dataFile(dir: Path, testSetName: TestSetName): IO[Path] = IO {
    dir.resolve("TEST-" + testSetName + ".xml")
  }

  private def dataFileOutput(dataFile: Path) : Resource[IO, _ <: OutputStream] = {
    Resource.make(IO { new BufferedOutputStream(Files.newOutputStream(dataFile)) })(os => IO { os.close()})
  }

  private def formatJunitTestSet(testSetResults: TestSetResults, os: OutputStream) = IO {
    val junitResultFormatter = new XQTSXMLJUnitResultFormatter()
    junitResultFormatter.setOutput(os)

    val testSetRef = testSetResults.testSetRef
    val results = testSetResults.results

    val junitTestSet = new JUnitTest(s"${XQTSVersion.label(testSetRef.xqtsVersion)}.${testSetRef.name}")
    junitTestSet.setCounts(
      results.size,
      results.filter(_.isInstanceOf[FailureResult]).size,
      results.filter(_.isInstanceOf[ErrorResult]).size,
      results.filter(_.isInstanceOf[AssumptionFailedResult]).size
    )
    junitTestSet.setRunTime(results.foldLeft(0L)((accum, x) => accum + x.compilationTime + x.executionTime))
    junitTestSet.setProperties(asJavaHashtable(Map(
      "file" -> testSetRef.file.toString,
      "name" -> testSetRef.name,
      "version" -> XQTSVersion.label(testSetRef.xqtsVersion))))

    junitResultFormatter.startTestSuite(junitTestSet)
    for (result <- results) {

      val test = XQTSJUnitTest(result)
      junitResultFormatter.startTest(test)

      result match {
        case PassResult(_, _, _, _) =>
          junitResultFormatter.endTest(test)

        case AssumptionFailedResult(_, _, _, _, reason) =>
          junitResultFormatter.formatSkip(test, reason)

        case FailureResult(_, _, _, _, reason) =>
          junitResultFormatter.addFailure(test, new AssertionFailedError(reason))
          junitResultFormatter.endTest(test)

        case ErrorResult(_, _, _, _, throwable) =>
          junitResultFormatter.addError(test, throwable)
          junitResultFormatter.endTest(test)
      }
    }
    junitResultFormatter.endTestSuite(junitTestSet)
  }

  private def asJavaHashtable[K, V](map: Map[K, V]) : java.util.Hashtable[K, V] = {
    import scala.jdk.CollectionConverters._
    new java.util.Hashtable(map.asJava)
  }

  private def aggregateJUnitReports(dataDir: Path, htmlDir: Path) : IO[Unit] = IO {
    val project = new Project()
    project.setProperty("java.io.tmpdir", System.getProperty("java.io.tmpdir"))

    val fileSet = new FileSet()
    fileSet.setProject(project)
    fileSet.setDir(dataDir.toFile)
    fileSet.setIncludes("TEST-*.xml")

    val aggregator = new XMLResultAggregator()
    aggregator.setProject(project)
    aggregator.addFileSet(fileSet)
    aggregator.setTodir(dataDir.toFile)   // for the `TESTS-TestSuites.xml` aggregate file

    val aggregateTransformer = aggregator.createReport()
    aggregateTransformer.setTodir(htmlDir.toFile)   // for the HTML reports
    aggregateTransformer.createFactory().setName(classOf[TransformerFactoryImpl].getName)

    styleDir.map(dir => aggregateTransformer.setStyledir(dir.toAbsolutePath.toFile))

    aggregator.execute()
  }

  private object XQTSJUnitTest {
    def apply(testResult : TestResult) = new XQTSJUnitTest(testResult)
  }

  private class XQTSJUnitTest(testResult: TestResult) extends JUTest {
    override def countTestCases(): Int = 1

    def getName() : String = testResult.testCase

    def getCompilationTime : Long = testResult.compilationTime

    def getExecutionTime : Long = testResult.executionTime

    override def run(juTestResult: JUTestResult): Unit =  {}
  }

  class XQTSXMLJUnitResultFormatter extends XMLJUnitResultFormatter {
    private val rootElementField = classOf[XMLJUnitResultFormatter].getDeclaredField("rootElement")
    rootElementField.setAccessible(true)

    override def endTest(test: JUTest): Unit = {
      super.endTest(test)
      if (test.isInstanceOf[XQTSJUnitTest]) {
        val xqtsJunitTest = test.asInstanceOf[XQTSJUnitTest]
        val rootElement = rootElementField.get(this).asInstanceOf[Element]
        val currentTest = rootElement.getLastChild().asInstanceOf[Element]
        val timeInSeconds = ((xqtsJunitTest.getCompilationTime + xqtsJunitTest.getExecutionTime) / 1000D).toString
        currentTest.setAttribute(ATTR_TIME, timeInSeconds)
      }
    }
  }
}
