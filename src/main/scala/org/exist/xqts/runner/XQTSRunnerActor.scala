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

import java.nio.file.Path
import java.util.regex.Pattern

import akka.actor.{Actor, Props}
import XQTSRunnerActor._
import akka.routing.FromConfig
import grizzled.slf4j.Logger
import org.exist.xqts.runner.TestCaseRunnerActor.TestResult
import org.exist.xqts.runner.XQTSParserActor.Feature.Feature
import org.exist.xqts.runner.XQTSParserActor.Spec.Spec
import org.exist.xqts.runner.XQTSParserActor.XmlVersion.XmlVersion
import org.exist.xqts.runner.XQTSParserActor.XsdVersion.XsdVersion
import org.exist.xqts.runner.XQTSParserActor.{Parse, ParseComplete, TestSetRef}
import org.exist.xqts.runner.XQTSResultsSerializerActor.{FinalizeSerialization, FinishedSerialization, SerializedTestSetResults, TestSetResults}
import org.exist.xqts.runner.qt3.XQTS3TestSetParserActor
import scalaz.\/

import scala.collection.immutable.Map

/**
  * The supervisor actor which will coordinate all other
  * actors in parsing, executing, and reporting on an XQTS
  * invocation.
  *
  * @param xmlParserBufferSize the maximum buffer size to use for each XML
  *                            document from the XQTS which we parse.
  * @param existServer a reference to an eXist-db server.
  * @param serializerActorClass the class to use for serializing the results of the XQTS.
  * @param outputDir the directory to serialize XQTS results to.
  */
class XQTSRunnerActor(xmlParserBufferSize: Int, existServer: ExistServer, parserActorClass: Class[XQTSParserActor], serializerActorClass: Class[XQTSResultsSerializerActor], outputDir: Path) extends Actor {

  private val logger = Logger(classOf[XQTSRunnerActor])
  private val resultsSerializerRouter = context.actorOf(FromConfig.props(Props(serializerActorClass, outputDir)), name = "JUnitResultsSerializerRouter")

  private var unparsedTestSets: Set[TestSetRef] = Set.empty
  private var unserializedTestSets: Set[TestSetRef] = Set.empty
  private var testCases : Map[TestSetRef, Set[String]] = Map.empty
  private var completedTestCases : Map[TestSetRef, Map[String, TestResult]] = Map.empty

  override def receive: Receive = {

    case RunXQTS(xqtsVersion, xqtsPath, features, specs, xmlVersions, xsdVersions, maxCacheBytes, testSets, testCases, excludeTestSets, excludeTestCases) =>
      logger.info(s"Running XQTS: ${XQTSVersion.label(xqtsVersion)}")
      val readFileRouter = context.actorOf(FromConfig.props(Props(classOf[ReadFileActor])), name="ReadFileRouter")
      val commonResourceCacheActor = context.actorOf(Props(classOf[CommonResourceCacheActor], readFileRouter, maxCacheBytes))
      val testCaseRunnerRouter = context.actorOf(FromConfig.props(Props(classOf[TestCaseRunnerActor], existServer, commonResourceCacheActor)), name = "TestCaseRunnerRouter")
      val testSetParserRouter = context.actorOf(FromConfig.props(Props(classOf[XQTS3TestSetParserActor], xmlParserBufferSize, testCaseRunnerRouter)), "XQTS3TestSetParserRouter")
      val parserActor = context.actorOf(Props(parserActorClass, xmlParserBufferSize, testSetParserRouter), parserActorClass.getSimpleName)

      parserActor ! Parse(xqtsVersion, xqtsPath, features, specs, xmlVersions, xsdVersions, testSets, testCases, excludeTestSets, excludeTestCases)

    case ParseComplete(xqtsVersion, xqtsPath) =>
      // there is nothing we need to do here

    case ParsingTestSet(testSetRef) =>
      unparsedTestSets += testSetRef

    case ParsedTestSet(testSetRef, parsedTestCases) =>
      testCases = addTestCases(testCases, testSetRef, parsedTestCases)
      unparsedTestSets -= testSetRef

      // have we completed testing an entire TestSet? NOTE: tests could have finished executing before parse complete message arrives!
      if (isTestSetCompleted(testSetRef)) {
        // serialize the TestSet results
        resultsSerializerRouter ! TestSetResults(testSetRef, completedTestCases(testSetRef).values.toSeq)
        unserializedTestSets += testSetRef
      }

    case RunningTestCase(testSetRef, testCase) =>
      testCases = addTestCase(testCases, testSetRef, testCase)

    case RanTestCase(testSetRef, testResult) =>
      completedTestCases = mergeTestCases(completedTestCases, testSetRef, testResult)

      // have we completed testing an entire TestSet?
      if (isTestSetCompleted(testSetRef)) {
        // serialize the TestSet results
        resultsSerializerRouter ! TestSetResults(testSetRef, completedTestCases(testSetRef).values.toSeq)
        unserializedTestSets += testSetRef
      }

    case SerializedTestSetResults(testSetRef) =>
      unserializedTestSets -= testSetRef
      if (allTestSetsCompleted()) {
        // all TestSet results have been sent to the serializer
        resultsSerializerRouter ! FinalizeSerialization
      }

    case FinishedSerialization =>
      // all tests have run, and serialization is finished
      context.stop(self)
      context.system.terminate()
  }

