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

package org.exist.xqts.runner.xqftts

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.{Files, Path}
import java.util.regex.Pattern
import org.apache.pekko.actor.ActorRef
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.fasterxml.aalto.AsyncXMLStreamReader.EVENT_INCOMPLETE
import com.fasterxml.aalto.{AsyncByteBufferFeeder, AsyncXMLStreamReader}

import javax.xml.stream.XMLStreamConstants.{CHARACTERS, END_DOCUMENT, END_ELEMENT, START_ELEMENT}
import org.exist.xqts.runner._
import org.exist.xqts.runner.XQTSParserActor._
import org.exist.xqts.runner.TestCaseRunnerActor.RunTestCase
import org.exist.xqts.runner.XQTSRunnerActor.{ParsedTestSet, ParsingTestSet, RunningTestCase}
import org.exist.xqts.runner.xqftts._

import scala.annotation.tailrec

/**
  * Parses an XQFTTS 1.0.4 XQFTTSCatalog.xml file.
  *
  * Unlike the QT3 parsers which split catalog and test-set parsing,
  * this actor handles both since XQFTTS puts everything in one file.
  * For each test-case parsed, an execution request will be sent
  * to a TestCaseRunnerActor.
  *
  * @param xmlParserBufferSize the maximum buffer size for XML parsing.
  * @param testCaseRunnerRouter a router to test case runner actors.
  */
class XQFTTSCatalogParserActor(xmlParserBufferSize: Int, testCaseRunnerRouter: ActorRef, existServer: ExistServer) extends XQTSParserActor {

  private val logger = Logger(classOf[XQFTTSCatalogParserActor])

  // Source lookup: ID -> resolved file path
  private var sources: Map[String, Path] = Map.empty

  // Stop word URI -> local file path mapping (populated from <stopwords> catalog elements)
  private var stopWordURIMap: Map[String, Path] = Map.empty

  // Thesaurus URI -> local file path mapping (populated from <thesaurus> catalog elements)
  private var thesaurusURIMap: Map[String, Path] = Map.empty

  // Test group tracking
  private var groupStack: List[String] = List.empty
  private var currentFilePath: List[Option[String]] = List.empty // parallel stack for FilePath

  // Current test-case state
  private var currentTestCaseName: Option[String] = None
  private var currentTestCaseFilePath: Option[String] = None
  private var currentTestCaseScenario: Option[String] = None
  private var currentQueryName: Option[String] = None
  private var currentInputFiles: List[(String, String)] = List.empty // (variable, sourceID)
  private var currentContextItem: Option[String] = None // sourceID for context item
  private var currentOutputFiles: List[(String, Path)] = List.empty // (compareType, resolvedPath)
  private var currentExpectedErrors: List[String] = List.empty

  // Temporary state for text capture within input-file / output-file / expected-error
  private var captureText = false
  private var currentText: Option[String] = None
  private var currentInputVariable: Option[String] = None
  private var currentCompareType: Option[String] = None

  // Offset paths from the catalog root element
  private var queryOffsetPath: String = XQUERY_QUERY_OFFSET
  private var resultOffsetPath: String = RESULT_OFFSET
  private var sourceOffsetPath: String = "./"
  private var queryFileExtension: String = XQUERY_FILE_EXT

  // Test set tracking
  private var testSetTestCases: Map[String, List[String]] = Map.empty
  private var announcedTestSets: Set[String] = Set.empty
  private var matchedTestSets: Int = 0
  private var uriMapsRegistered: Boolean = false

  override def receive: Receive = {

    case Parse(xqtsVersion, xqtsPath, features, specs, xmlVersions, xsdVersions, testSets, testCases, excludeTestSets, excludeTestCases) =>
      val sender = context.sender()
      logger.info(s"Parsing XQFTTS Catalog: ${xqtsPath.resolve(CATALOG_FILE)}...")
      val matched = parseCatalog(sender, xqtsVersion, xqtsPath, testSets, testCases, excludeTestSets, excludeTestCases)
      // Ensure URI maps are registered (in case catalog had no test groups)
      if (!uriMapsRegistered) {
        registerURIMaps()
        uriMapsRegistered = true
      }

      logger.info(s"Parsed XQFTTS Catalog OK. Matched $matched test sets.")
      sender ! ParseComplete(xqtsVersion, xqtsPath, matched)
      context.stop(self)
  }

