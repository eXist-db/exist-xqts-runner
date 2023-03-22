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
import akka.actor.{Actor, Props, Timers}
import XQTSRunnerActor._
import akka.routing.FromConfig
import org.exist.xqts.runner.TestCaseRunnerActor.TestResult
import org.exist.xqts.runner.XQTSParserActor.Feature.Feature
import org.exist.xqts.runner.XQTSParserActor.Spec.Spec
import org.exist.xqts.runner.XQTSParserActor.XmlVersion.XmlVersion
import org.exist.xqts.runner.XQTSParserActor.XsdVersion.XsdVersion
import org.exist.xqts.runner.XQTSParserActor.{Parse, ParseComplete, TestSetRef}
import org.exist.xqts.runner.XQTSResultsSerializerActor.{FinalizeSerialization, FinishedSerialization, SerializedTestSetResults, TestSetResults}
import org.exist.xqts.runner.qt3.XQTS3TestSetParserActor

import scala.annotation.unused
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
class XQTSRunnerActor(xmlParserBufferSize: Int, existServer: ExistServer, parserActorClass: Class[XQTSParserActor], serializerActorClass: Class[XQTSResultsSerializerActor], styleDir: Option[Path], outputDir: Path) extends Actor with Timers {

  private val logger = Logger(classOf[XQTSRunnerActor])
  private val resultsSerializerRouter = context.actorOf(FromConfig.props(Props(serializerActorClass, styleDir, outputDir)), name = "JUnitResultsSerializerRouter")

  private var started = System.currentTimeMillis()

  private var unparsedTestSets: Set[TestSetRef] = Set.empty
  private var unserializedTestSets: Set[TestSetRef] = Set.empty
  private var testCases : Map[TestSetRef, Set[String]] = Map.empty
  private var completedTestCases : Map[TestSetRef, Map[String, TestResult]] = Map.empty

  private case object TimerStatsKey
  private case object TimerPrintStats
  private case class Stats(unparsedTestSets: Int, testCases: (Int, Int), completedTestCases: (Int, Int), unserializedTestSets: Int) {
    def asMessage: String = s"XQTSRunnerActor Progress:\nunparsedTestSets=${unparsedTestSets}\ntestCases[sets/cases]=${testCases._1}/${testCases._2}\ncompletedTestCases[sets/cases]=${completedTestCases._1}/${completedTestCases._2}\nunserializedTestSets=${unserializedTestSets}"
  }
  private var previousStats: Stats = Stats(0, (0,0), (0,0), 0)
  private var unchangedStatsTicks = 0;

  override def receive: Receive = {

    case RunXQTS(xqtsVersion, xqtsPath, features, specs, xmlVersions, xsdVersions, maxCacheBytes, testSets, testCases, excludeTestSets, excludeTestCases) =>
      started = System.currentTimeMillis()
      logger.info(s"Running XQTS: ${XQTSVersion.label(xqtsVersion)}")

      if (logger.isDebugEnabled()) {
        // prints stats about the state of this actor (i.e. test set progress)
        import scala.concurrent.duration._
        timers.startTimerAtFixedRate(TimerStatsKey, TimerPrintStats, 5.seconds)
      }

      val readFileRouter = context.actorOf(FromConfig.props(Props(classOf[ReadFileActor])), name="ReadFileRouter")
      val commonResourceCacheActor = context.actorOf(Props(classOf[CommonResourceCacheActor], readFileRouter, maxCacheBytes))

      val testCaseRunnerRouter = context.actorOf(FromConfig.props(Props(classOf[TestCaseRunnerActor], existServer, commonResourceCacheActor)), name = "TestCaseRunnerRouter")

      val testSetParserRouter = context.actorOf(FromConfig.props(Props(classOf[XQTS3TestSetParserActor], xmlParserBufferSize, testCaseRunnerRouter)), "XQTS3TestSetParserRouter")
      val parserActor = context.actorOf(Props(parserActorClass, xmlParserBufferSize, testSetParserRouter), parserActorClass.getSimpleName)

      parserActor ! Parse(xqtsVersion, xqtsPath, features, specs, xmlVersions, xsdVersions, testSets, testCases, excludeTestSets, excludeTestCases)


    case TimerPrintStats =>
      val stats = Stats(this.unparsedTestSets.size, (this.testCases.size, this.testCases.values.foldLeft(0)(_ + _.size)), (this.completedTestCases.size, this.completedTestCases.values.foldLeft(0)(_ + _.size)), this.unserializedTestSets.size)
      logger.debug(stats.asMessage)
      if (stats.equals(previousStats)) {
        unchangedStatsTicks = unchangedStatsTicks + 1
      }

      // if stats have not changed for 5 ticks, dump some info about incomplete test sets
      if (unchangedStatsTicks > 5) {
        val incompleteTestSets = testCases
          .map { case (testSetRef, testCaseNames) => (testSetRef, testCaseNames.removedAll(completedTestCases.get(testSetRef).map(_.keySet).getOrElse(Set.empty)))}
          .filter {case (_, testCaseNames) => testCaseNames.nonEmpty}

        logger.debug(s"incompleteTestSets=${incompleteTestSets.map { case (testSetRef, testCaseNames) => (testSetRef.name, testCaseNames)}}")

        // reset
        unchangedStatsTicks = 0;
      }
      previousStats = stats

    case ParseComplete(xqtsVersion, _, matchedTestSets) =>
      logger.info(s"Matched $matchedTestSets Test Sets in XQTS ${XQTSVersion.toVersionName(xqtsVersion)}...")
      if (matchedTestSets == 0) {
        logger.warn("Nothing to do! Did you specify your Test Set names/patterns correctly?")
        shutdown()
      }

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
      logger.info(s"Starting execution of Test Case: ${testSetRef.name}/${testCase}...")
      testCases = addTestCase(testCases, testSetRef, testCase)

    case RanTestCase(testSetRef, testResult) =>
      logger.info(s"Finished execution of Test Case: ${testSetRef.name}/${testResult.testCase}.")
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
      logger.info(s"Completed XQTS in (${System.currentTimeMillis() - started} ms)")
      shutdown()
  }

  private def shutdown(): Unit = {
    if (logger.isDebugEnabled()) {
      timers.cancel(TimerStatsKey)
    }
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

  @unused
  private def add(map: Map[TestSetRef, Map[String, Option[TestResult]]], testSetRef: TestSetRef) : Map[TestSetRef, Map[String, Option[TestResult]]] = {
    if (map.contains(testSetRef)) {
      map
    } else {
      map + (testSetRef -> Map.empty)
    }
  }

  @unused
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

  @unused
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

  @unused
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
  case class RunXQTS(xqtsVersion: XQTSVersion, xqtsPath: Path, features: Set[Feature], specs: Set[Spec], xmlVersions: Set[XmlVersion], xsdVersions: Set[XsdVersion], maxCacheBytes: Long, testSets: Either[Set[String], Pattern], testCases: Either[Set[String], Pattern], excludeTestSets: Set[String], excludeTestCases: Set[String])

  case class ParsingTestSet(testSetRef: TestSetRef)
  case class ParsedTestSet(testSetRef: TestSetRef, testCases: Seq[String])
  case class RunningTestCase(testSetRef: TestSetRef, testCase: String)
  case class RanTestCase(testSetRef: TestSetRef, testResult: TestResult)
}
