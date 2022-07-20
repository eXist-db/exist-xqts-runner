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

package org.exist.xqts.runner.qt3

import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.{Files, Path}
import java.util.regex.Pattern
import akka.actor.ActorRef
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.fasterxml.aalto.AsyncXMLStreamReader.EVENT_INCOMPLETE
import com.fasterxml.aalto.{AsyncByteBufferFeeder, AsyncXMLStreamReader}
import grizzled.slf4j.Logger

import javax.xml.stream.XMLStreamConstants.{CHARACTERS, END_DOCUMENT, END_ELEMENT, START_ELEMENT}
import net.sf.saxon.value.AnyURIValue
import org.exist.xqts.runner.XQTSParserActor.Feature.Feature
import org.exist.xqts.runner.XQTSParserActor.Spec.Spec
import org.exist.xqts.runner.XQTSParserActor.XmlVersion.XmlVersion
import org.exist.xqts.runner.XQTSParserActor.XsdVersion.XsdVersion
import org.exist.xqts.runner.{XQTSParseException, XQTSParserActor, XQTSVersion}
import org.exist.xqts.runner.XQTSParserActor._
import org.exist.xqts.runner.qt3.XQTS3CatalogParserActor._
import org.exist.xqts.runner.qt3.XQTS3TestSetParserActor.ParseTestSet

import scala.annotation.tailrec