  private def parseCatalog(
    xqtsRunner: ActorRef,
    xqtsVersion: XQTSVersion,
    xqtsPath: Path,
    testSets: Either[Set[String], Pattern],
    testCases: Either[Set[String], Pattern],
    excludeTestSets: Set[String],
    excludeTestCases: Set[String]
  ): Int = {

    def matchesTestSets(testSetName: String): Boolean = {
      if (excludeTestSets.contains(testSetName)) return false
      testSets match {
        case Left(names) => names.isEmpty || names.contains(testSetName)
        case Right(pattern) => pattern.matcher(testSetName).matches()
      }
    }

    def matchesTestCases(testCaseName: String): Boolean = {
      if (excludeTestCases.contains(testCaseName)) return false
      testCases match {
        case Left(names) => names.isEmpty || names.contains(testCaseName)
        case Right(pattern) => pattern.matcher(testCaseName).matches()
      }
    }

    val catalogPath = xqtsPath.resolve(CATALOG_FILE)

    val bufIO = cats.effect.Resource.make(IO { ByteBuffer.allocate(xmlParserBufferSize) })(buf => IO { buf.clear() })
    val fileIO = cats.effect.Resource.make(IO { Files.newByteChannel(catalogPath) })(channel => IO { channel.close() })
    val asyncParserIO = cats.effect.Resource.make(IO { PARSER_FACTORY.createAsyncForByteBuffer() })(asyncReader => IO { asyncReader.close() })
    val parseIO = bufIO.use(buf =>
      fileIO.use(channel =>
        asyncParserIO.use(asyncReader =>
          IO {
            parseAll(asyncReader.next(), asyncReader, xqtsPath, channel, buf, xqtsRunner, matchesTestSets, matchesTestCases)
          }
        )
      )
    )
    parseIO.unsafeRunSync()(IORuntime.global)

    matchedTestSets
  }