  private def isTestSetCompleted(testSetRef: TestSetRef) : Boolean = {
    unparsedTestSets.contains(testSetRef) == false &&
      completedTestCases.get(testSetRef).map(_.keySet)
        .flatMap(completed => testCases.get(testSetRef).map(_ == completed))
        .getOrElse(false)
  }

  private def allTestSetsCompleted(): Boolean = {
    unserializedTestSets.isEmpty &&
      unparsedTestSets.isEmpty &&
        !testCases.keySet.map(isTestSetCompleted(_)).contains(false)
  }

  private def add(map: Map[TestSetRef, Map[String, Option[TestResult]]], testSetRef: TestSetRef) : Map[TestSetRef, Map[String, Option[TestResult]]] = {
    if (map.contains(testSetRef)) {
      map
    } else {
      map + (testSetRef -> Map.empty)
    }
  }

  private def add(map: Map[TestSetRef, Map[String, Option[TestResult]]], testSetRef: TestSetRef, testCase: String) : Map[TestSetRef, Map[String, Option[TestResult]]] = {
    if (map.contains(testSetRef)) {
      if (!map(testSetRef).contains(testCase)) {
        map + (testSetRef -> (map(testSetRef) + (testCase -> None)))
      } else {
        map
      }
    } else {
      map + (testSetRef -> Map(testCase -> None))
    }
  }

  private def add(map: Map[TestSetRef, Map[String, Option[TestResult]]], testSetRef: TestSetRef, testCases: Seq[String]) : Map[TestSetRef, Map[String, Option[TestResult]]] = {
    if (map.contains(testSetRef)) {
      map + (testSetRef -> (map(testSetRef) ++ testCases.filterNot(map(testSetRef).contains(_)).map((_, None)).toMap))
    } else {
      map + (testSetRef -> testCases.map((_, None)).toMap)
    }
  }

  private def addTestCase(map: Map[TestSetRef, Set[String]], testSetRef: TestSetRef, testCase: String) : Map[TestSetRef, Set[String]] = {
    if (map.contains(testSetRef)) {
      map + (testSetRef -> (map(testSetRef) + testCase))
    } else {
      map + (testSetRef -> Set(testCase))
    }
  }

  private def addTestCases(map: Map[TestSetRef, Set[String]], testSetRef: TestSetRef, testCases: Seq[String]) : Map[TestSetRef, Set[String]] = {
    if (map.contains(testSetRef)) {
      map + (testSetRef -> (map(testSetRef) ++ testCases.toSet))
    } else {
      map + (testSetRef -> testCases.toSet)
    }
  }

  private def removeOutstanding(map: Map[TestSetRef, Set[String]], testSetRef: TestSetRef, testCase: String) : Map[TestSetRef, Set[String]] = {
    if (map.contains(testSetRef)) {
      val newValueSet = map(testSetRef) - testCase
      if (newValueSet.isEmpty) {
        map - testSetRef
      } else {
        map + (testSetRef -> newValueSet)
      }
    } else {
      map
    }
  }

  private def mergeTestCases(map: Map[TestSetRef, Map[String, TestResult]], testSetRef: TestSetRef, testResult: TestResult): Map[TestSetRef, Map[String, TestResult]] = {
    if (map.contains(testSetRef)) {
      map + (testSetRef -> (map(testSetRef) + (testResult.testCase -> testResult)))
    } else {
      map + (testSetRef -> Map(testResult.testCase -> testResult))
    }
  }
}

/**
  * Objects and Classes that are used for executing an XQTS.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
object XQTSRunnerActor {
  case class RunXQTS(xqtsVersion: XQTSVersion, xqtsPath: Path, features: Set[Feature], specs: Set[Spec], xmlVersions: Set[XmlVersion], xsdVersions: Set[XsdVersion], maxCacheBytes: Long, testSets: Set[String] \/ Pattern, testCases: Set[String], excludeTestSets: Set[String], excludeTestCases: Set[String])

  case class ParsingTestSet(testSetRef: TestSetRef)
  case class ParsedTestSet(testSetRef: TestSetRef, testCases: Seq[String])
  case class RunningTestCase(testSetRef: TestSetRef, testCase: String)
  case class RanTestCase(testSetRef: TestSetRef, testResult: TestResult)
}