/**
  * Parser an XQTS 3.1 catalog.xml file.
  *
  * For each reference to a test-set file parsed from the catalog,
  * a parse request of {@link ParseTestSet} will be sent
  * to a {@link XQTS3TestSetParserActor}.
  *
  * @param xmlParserBufferSize the maximum buffer size to use for each XML
  *                            document from the XQTS which we parse.
  * @param testSetParserRouter a router to a pool of test-set parsers.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
class XQTS3CatalogParserActor(xmlParserBufferSize: Int, testSetParserRouter: ActorRef) extends XQTSParserActor {

  private val logger = Logger(classOf[XQTS3CatalogParserActor])

  private var currentEnv: Option[Environment] = None
  private var currentNs: Option[Namespace] = None
  private var currentSchema: Option[Schema] = None
  private var captureText = false
  private var currentText: Option[String] = None
  private var currentCreated: Option[Created] = None
  private var currentSource: Option[Source] = None
  private var currentParam: Option[Param] = None
  private var currentTestSetRef: Option[TestSetRef] = None

  private var globalEnvironments: Map[String, Environment] = Map.empty

  override def receive: Receive = {

    case Parse(xqtsVersion, xqtsPath, features, specs, xmlVersions, xsdVersions, testSets, testCases, excludeTestSets, excludeTestCases) =>
      val sender = context.sender()
      logger.info(s"Parsing XQTS Catalog: ${xqtsPath.resolve(CATALOG_FILE)}...")
      val matchedTestSets = parseCatalog(sender, xqtsVersion, xqtsPath, features, specs, xmlVersions, xsdVersions, testSets, testCases, excludeTestSets, excludeTestCases)
      logger.info("Parsed XQTS Catalog OK.")
      sender ! ParseComplete(xqtsVersion, xqtsPath, matchedTestSets)

      context.stop(self)  // we are no longer needed
  }

  /**
    * Parses a XQTS catalog.xml file.
    *
    * Each test-set which is not excluded from the parse
    * will be dispatched to a {@link XQTS3TestSetParserActor}.
    *
    * @param xqtsRunner a reference to the XQTSRunnerActor
    * @param xqtsVersion the version of the XQTS.
    * @param xqtsPath the path to the XQTS.
    * @param features the enabled XQTS features to support.
    * @param specs the enabled XQTS specs to support.
    * @param xmlVersions the enabled XQTS XML versions to support.
    * @param xsdVersions the enabled XQTS XSD versions to support.
    * @param testSets the test-sets to parse, or an empty set to parse all.
    * @param testCases the test-cases to parse, or an empty set to parse all.
    * @param excludeTestSets the names of any test sets to exclude from the parse.
    * @param excludeTestCases the names of any test cases to exclude from the parse.
    *
    * @return the number of test sets that were matched in the catalog and dispatched to the testSetParserRouter.
    */
  private def parseCatalog(xqtsRunner: ActorRef, xqtsVersion: XQTSVersion, xqtsPath: Path, features: Set[Feature], specs: Set[Spec], xmlVersions: Set[XmlVersion], xsdVersions: Set[XsdVersion], testSets: Either[Set[String], Pattern], testCases: Either[Set[String], Pattern], excludeTestSets: Set[String], excludeTestCases: Set[String]) : Int = {

    /**
      * The asynchronous STaX parsing loop,
      * implemented as a recursive function.
      *
      * @param event the current STaX event.
      * @param asyncReader the asynchronous XML stream reader.
      * @param xqtsPath the path to the directory containing the catalog.xml.
      * @param channel the file channel for the {@code asyncReader}.
      * @param buf the buffer for the {@code asyncReader}.
      *
      * @return the number of test sets that were matched in the catalog and dispatched to the testSetParserRouter.
      *
      * @throws XQTSParseException if an error happens during parsing.
      */
    @tailrec
    @throws[XQTSParseException]
    def parseAll(event: Int, asyncReader: AsyncXMLStreamReader[AsyncByteBufferFeeder], xqtsPath: Path, channel: SeekableByteChannel, buf: ByteBuffer, matchedTestSets: Int = 0): Int = {
      var matchedTestSet = false

      event match {
        case END_DOCUMENT =>
          return matchedTestSets  // exit parse

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_ENVIRONMENT) =>
          val name = asyncReader.getAttributeValue(ATTR_NAME)
          currentEnv = Some(Environment(name))

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_NAMESPACE && currentEnv.nonEmpty) =>
          val prefix = asyncReader.getAttributeValue(ATTR_PREFIX)
          val uri = new URI(asyncReader.getAttributeValue(ATTR_URI))
          currentNs = Some(Namespace(prefix, uri))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_NAMESPACE && currentEnv.nonEmpty) =>
          currentEnv = currentNs.map(namespace => currentEnv.map(env => env.copy(namespaces = namespace +: env.namespaces)))
            .getOrElse(currentEnv)
          currentNs = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_SCHEMA && currentEnv.nonEmpty) =>
          val file = Option(asyncReader.getAttributeValue(ATTR_FILE))
          val uri = Option(asyncReader.getAttributeValue(ATTR_URI)).map(new AnyURIValue(_))
          currentSchema = Some(Schema(uri, file.map(xqtsPath.resolve(_))))

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_DESCRIPTION && (currentSchema.nonEmpty || currentSource.nonEmpty)) =>
          captureText = true

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_DESCRIPTION && (currentSchema.nonEmpty || currentSource.nonEmpty)) =>
          if (currentSchema.nonEmpty) {
            currentSchema = currentSchema.map(schema => schema.copy(description = currentText))
          } else if (currentSource.nonEmpty) {
            currentSource = currentSource.map(source => source.copy(description = currentText))
          }
          currentText = None
          captureText = false

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_CREATED && (currentSchema.nonEmpty || currentSource.nonEmpty)) =>
          val by = asyncReader.getAttributeValue(ATTR_BY)
          val on = asyncReader.getAttributeValue(ATTR_ON)
          currentCreated = Some(Created(by, on))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_CREATED && (currentSchema.nonEmpty || currentSource.nonEmpty)) =>
          if (currentSchema.nonEmpty) {
            currentSchema = currentSchema.map(schema => schema.copy(created = currentCreated))
          } else if (currentSource.nonEmpty) {
            currentSource = currentSource.map(source => source.copy(created = currentCreated))
          }
          currentCreated = None

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_SCHEMA && currentEnv.nonEmpty) =>
          currentEnv = currentSchema.map(schema => currentEnv.map(env => env.copy(schemas = schema +: env.schemas)))
            .getOrElse(currentEnv)
          currentSchema = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_SOURCE && currentEnv.nonEmpty) =>
          val role = try {
            Option(asyncReader.getAttributeValue(ATTR_ROLE)).filter(_.nonEmpty).map(Role.parse(_))
          } catch {
            case e: IllegalArgumentException => throw XQTSParseException(e.getMessage)
          }

          val file = asyncReader.getAttributeValue(ATTR_FILE)
          val validation = Option(asyncReader.getAttributeValue(ATTR_VALIDATION)).filter(_.nonEmpty)
          val uri = Option(asyncReader.getAttributeValue(ATTR_URI)).filter(_.nonEmpty)
          currentSource = Some(Source(role, xqtsPath.resolve(file), uri, validation.map(Validation.withName)))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_SOURCE && currentEnv.nonEmpty) =>
          currentEnv = currentSource.map(source => currentEnv.map(env => env.copy(sources = source +: env.sources)))
            .getOrElse(currentEnv)
          currentSource = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_PARAM && currentEnv.nonEmpty) =>
          val name = asyncReader.getAttributeValue(ATTR_NAME)
          val select = Option(asyncReader.getAttributeValue(ATTR_SELECT)).filter(_.nonEmpty)
          val as = Option(asyncReader.getAttributeValue(ATTR_AS)).filter(_.nonEmpty)
          val source = Option(asyncReader.getAttributeValue(ATTR_SOURCE)).filter(_.nonEmpty)
          val declared = Option(asyncReader.getAttributeValue(ATTR_DECLARED)).filter(_.nonEmpty).map(_.toBoolean).getOrElse(false)
          currentParam = Some(Param(name, select, as, source, declared))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_PARAM && currentEnv.nonEmpty) =>
          currentEnv = currentParam.map(param => currentEnv.map(env => env.copy(params = param +: env.params)))
            .getOrElse(currentEnv)
          currentParam = None

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_ENVIRONMENT) =>
          globalEnvironments = currentEnv.map(env => globalEnvironments + (env.name -> env)).getOrElse(globalEnvironments)
          currentEnv = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_TEST_SET) =>
          val name = asyncReader.getAttributeValue(ATTR_NAME)
          val file = asyncReader.getAttributeValue(ATTR_FILE)
          currentTestSetRef = Some(TestSetRef(xqtsVersion, name, xqtsPath.resolve(file)))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_TEST_SET) =>
          currentTestSetRef match {
            case Some(testSetRef) =>
              testSets match {
                case Right(testSetPattern) if (testSetPattern.matcher(testSetRef.name).matches() && !excludeTestSets.contains(testSetRef.name)) =>
                  testSetParserRouter ! ParseTestSet(testSetRef, testCases, features, specs, xmlVersions, xsdVersions, excludeTestCases, globalEnvironments, xqtsRunner)
                  matchedTestSet = true

                case Left(filterTestSets) if ((filterTestSets.isEmpty || filterTestSets.contains(testSetRef.name)) && !excludeTestSets.contains(testSetRef.name)) =>
                  testSetParserRouter ! ParseTestSet(testSetRef, testCases, features, specs, xmlVersions, xsdVersions, excludeTestCases, globalEnvironments, xqtsRunner)
                  matchedTestSet = true

                case _ =>
                  logger.debug(s"Filtered out test-set: ${testSetRef.name}")
              }
              currentTestSetRef = None
            case None =>
          }

        case CHARACTERS if (captureText) =>
          val characters: String = asyncReader.getText
          currentText = currentText.map(_ ++ characters).orElse(Some(characters))

        case EVENT_INCOMPLETE =>
          val read = channel.read(buf)
          if (read == -1) {
            asyncReader.getInputFeeder.endOfInput()
          } else {
            buf.flip()
            asyncReader.getInputFeeder.feedInput(buf)
          }

        case _ =>
          // we can ignore anything else
      }

      val updatedMatchedTestSets : Int = if (matchedTestSet) matchedTestSets + 1 else matchedTestSets
      parseAll(asyncReader.next(), asyncReader, xqtsPath, channel, buf, updatedMatchedTestSets)
    }

    val bufIO = cats.effect.Resource.make(IO { ByteBuffer.allocate(xmlParserBufferSize) })(buf => IO { buf.clear() })
    val fileIO = cats.effect.Resource.make(IO { Files.newByteChannel(xqtsPath.resolve(CATALOG_FILE)) })(channel => IO {channel.close() })
    val asyncParserIO = cats.effect.Resource.make(IO { PARSER_FACTORY.createAsyncForByteBuffer() } )(asyncReader => IO { asyncReader.close() })
    val parseIO = bufIO.use(buf =>
      fileIO.use(channel =>
        asyncParserIO.use(asyncReader =>
          IO {
            val event = asyncReader.next()
            parseAll(event, asyncReader, xqtsPath, channel, buf)
          }
        )
      )
    )

    implicit val runtime = IORuntime.global

    // TODO(AR) handleErrorWith for XQTSParseException and others?
    parseIO.unsafeRunSync()
  }
}

object XQTS3CatalogParserActor {
  /**
    * Name of the XQTS 3.1 Catalog file
    */
  private val CATALOG_FILE = "catalog.xml"
}