  @tailrec
  @throws[XQTSParseException]
  private def parseAll(
    event: Int,
    asyncReader: AsyncXMLStreamReader[AsyncByteBufferFeeder],
    xqtsPath: Path,
    channel: SeekableByteChannel,
    buf: ByteBuffer,
    xqtsRunner: ActorRef,
    matchesTestSets: String => Boolean,
    matchesTestCases: String => Boolean
  ): Unit = {
    event match {

      case END_DOCUMENT =>
        // Finalize any remaining open test sets
        for ((tsName, tcNames) <- testSetTestCases) {
          val testSetRef = TestSetRef(XQTS_FTTS_1_0, tsName, xqtsPath.resolve(CATALOG_FILE))
          xqtsRunner ! ParsedTestSet(testSetRef, tcNames)
        }
        return

      case START_ELEMENT if asyncReader.getLocalName == ELEM_TEST_SUITE =>
        // Read offset paths from catalog root attributes
        asyncReader.getAttributeValueOpt(ATTR_XQUERY_QUERY_OFFSET_PATH).foreach(v => queryOffsetPath = v)
        asyncReader.getAttributeValueOpt(ATTR_RESULT_OFFSET_PATH).foreach(v => resultOffsetPath = v)
        asyncReader.getAttributeValueOpt(ATTR_SOURCE_OFFSET_PATH).foreach(v => sourceOffsetPath = v)
        asyncReader.getAttributeValueOpt(ATTR_XQUERY_FILE_EXTENSION).foreach(v => queryFileExtension = v)

      case START_ELEMENT if asyncReader.getLocalName == ELEM_SOURCE && groupStack.isEmpty =>
        // Source definition in the <sources> section
        val id = asyncReader.getAttributeValue(ATTR_ID)
        val fileName = asyncReader.getAttributeValue(ATTR_FILE_NAME)
        if (id != null && fileName != null) {
          sources = sources + (id -> xqtsPath.resolve(fileName))
        }

      case START_ELEMENT if asyncReader.getLocalName == ELEM_STOPWORDS && groupStack.isEmpty =>
        // Stop word definition: maps URI to local file path
        val uri = asyncReader.getAttributeValue(ATTR_URI)
        val fileName = asyncReader.getAttributeValue(ATTR_FILE_NAME)
        if (uri != null && fileName != null) {
          val resolvedPath = xqtsPath.resolve(fileName)
          stopWordURIMap = stopWordURIMap + (uri -> resolvedPath)
          logger.debug(s"Registered stop word URI mapping: $uri -> $resolvedPath")
        }

      case START_ELEMENT if asyncReader.getLocalName == ELEM_THESAURUS && groupStack.isEmpty =>
        // Thesaurus definition: maps URI to local file path
        val uri = asyncReader.getAttributeValue(ATTR_URI)
        val fileName = asyncReader.getAttributeValue(ATTR_FILE_NAME)
        if (uri != null && fileName != null) {
          val resolvedPath = xqtsPath.resolve(fileName)
          thesaurusURIMap = thesaurusURIMap + (uri -> resolvedPath)
          logger.debug(s"Registered thesaurus URI mapping: $uri -> $resolvedPath")
        }

      case START_ELEMENT if asyncReader.getLocalName == ELEM_TEST_GROUP =>
        // Register stop word and thesaurus URI maps before the first test group
        // to ensure they're available before any test cases are sent to the runner.
        if (!uriMapsRegistered) {
          registerURIMaps()
          uriMapsRegistered = true
        }
        val name = asyncReader.getAttributeValue(ATTR_NAME)
        groupStack = groupStack :+ (if (name != null) name else s"group-${groupStack.size}")
        currentFilePath = currentFilePath :+ Option(asyncReader.getAttributeValue(ATTR_FILE_PATH))

      case END_ELEMENT if asyncReader.getLocalName == ELEM_TEST_GROUP =>
        val groupName = if (groupStack.nonEmpty) groupStack.last else "<unknown>"
        // If this group had test cases, finalize it
        if (testSetTestCases.contains(groupName)) {
          val testSetRef = TestSetRef(XQTS_FTTS_1_0, groupName, xqtsPath.resolve(CATALOG_FILE))
          xqtsRunner ! ParsedTestSet(testSetRef, testSetTestCases(groupName))
          testSetTestCases = testSetTestCases - groupName
        }
        if (groupStack.nonEmpty) groupStack = groupStack.init
        if (currentFilePath.nonEmpty) currentFilePath = currentFilePath.init

      case START_ELEMENT if asyncReader.getLocalName == ELEM_TEST_CASE =>
        currentTestCaseName = asyncReader.getAttributeValueOpt(ATTR_NAME)
        currentTestCaseFilePath = asyncReader.getAttributeValueOpt(ATTR_FILE_PATH)
          .orElse(currentFilePath.reverse.collectFirst { case Some(fp) => fp })
        currentTestCaseScenario = asyncReader.getAttributeValueOpt(ATTR_SCENARIO)
        currentQueryName = None
        currentInputFiles = List.empty
        currentContextItem = None
        currentOutputFiles = List.empty
        currentExpectedErrors = List.empty

      case START_ELEMENT if asyncReader.getLocalName == ELEM_QUERY && currentTestCaseName.isDefined =>
        currentQueryName = asyncReader.getAttributeValueOpt(ATTR_NAME)

      case START_ELEMENT if asyncReader.getLocalName == ELEM_INPUT_FILE && currentTestCaseName.isDefined =>
        currentInputVariable = asyncReader.getAttributeValueOpt(ATTR_VARIABLE).orElse(Some("input-context"))
        captureText = true
        currentText = None

      case END_ELEMENT if asyncReader.getLocalName == ELEM_INPUT_FILE && currentTestCaseName.isDefined =>
        captureText = false
        val sourceId = currentText.map(_.trim).getOrElse("")
        if (sourceId.nonEmpty) {
          val variable = currentInputVariable.getOrElse("input-context")
          currentInputFiles = currentInputFiles :+ (variable, sourceId)
        }
        currentText = None
        currentInputVariable = None

      case START_ELEMENT if asyncReader.getLocalName == ELEM_CONTEXT_ITEM && currentTestCaseName.isDefined =>
        captureText = true
        currentText = None

      case END_ELEMENT if asyncReader.getLocalName == ELEM_CONTEXT_ITEM && currentTestCaseName.isDefined =>
        captureText = false
        currentText.map(_.trim).filter(_.nonEmpty).foreach { sourceId =>
          currentContextItem = Some(sourceId)
        }
        currentText = None

      case START_ELEMENT if asyncReader.getLocalName == ELEM_OUTPUT_FILE && currentTestCaseName.isDefined =>
        currentCompareType = asyncReader.getAttributeValueOpt(ATTR_COMPARE).orElse(Some(COMPARE_TEXT))
        captureText = true
        currentText = None

      case END_ELEMENT if asyncReader.getLocalName == ELEM_OUTPUT_FILE && currentTestCaseName.isDefined =>
        captureText = false
        val fileName = currentText.map(_.trim).getOrElse("")
        if (fileName.nonEmpty) {
          val filePath = currentTestCaseFilePath.getOrElse("")
          val resolvedPath = xqtsPath.resolve(resultOffsetPath + filePath + fileName)
          val compareType = currentCompareType.getOrElse(COMPARE_TEXT)
          currentOutputFiles = currentOutputFiles :+ (compareType, resolvedPath)
        }
        currentText = None
        currentCompareType = None

      case START_ELEMENT if asyncReader.getLocalName == ELEM_EXPECTED_ERROR && currentTestCaseName.isDefined =>
        captureText = true
        currentText = None

      case END_ELEMENT if asyncReader.getLocalName == ELEM_EXPECTED_ERROR && currentTestCaseName.isDefined =>
        captureText = false
        currentText.map(_.trim).filter(_.nonEmpty).foreach { code =>
          currentExpectedErrors = currentExpectedErrors :+ code
        }
        currentText = None

      case END_ELEMENT if asyncReader.getLocalName == ELEM_TEST_CASE && currentTestCaseName.isDefined =>
        // Build and dispatch the test case
        val testCaseName = currentTestCaseName.get
        val testSetName = if (groupStack.nonEmpty) groupStack.last else "unknown"

        if (matchesTestSets(testSetName) && matchesTestCases(testCaseName)) {
          val testSetRef = TestSetRef(XQTS_FTTS_1_0, testSetName, xqtsPath.resolve(CATALOG_FILE))

          // Announce test set if first time
          if (!announcedTestSets.contains(testSetName)) {
            xqtsRunner ! ParsingTestSet(testSetRef)
            announcedTestSets = announcedTestSets + testSetName
            matchedTestSets = matchedTestSets + 1
          }

          // Build query path
          val filePath = currentTestCaseFilePath.getOrElse("")
          val queryPath = currentQueryName.map(qn => xqtsPath.resolve(queryOffsetPath + filePath + qn + queryFileExtension))

          // Build environment from input files and context item
          val inputSources = currentInputFiles.flatMap { case (variable, sourceId) =>
            sources.get(sourceId).map { sourcePath =>
              Source(
                role = Some(ExternalVariableRole(variable)),
                file = sourcePath,
                uri = None
              )
            }
          }
          val contextSources = currentContextItem.flatMap { sourceId =>
            sources.get(sourceId).map { sourcePath =>
              Source(
                role = Some(ContextItemRole),
                file = sourcePath,
                uri = None
              )
            }
          }.toList
          val envSources = contextSources ++ inputSources
          val environment = if (envSources.nonEmpty) Some(Environment("env", sources = envSources)) else None

          // Build assertions
          val assertions: List[Result] = buildAssertions()

          val result = if (assertions.size == 1) Some(assertions.head) else if (assertions.nonEmpty) Some(AnyOf(assertions)) else None

          val testCase = TestCase(
            file = xqtsPath.resolve(CATALOG_FILE),
            name = testCaseName,
            covers = "",
            description = None,
            environment = environment,
            test = queryPath.map(Right(_)),
            result = result
          )

          // Track and dispatch
          testSetTestCases = testSetTestCases + (testSetName -> (testSetTestCases.getOrElse(testSetName, List.empty) :+ testCaseName))
          xqtsRunner ! RunningTestCase(testSetRef, testCaseName)
          testCaseRunnerRouter ! RunTestCase(testSetRef, testCase, xqtsRunner)
        }

        // Reset test case state
        currentTestCaseName = None
        currentTestCaseFilePath = None
        currentTestCaseScenario = None
        currentQueryName = None
        currentInputFiles = List.empty
        currentContextItem = None
        currentOutputFiles = List.empty
        currentExpectedErrors = List.empty

      case CHARACTERS if captureText =>
        val text = asyncReader.getText
        currentText = Some(currentText.getOrElse("") + text)

      case EVENT_INCOMPLETE =>
        val bytesRead = channel.read(buf)
        if (bytesRead == -1) {
          asyncReader.getInputFeeder.endOfInput()
        } else {
          buf.flip()
          asyncReader.getInputFeeder.feedInput(buf)
        }

      case _ => // ignore other events
    }

    parseAll(asyncReader.next(), asyncReader, xqtsPath, channel, buf, xqtsRunner, matchesTestSets, matchesTestCases)
  }

