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
import akka.actor.{Actor, ActorRef}
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.fasterxml.aalto.AsyncXMLStreamReader.EVENT_INCOMPLETE
import com.fasterxml.aalto.{AsyncByteBufferFeeder, AsyncXMLStreamReader}
import grizzled.slf4j.Logger

import javax.xml.namespace.{NamespaceContext, QName}
import javax.xml.stream.XMLStreamConstants.{CDATA, CHARACTERS, END_DOCUMENT, END_ELEMENT, START_ELEMENT}
import net.sf.saxon.value.AnyURIValue
import org.exist.xqts.runner.{Stack, XQTSParseException}
import org.exist.xqts.runner.TestCaseRunnerActor.{AssumptionFailedResult, RunTestCase}
import org.exist.xqts.runner.XQTSParserActor.Feature.Feature
import org.exist.xqts.runner.XQTSParserActor.Spec.Spec
import org.exist.xqts.runner.XQTSParserActor.XmlVersion.XmlVersion
import org.exist.xqts.runner.XQTSParserActor.XsdVersion.XsdVersion
import org.exist.xqts.runner.XQTSParserActor.{missingDependencies, _}
import org.exist.xqts.runner.XQTSRunnerActor.{ParsedTestSet, ParsingTestSet, RanTestCase, RunningTestCase}
import org.exist.xqts.runner.qt3.XQTS3TestSetParserActor._
import scalaz.syntax.either._

import scala.annotation.tailrec

/**
  * Parser an XQTS 3.1 test-set XML file.
  *
  * For each test-case parsed from the test-set,
  * an execution request of {@link RunTestCase} will be sent
  * to a {@link TestCaseRunnerActor}.
  *
  * @param xmlParserBufferSize the maximum buffer size to use for each XML
  *                            document from the XQTS which we parse.
  * @param testCaseRunnerActor a reference to an actor which can execute
  *                            an XQTS test-case.
  *
  * @author Adam Retter <adam@evolvedinary.com>
  */
class XQTS3TestSetParserActor(xmlParserBufferSize: Int, testCaseRunnerActor: ActorRef) extends Actor {

  private val logger = Logger(classOf[XQTS3TestSetParserActor])

  private var currentEnv: Option[Environment] = None
  private var currentSchema: Option[Schema] = None
  private var currentSource: Option[Source] = None
  private var currentResource: Option[Resource] = None
  private var currentParam: Option[Param] = None
  private var currentNs: Option[Namespace] = None
  private var currentCollection: Option[Collection] = None

  private var captureText = false

  private var currentText: Option[String] = None
  private var currentCreated: Option[Created] = None
  private var currentTestSet: Option[TestSet] = None
  private var parsingTestCases: Boolean = false
  private var currentModified: Option[Modified] = None
  private var currentLink: Option[Link] = None
  private var currentDependency: Option[Dependency] = None
  private var currentTestCase: Option[TestCase] = None
  private var currentResult: Option[Stack[Result]] = None

  private var currentNormalizeSpace: Option[Boolean] = None
  private var currentFile: Option[Path] = None
  private var currentFlags: Option[String] = None
  private var currentIgnorePrefixes: Option[Boolean] = None

  private var environments: Map[String, Environment] = Map.empty

  override def receive: Receive = {
    case ParseTestSet(testSetRef, testCases, features, specs, xmlVersions, xsdVersions, excludeTestCases, globalEnvironments, manager) =>
      logger.info(s"Parsing XQTS TestSet: ${testSetRef.file}...")
      manager ! ParsingTestSet(testSetRef)
      val parsedTestSet = parseTestSet(testSetRef, testCases, features, specs, xmlVersions, xsdVersions, excludeTestCases, globalEnvironments, manager)
      logger.info(s"Parsed XQTS TestSet: ${testSetRef.file} OK.")
      manager ! ParsedTestSet(testSetRef, parsedTestSet.map(_.testCases.map(_.name)).getOrElse(Seq.empty))
  }