  /**
    * Register stop word and thesaurus URI mappings on the ExistServer's global context
    * attributes, so they're available via XQueryContext.getAttribute() during test execution.
    */
  private def registerURIMaps(): Unit = {
    import scala.jdk.CollectionConverters._
    if (stopWordURIMap.nonEmpty) {
      val javaMap: java.util.Map[String, Path] = stopWordURIMap.asJava
      existServer.globalContextAttributes = existServer.globalContextAttributes +
        ("ft.stopWordURIMap" -> javaMap)
      logger.info(s"Registered ${stopWordURIMap.size} stop word URI mappings")
    }
    if (thesaurusURIMap.nonEmpty) {
      val javaMap: java.util.Map[String, Path] = thesaurusURIMap.asJava
      existServer.globalContextAttributes = existServer.globalContextAttributes +
        ("ft.thesaurusURIMap" -> javaMap)
      logger.info(s"Registered ${thesaurusURIMap.size} thesaurus URI mappings")
    }
  }

  /**
    * Build assertions from the current test case's output files and expected errors.
    */
  private def buildAssertions(): List[Result] = {
    val outputAssertions = currentOutputFiles.filter(_._2 != null).flatMap { case (compareType, path) =>
      compareType match {
        case COMPARE_XML =>
          Some(AssertXml(Right(path)))
        case COMPARE_FRAGMENT =>
          // Fragment comparison normalizes whitespace in text nodes because
          // XQFTTS expected outputs were generated by reference implementations
          // with different line-wrapping/indentation conventions than eXist-db.
          Some(AssertXml(Right(path), normalizeWhitespace = true))
        case COMPARE_TEXT =>
          Some(AssertXml(Right(path)))
        case COMPARE_INSPECT | COMPARE_IGNORE =>
          Some(AssertInspect) // cannot automatically verify; always passes
        case _ =>
          Some(AssertXml(Right(path)))
      }
    }

    val errorAssertions = currentExpectedErrors.map(code => Error(code))

    outputAssertions ++ errorAssertions
  }
}