  /**
    * Parses an XQTS test-set XML file.
    *
    * @param testSetRef a reference to the test-set.
    * @param testCases the test-cases to parse, or an empty set to parse all.
    * @param features the enabled XQTS features to support.
    * @param specs the enabled XQTS specs to support.
    * @param xmlVersions the enabled XQTS XML versions to support.
    * @param xsdVersions the enabled XQTS XSD versions to support.
    * @param excludeTestCases the names of any test cases to exclude from the parse.
    * @param globalEnvironments any environemnts which were defined globally in the XQTS catalog.
    * @param manager the supervising manager actor.
    *
    * @return Some of the parsed test-set, or None.
    */
  private def parseTestSet(testSetRef: TestSetRef, testCases: Set[String], features: Set[Feature], specs: Set[Spec], xmlVersions: Set[XmlVersion], xsdVersions: Set[XsdVersion], excludeTestCases: Set[String], globalEnvironments: Map[String, Environment], manager: ActorRef) : Option[TestSet] = {

    /**
      * Get's an environment.
      *
      * First searches the environments of the test set,
      * and the falls-back and searches the environments
      * of the catalog.
      *
      * @param ref The reference to the environment.
      * @return The environment if known.
      */
    def getEnvironment(ref: String) : Option[Environment] = {
      environments.get(ref)
        .orElse(globalEnvironments.get(ref))
    }

    /**
      * The asynchronous STaX parsing loop,
      * implemented as a recursive function.
      *
      * @param event the current STaX event.
      * @param asyncReader the asynchronous XML stream reader.
      * @param testSetDir the path to the directory containing the test-set.
      * @param channel the file channel for the {@code asyncReader}.
      * @param buf the buffer for the {@code asyncReader}.
      *
      * @return Some of the parsed test-set, or None.
      *
      * @throws XQTSParseException if an error happens during parsing.
      */
    @tailrec
    @throws[XQTSParseException]
    def parseAll(event: Int, asyncReader: AsyncXMLStreamReader[AsyncByteBufferFeeder], testSetDir: Path, channel: SeekableByteChannel, buf: ByteBuffer) : Option[TestSet] = {
      event match {
        case END_DOCUMENT =>
          return currentTestSet  // exit parse

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_ENVIRONMENT) =>
          asyncReader.getAttributeValueOptNE(ATTR_REF) match {
            case None =>
              // Definition of an environment
              val name = asyncReader.getAttributeValue(ATTR_NAME)
              currentEnv = Some(Environment(name))

            case Some(ref) =>
              // Reference to an (already) defined environment
              getEnvironment(ref) match {
                case env @ Some(_) =>
                  currentTestCase = currentTestCase.map(testCase => testCase.copy(environment = env))
                case None =>
                  throw new XQTSParseException(s"Environment '$ref' was referenced, but not defined. Test set '${testSetRef.name}'${currentTestCase.map(testCase => s" for test case '${testCase.name}'").getOrElse("")}")
              }
          }

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
          val uri = asyncReader.getAttributeValueOpt(ATTR_URI).map(new AnyURIValue(_))
          currentSchema = Some(Schema(uri, file.map(testSetDir.resolve(_))))

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_DESCRIPTION) =>
          captureText = true

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_DESCRIPTION) =>
          if (currentTestCase.nonEmpty && currentTestCase.flatMap(_.description).isEmpty) {
            currentTestCase = currentTestCase.map(testCase => testCase.copy(description = currentText))
          } else if (currentSchema.nonEmpty) {
            currentSchema = currentSchema.map(schema => schema.copy(description = currentText))
          } else if (currentSource.nonEmpty) {
            currentSource = currentSource.map(source => source.copy(description = currentText))
          } else if (currentResource.nonEmpty) {
            currentResource = currentResource.map(resource => resource.copy(description = currentText))
          } else if (currentTestSet.nonEmpty && !parsingTestCases && currentTestSet.flatMap(_.description).isEmpty) {
            currentTestSet = currentTestSet.map(testSet => testSet.copy(description = currentText))
          }
          currentText = None
          captureText = false

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_LINK && currentTestSet.nonEmpty) =>
          val `type` = asyncReader.getAttributeValue(ATTR_TYPE)
          val document = asyncReader.getAttributeValue(ATTR_DOCUMENT)
          val section = asyncReader.getAttributeValueOpt(ATTR_SECTION)
          currentLink = Some(Link(`type`, document, section))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_LINK && currentTestSet.nonEmpty) =>
          currentTestSet = currentLink.map(link => currentTestSet.map(testSet => testSet.copy(links = link +: testSet.links))).getOrElse(currentTestSet)
          currentLink = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_MODULE && currentTestCase.nonEmpty) =>
          val uri = new AnyURIValue(asyncReader.getAttributeValue(ATTR_URI))
          val file = asyncReader.getAttributeValue(ATTR_FILE)
          val module = Module(uri, testSetDir.resolve(file))
          currentTestCase = currentTestCase.map(testCase => testCase.copy(modules = module +: testCase.modules))

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_DEPENDENCY) =>
          val `type` = asyncReader.getAttributeValue(ATTR_TYPE)
          val value = asyncReader.getAttributeValue(ATTR_VALUE)
          val satisfied = asyncReader.getAttributeValueOptNE(ATTR_SATISFIED).map(_.toBoolean).getOrElse(true)
          currentDependency = Some(Dependency(DependencyType.fromXqtsName(`type`), value, satisfied))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_DEPENDENCY) =>
          if(currentTestCase.nonEmpty) {
            currentTestCase = currentDependency.map(dependency => currentTestCase.map(testCase => testCase.copy(dependencies = dependency +: testCase.dependencies))).getOrElse(currentTestCase)
          } else if(currentTestSet.nonEmpty && !parsingTestCases) {
            currentTestSet = currentDependency.map(dependency => currentTestSet.map(testSet => testSet.copy(dependencies = dependency +: testSet.dependencies))).getOrElse(currentTestSet)
          }
          currentDependency = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_CREATED) =>
          val by = asyncReader.getAttributeValue(ATTR_BY)
          val on = asyncReader.getAttributeValue(ATTR_ON)
          currentCreated = Some(Created(by, on))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_CREATED) =>
          if(currentTestCase.nonEmpty) {
            currentTestCase = currentTestCase.map(testCase => testCase.copy(created = currentCreated))
          } else if (currentSchema.nonEmpty) {
            currentSchema = currentSchema.map(schema => schema.copy(created = currentCreated))
          } else if (currentSource.nonEmpty) {
            currentSource = currentSource.map(source => source.copy(created = currentCreated))
          } else if (currentResource.nonEmpty) {
            currentResource = currentResource.map(resource => resource.copy(created = currentCreated))
          }
          currentCreated = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_MODIFIED) =>
          val by = asyncReader.getAttributeValue(ATTR_BY)
          val on = asyncReader.getAttributeValue(ATTR_ON)
          val change = asyncReader.getAttributeValue(ATTR_CHANGE)
          currentModified = Some(Modified(by, on, change))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_MODIFIED) =>
          if(currentTestCase.nonEmpty) {
            currentTestCase = currentModified.map(modified => currentTestCase.map(testCase => testCase.copy(modifications = modified +: testCase.modifications))).getOrElse(currentTestCase)
          } else if (currentSchema.nonEmpty) {
            currentSchema = currentModified.flatMap(modified => currentSchema.map(schema => schema.copy(modifications = modified +: schema.modifications)))
          } else if (currentSource.nonEmpty) {
            currentSource = currentModified.flatMap(modified => currentSource.map(source => source.copy(modifications = modified +: source.modifications)))
          } else if (currentResource.nonEmpty) {
            currentResource = currentModified.flatMap(modified => currentResource.map(resource => resource.copy(modifications = modified +: resource.modifications)))
          }
          currentModified = None

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_SCHEMA && currentEnv.nonEmpty) =>
          currentEnv = currentSchema.map(schema => currentEnv.map(env => env.copy(schemas =  schema +: env.schemas)))
            .getOrElse(currentEnv)
          currentSchema = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_SOURCE && currentEnv.nonEmpty) =>
          val role = asyncReader.getAttributeValueOptNE(ATTR_ROLE)
          val file = asyncReader.getAttributeValue(ATTR_FILE)
          val validation = asyncReader.getAttributeValueOptNE(ATTR_VALIDATION)
          val uri = asyncReader.getAttributeValueOptNE(ATTR_URI)
          currentSource = Some(Source(role, testSetDir.resolve(file), uri, validation.map(Validation.withName)))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_SOURCE && currentEnv.nonEmpty && currentCollection.nonEmpty) =>
          currentCollection = currentSource.map(source => currentCollection.map(collection => collection.copy(sources = source +: collection.sources)))
            .getOrElse(currentCollection)
          currentSource = None

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_SOURCE && currentEnv.nonEmpty) =>
          currentEnv = currentSource.map(source => currentEnv.map(env => env.copy(sources = source +: env.sources)))
            .getOrElse(currentEnv)
          currentSource = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_RESOURCE && currentEnv.nonEmpty) =>
          val file = asyncReader.getAttributeValue(ATTR_FILE)
          val uri = asyncReader.getAttributeValue(ATTR_URI)
          val mediaType = asyncReader.getAttributeValueOptNE(ATTR_MEDIA_TYPE)
          val encodingType = asyncReader.getAttributeValueOptNE(ATTR_ENCODING)
          currentResource = Some(Resource(testSetDir.resolve(file), uri, mediaType, encodingType))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_RESOURCE && currentEnv.nonEmpty) =>
          currentEnv = currentResource.map(resource => currentEnv.map(env => env.copy(resources = resource +: env.resources)))
            .getOrElse(currentEnv)
          currentResource = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_PARAM && currentEnv.nonEmpty) =>
          val name = asyncReader.getAttributeValue(ATTR_NAME)
          val select = asyncReader.getAttributeValueOptNE(ATTR_SELECT)
          val as = asyncReader.getAttributeValueOptNE(ATTR_AS)
          val source = asyncReader.getAttributeValueOptNE(ATTR_SOURCE)
          val declared = asyncReader.getAttributeValueOptNE(ATTR_DECLARED).map(_.toBoolean).getOrElse(false)
          currentParam = Some(Param(name, select, as, source, declared))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_PARAM && currentEnv.nonEmpty) =>
          currentEnv = currentParam.map(param => currentEnv.map(env => env.copy(params = param +: env.params)))
            .getOrElse(currentEnv)
          currentParam = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_CONTEXT_ITEM && currentEnv.nonEmpty) =>
          val select = asyncReader.getAttributeValueOptNE(ATTR_SELECT)
          currentEnv = currentEnv.map(_.copy(contextItem = select))

        case START_ELEMENT if(asyncReader.getLocalName == ELEM_DECIMAL_FORMAT && currentEnv.nonEmpty) =>
          val name = asyncReader.getAttributeValueOptNE(ATTR_NAME).map(toQName(_, asyncReader.getNamespaceContext))
          val decimalSeparator = asyncReader.getAttributeValueOptNE(ATTR_DECIMAL_SEPARATOR).map(_.codePointAt(0))
          val exponentSeparator = asyncReader.getAttributeValueOptNE(ATTR_EXPONENT_SEPARATOR).map(_.codePointAt(0))
          val groupingSeparator = asyncReader.getAttributeValueOptNE(ATTR_GROUPING_SEPARATOR).map(_.codePointAt(0))
          val zeroDigit = asyncReader.getAttributeValueOptNE(ATTR_ZERO_DIGIT).map(_.codePointAt(0))
          val digit = asyncReader.getAttributeValueOptNE(ATTR_DIGIT).map(_.codePointAt(0))
          val minusSign = asyncReader.getAttributeValueOptNE(ATTR_MINUS_SIGN).map(_.codePointAt(0))
          val percent = asyncReader.getAttributeValueOptNE(ATTR_PERCENT).map(_.codePointAt(0))
          val perMille = asyncReader.getAttributeValueOptNE(ATTR_PER_MILLE).map(_.codePointAt(0))
          val patternSeparator = asyncReader.getAttributeValueOptNE(ATTR_PATTERN_SEPARATOR).map(_.codePointAt(0))
          val infinity = asyncReader.getAttributeValueOptNE(ATTR_INFINITY)
          val nan = asyncReader.getAttributeValueOptNE(ATTR_NAN)
          val decimalFormat = DecimalFormat(name, decimalSeparator, exponentSeparator, groupingSeparator, zeroDigit, digit, minusSign, percent, perMille, patternSeparator, infinity, nan)
          currentEnv = currentEnv.map(env => env.copy(decimalFormats = decimalFormat +: env.decimalFormats))

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_COLLECTION && currentEnv.nonEmpty) =>
          val uri = new AnyURIValue(asyncReader.getAttributeValue(ATTR_URI))
          currentCollection = Some(Collection(uri))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_COLLECTION && currentEnv.nonEmpty) =>
          currentEnv = currentCollection.map(collection => currentEnv.map(env => env.copy(collections = collection +: env.collections)))
            .getOrElse(currentEnv)
          currentCollection = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_STATIC_BASE_URI && currentEnv.nonEmpty) =>
          val uri = asyncReader.getAttributeValueOptNE(ATTR_URI)
          currentEnv = currentEnv.map(_.copy(staticBaseUri = uri))

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_COLLATION && currentEnv.nonEmpty) =>
          val uri = asyncReader.getAttributeValueOptNE(ATTR_URI).map(new URI(_))
          val default = asyncReader.getAttributeValueOptNE(ATTR_DEFAULT).map(_.toBoolean).getOrElse(false)
          currentEnv = currentEnv.map(_.copy(collation = uri.map(Collation(_, default))))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_ENVIRONMENT) =>
          if (currentTestCase.isEmpty) {
            // global environment for the test set
            environments = currentEnv.map(env => environments + (env.name -> env)).getOrElse(environments)
          } else if (currentEnv.nonEmpty) {
            // environment specific to the test case
            currentTestCase.flatMap(_.environment) match {
              case Some(_) =>
                throw new XQTSParseException(s"Environment was defined for test case, but test case already has an environment: '${testSetRef.name}'${currentTestCase.map(testCase => s" for test case '${testCase.name}'").getOrElse("")}")
              case None =>
                currentTestCase = currentTestCase.map(_.copy(environment = currentEnv))
            }
          }
          currentEnv = None

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_TEST_SET) =>
          val name = asyncReader.getAttributeValue(ATTR_NAME)
          val covers = asyncReader.getAttributeValue(ATTR_COVERS)
          currentTestSet = Some(TestSet(name, covers))

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_TEST_SET) =>
          parsingTestCases = false
          // there is nothing more we need to do here, although in future we could add further logging here

        case START_ELEMENT if (asyncReader.getLocalName == ELEM_TEST_CASE) =>
          parsingTestCases = true
          val name = asyncReader.getAttributeValue(ATTR_NAME)
          val covers = asyncReader.getAttributeValue(ATTR_COVERS)
          if ((testCases.isEmpty || testCases.contains(name)) && !excludeTestCases.contains(name)) {
            currentTestCase = Some(TestCase(testSetRef.file, name, covers))
          } else {
            logger.debug(s"Filtered out test-case: ${name}")
          }

        case START_ELEMENT if (currentTestCase.nonEmpty && asyncReader.getLocalName == ELEM_TEST) =>
          val attrFile = asyncReader.getAttributeValueOpt(ATTR_FILE)
          currentFile = attrFile.map(file => testSetRef.file.resolveSibling(file))
          captureText = true

        case END_ELEMENT if (currentTestCase.nonEmpty && asyncReader.getLocalName == ELEM_TEST) =>
          currentTestCase = currentTestCase.map(testCase => testCase.copy(test = currentText.flatMap(text => Some(text.left[Path])).orElse(currentFile.map(_.right[String]))))
          currentFile = None
          currentText = None
          captureText = false

        case START_ELEMENT if(currentTestCase.nonEmpty && asyncReader.getLocalName == ELEM_RESULT) =>
          currentResult = Some(Stack.empty)

        case START_ELEMENT if(asyncReader.getLocalName == ELEM_ALL_OF) =>
          currentResult = currentResult.map(addAssertion(_)(AllOf(List.empty)))

        case START_ELEMENT if(asyncReader.getLocalName == ELEM_ANY_OF) =>
          currentResult = currentResult.map(addAssertion(_)(AnyOf(List.empty)))

        case START_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT_STRING_VALUE) =>
          currentNormalizeSpace = asyncReader.getAttributeValueOptNE(ATTR_NORMALIZE_SPACE).map(_.toBoolean)
          captureText = true

        case START_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT_XML) =>
          val attrFile = asyncReader.getAttributeValueOptNE(ATTR_FILE)
          currentFile = attrFile.map(file => testSetRef.file.resolveSibling(file))
          currentIgnorePrefixes = asyncReader.getAttributeValueOptNE(ATTR_IGNORE_PREFIXES).map(_.toBoolean)
          captureText = true

        case START_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_SERIALIZATION_MATCHES) =>
          val attrFile = asyncReader.getAttributeValueOptNE(ATTR_FILE)
          currentFile = attrFile.map(file => testSetRef.file.resolveSibling(file))
          currentFlags = asyncReader.getAttributeValueOptNE(ATTR_FLAGS)
          captureText = true

        // assertions where we just need their text()
        case START_ELEMENT if(currentResult.nonEmpty && (
          asyncReader.getLocalName == ELEM_ASSERT
            || asyncReader.getLocalName == ELEM_ASSERT_COUNT
            || asyncReader.getLocalName == ELEM_ASSERT_DEEP_EQ
            || asyncReader.getLocalName == ELEM_ASSERT_EQ
            || asyncReader.getLocalName == ELEM_ASSERT_PERMUTATION
            || asyncReader.getLocalName == ELEM_ASSERT_TYPE
          )) =>
          captureText = true

        case START_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT_SERIALIZATION_ERROR) =>
          val code = asyncReader.getAttributeValue(ATTR_CODE)
          val assertion = AssertSerializationError(code)
          currentResult = currentResult.map(addAssertion(_)(assertion))

        case END_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT) =>
          currentText match {
            case Some(text) =>
              val assertion = Assert(text)
              currentResult = currentResult.map(addAssertion(_)(assertion))
            case None =>
              throw new XQTSParseException(s"No text captured for element: $ELEM_ASSERT")
          }
          currentText = None
          captureText = false

        case END_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT_COUNT) =>
          currentText match {
            case Some(text) =>
              val assertion = AssertCount(text.trim.toInt)
              currentResult = currentResult.map(addAssertion(_)(assertion))
            case None =>
              throw new XQTSParseException(s"No text captured for element: $ELEM_ASSERT_COUNT")
          }
          currentText = None
          captureText = false

        case END_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT_DEEP_EQ) =>
          currentText match {
            case Some(text) =>
              val assertion = AssertDeepEquals(text)
              currentResult = currentResult.map(addAssertion(_)(assertion))
            case None =>
              throw new XQTSParseException(s"No text captured for element: $ELEM_ASSERT_DEEP_EQ")
          }
          currentText = None
          captureText = false

        case END_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT_EQ) =>
          currentText match {
            case Some(text) =>
              val assertion = AssertEq(text)
              currentResult = currentResult.map(addAssertion(_)(assertion))
            case None =>
              throw new XQTSParseException(s"No text captured for element: $ELEM_ASSERT_EQ")
          }
          currentText = None
          captureText = false

        case END_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT_PERMUTATION) =>
          currentText match {
            case Some(text) =>
              val assertion = AssertPermutation(text)
              currentResult = currentResult.map(addAssertion(_)(assertion))
            case None =>
              throw new XQTSParseException(s"No text captured for element: $ELEM_ASSERT_PERMUTATION")
          }
          currentText = None
          captureText = false

        case END_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT_STRING_VALUE) =>
          val assertion = AssertStringValue(currentText.getOrElse(""), currentNormalizeSpace.getOrElse(false))
          currentResult = currentResult.map(addAssertion(_)(assertion))
          currentNormalizeSpace = None
          currentText = None
          captureText = false

        case END_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT_TYPE) =>
          currentText match {
            case Some(text) =>
              val assertion = AssertType(text)
              currentResult = currentResult.map(addAssertion(_)(assertion))
            case None =>
              throw new XQTSParseException(s"No text captured for element: $ELEM_ASSERT_TYPE")
          }
          currentText = None

        case END_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ASSERT_XML) =>
          currentText
              .flatMap(text => Some(text.left[Path]))
              .orElse(currentFile.map(_.right[String]))
              .map(AssertXml(_, currentIgnorePrefixes.getOrElse(false))) match {
            case Some(assertXml) =>
              currentResult = currentResult.map(addAssertion(_)(assertXml))
            case None =>
              throw new XQTSParseException(s"No text captured for element: $ELEM_ASSERT_XML")
          }
          currentFile = None
          currentIgnorePrefixes = None
          currentText = None

        case END_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_SERIALIZATION_MATCHES) =>
          currentText
            .flatMap(text => Some(text.left[Path]))
            .orElse(currentFile.map(_.right[String]))
            .map(SerializationMatches(_, currentFlags)) match {
            case Some(serializationMatched) =>
              currentResult = currentResult.map(addAssertion(_)(serializationMatched))
            case None =>
              throw new XQTSParseException(s"No text captured for element: $ELEM_SERIALIZATION_MATCHES")
          }
          currentFile = None
          currentFlags = None
          currentText = None

        case END_ELEMENT if(asyncReader.getLocalName == ELEM_ASSERT_EMPTY) =>
          currentResult = currentResult.map(addAssertion(_)(AssertEmpty))

        case END_ELEMENT if(asyncReader.getLocalName == ELEM_ASSERT_FALSE) =>
          currentResult = currentResult.map(addAssertion(_)(AssertFalse))

        case END_ELEMENT if(asyncReader.getLocalName == ELEM_ASSERT_TRUE) =>
          currentResult = currentResult.map(addAssertion(_)(AssertTrue))

        case END_ELEMENT if(asyncReader.getLocalName == ELEM_ALL_OF || asyncReader.getLocalName == ELEM_ANY_OF) =>
          currentResult = currentResult.map(stepOutAssertions)

        case START_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_ERROR) =>
          val code = asyncReader.getAttributeValue(ATTR_CODE)
          val assertion = Error(code)
          currentResult = currentResult.map(addAssertion(_)(assertion))

        case END_ELEMENT if(currentResult.nonEmpty && asyncReader.getLocalName == ELEM_RESULT) =>
          currentTestCase = currentTestCase.map(_.copy(result = currentResult.flatMap(_.peekOption)))
          currentResult = None

        case END_ELEMENT if (asyncReader.getLocalName == ELEM_TEST_CASE) =>
          currentTestCase match {
            case Some(testCase) =>
              if (testCases.isEmpty || testCases.contains(testCase.name)) {
                currentTestSet = currentTestSet.map(testSet => testSet.copy(testCases = testCase +: testSet.testCases))
                val allDependencies : Seq[Dependency] = currentTestSet.map(testSet => (testSet.dependencies.toSet ++ testCase.dependencies.toSet).toSeq).getOrElse(testCase.dependencies)
                val missingDeps: Missing = missingDependencies(allDependencies, features, specs, xmlVersions, xsdVersions)
                if (missingDeps.isEmpty) {
                  testCaseRunnerActor ! RunTestCase(testSetRef.copy(name = currentTestSet.map(_.name).getOrElse("<UNKNOWN>")), testCase, manager)
                } else {
                  // assumption failed
                  //TODO(AR) replace these two messages with AssumptionFailed() - and let the manager deal with it
                  manager ! RunningTestCase(testSetRef, testCase.name)
                  manager ! RanTestCase(testSetRef, AssumptionFailedResult(testSetRef.name, testCase.name, 0, 0, s"Test's dependencies were not satisfiable. Missing: [${missingDeps.mkString(", ")}]"))
                }
              } else {
                throw new XQTSParseException("SHOULD BE FILTERED ON START_ELEMENT")
              }
              currentTestCase = None
            case None =>
              //throw new XQTSParseException(s"Encountered end of element: $ELEM_TEST_CASE, but no test case was captured")
          }

        case CHARACTERS if (captureText) =>
          val characters: String = asyncReader.getText
          currentText = currentText.map(_ ++ characters).orElse(Some(characters))

        case CDATA if(captureText) =>
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

      parseAll(asyncReader.next(), asyncReader, testSetDir, channel, buf)
    }

    def toQName(s: String, namespaceContext: NamespaceContext): QName = {
      val idx = s.indexOf(':')
      if (idx > -1) {
        val prefix = s.substring(0, idx)
        val localPart = s.substring(idx + 1)

        Option(namespaceContext.getNamespaceURI(prefix))
            .orElse(currentEnv
              .map(_.namespaces).getOrElse(List.empty)
              .filter(_.prefix.equals(prefix))
              .map(_.uri.toString)
              .headOption)
            .map(ns => new QName(ns, localPart, prefix))
            .getOrElse(QName.valueOf(s))
      } else {
        QName.valueOf(s)
      }
    }

    def addAssertion(currentAssertions: Stack[Result])(assertion: Result) : Stack[Result] = {
      currentAssertions.peekOption match {
        case Some(head) if (head.isInstanceOf[Assertions] && !assertion.isInstanceOf[Assertions]) =>
          // head of the stack is itself a list of assertions, and the assertion to add is not a list of assertions
          currentAssertions.replace(head.asInstanceOf[Assertions] :+ assertion)

        case Some(_) =>
            currentAssertions.push(assertion)

        case None =>
          Stack(assertion)
      }
    }

    def stepOutAssertions(currentAssertions: Stack[Result]) : Stack[Result] = {
      if (currentAssertions.size >= 2) {
        if (currentAssertions.peek.isInstanceOf[Assertions]) {
          val(prevHead, stack) = currentAssertions.pop()
          val head = stack.peek
          if (head.isInstanceOf[Assertions]) {
            stack.replace(head.asInstanceOf[Assertions] :+ prevHead)
          } else {
            throw new XQTSParseException("Unable to associate assertions object to non-assertions object")
          }
        } else {
          throw new XQTSParseException("Unable to associate non-assertions object")
        }
      } else {
        currentAssertions
      }
    }

    val bufIO = cats.effect.Resource.make(IO { ByteBuffer.allocate(xmlParserBufferSize) })(buf => IO { buf.clear() })
    val fileIO = cats.effect.Resource.make(IO { Files.newByteChannel(testSetRef.file) })(channel => IO {channel.close() })
    val asyncParserIO = cats.effect.Resource.make(IO { PARSER_FACTORY.createAsyncForByteBuffer() } )(asyncReader => IO { asyncReader.close() })
    val parseIO = bufIO.use(buf =>
      fileIO.use(channel =>
        asyncParserIO.use(asyncReader =>
          IO {
            val event = asyncReader.next()
            parseAll(event, asyncReader, testSetRef.file.getParent, channel, buf)
          }
        )
      )
    )

    implicit val runtime = IORuntime.global

    // TODO(AR) handleErrorWith for XQTSParseException and others?
    parseIO.unsafeRunSync()
  }
}

object XQTS3TestSetParserActor {
  case class ParseTestSet(testSetRef: TestSetRef, testCases: Set[String], features: Set[Feature], specs: Set[Spec], xmlVersions: Set[XmlVersion], xsdVersions: Set[XsdVersion], excludeTestCases: Set[String], globalEnvironments: Map[String, Environment], manager: ActorRef)
}
