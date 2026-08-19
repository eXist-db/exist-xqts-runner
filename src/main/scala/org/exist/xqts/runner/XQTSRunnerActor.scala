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
import org.apache.pekko.actor.{Actor, OneForOneStrategy, Props, SupervisorStrategy, Timers}
import XQTSRunnerActor._
import scala.util.control.NonFatal
import org.apache.pekko.routing.FromConfig
import org.exist.xqts.runner.TestCaseRunnerActor.TestResult
import org.exist.xqts.runner.XQTSParserActor.Feature.Feature
import org.exist.xqts.runner.XQTSParserActor.Spec.Spec
import org.exist.xqts.runner.XQTSParserActor.XmlVersion.XmlVersion
import org.exist.xqts.runner.XQTSParserActor.XsdVersion.XsdVersion
import org.exist.xqts.runner.XQTSParserActor.{Parse, ParseComplete, TestSetRef}
import org.exist.xqts.runner.XQTSResultsSerializerActor.{FinalizeSerialization, FinishedSerialization, SerializedTestSetResults, TestSetResults}
import org.exist.xqts.runner.qt3.XQTS3TestSetParserActor

import scala.annotation.unused

/**
 * The supervisor actor which will coordinate all other
 * actors in parsing, executing, and reporting on an XQTS
 * invocation.
 *
 * @param xmlParserBufferSize  the maximum buffer size to use for each XML
 *                             document from the XQTS which we parse.
 * @param existServer          a reference to an eXist-db server.
 * @param serializerActorClass the class to use for serializing the results of the XQTS.
 * @param outputDir            the directory to serialize XQTS results to.
 * @param serializerRouterProps overrides the Props of the results serializer
 *                              router; only intended for tests, which use it to
 *                              substitute a probe for the real serializer.
 */
class XQTSRunnerActor(xmlParserBufferSize: Int, existServer: ExistServer, parserActorClass: Class[XQTSParserActor], serializerActorClass: Class[XQTSResultsSerializerActor], styleDir: Option[Path], outputDir: Path, serializerRouterProps: Option[Props]) extends Actor with Timers {

  private val logger = Logger(classOf[XQTSRunnerActor])

  /* Pool routers escalate a routee failure by default, which restarts the
   * router and recreates ALL of its routees — wiping, for example, every
   * pending test case parked in the TestCaseRunnerActor pool, which would
   * then silently never be reported (issue #74). Resume instead: the failing
   * message is dropped (and logged by Pekko), routee state is preserved, and
   * fatal errors such as OutOfMemoryError still escalate. */
  private val resumeOnNonFatal = OneForOneStrategy() {
    case NonFatal(_) => SupervisorStrategy.Resume
  }

  private val resultsSerializerRouter = context.actorOf(serializerRouterProps.getOrElse(FromConfig.withSupervisorStrategy(resumeOnNonFatal).props(Props(serializerActorClass, styleDir, outputDir))), name = "JUnitResultsSerializerRouter")

  private var started = System.currentTimeMillis()

  /* Serialization follows parsed ground truth: a test set's results are
   * serialized once, when its ParsedTestSet has been received AND every known
   * case (parsed list ∪ observed-started cases) has a recorded result. Results
   * that arrive while a serialization is in flight mark the set dirty, and the
   * serialization ack triggers a full rewrite, so the JUnit file on disk is
   * always a complete superset. See
   * https://github.com/eXist-db/exist-xqts-runner/issues/74. */

  /** Test sets announced by the parser (ParsingTestSet received). */
  private var knownTestSets: Set[TestSetRef] = Set.empty
  /** Test sets whose ParsedTestSet (ground-truth case list) has been received. */
  private var parsedTestSets: Set[TestSetRef] = Set.empty
  /** Number of test sets the catalog parser matched (from ParseComplete). */
  private var expectedTestSetCount: Option[Int] = None
  /** Per test set: the case names known so far (parsed list ∪ started cases). */
  private var testCases: Map[TestSetRef, Set[String]] = Map.empty
  private var completedTestCases: Map[TestSetRef, Map[String, TestResult]] = Map.empty
  /** Test sets for which TestSetResults has been sent at least once. */
  private var dispatchedSerializations: Set[TestSetRef] = Set.empty
  /** Test sets with a TestSetResults sent and its ack still pending. */
  private var inFlightSerializations: Set[TestSetRef] = Set.empty
  /** Test sets that received further results while a serialization was in flight. */
  private var dirtyTestSets: Set[TestSetRef] = Set.empty

  private case object TimerStatsKey

  private case object TimerPrintStats

  private case object TimerWatchdogKey

  private case object TimerWatchdogCheck

  private case class Stats(unparsedTestSets: Int, testCases: (Int, Int), completedTestCases: (Int, Int), inFlightSerializations: Int) {
    def asMessage: String = s"XQTSRunnerActor Progress:\nunparsedTestSets=${unparsedTestSets}\ntestCases[sets/cases]=${testCases._1}/${testCases._2}\ncompletedTestCases[sets/cases]=${completedTestCases._1}/${completedTestCases._2}\ninFlightSerializations=${inFlightSerializations}"
  }

  private var previousStats: Stats = Stats(0, (0, 0), (0, 0), 0)
  private var unchangedStatsTicks = 0;

  /** Number of consecutive watchdog ticks with no progress before forcing shutdown. 10s tick x 6 = 60s stall timeout. */
  private val STALL_TIMEOUT_TICKS = 6
  private var watchdogPreviousCompletedCount = 0
  private var watchdogStalledTicks = 0
  private var startedTestCases: Map[TestSetRef, Set[String]] = Map.empty

  // Forced-shutdown drain state. Once `forceSerializeAndShutdown` has been
  // called, we send any pending TestSetResults and then wait for their
  // SerializedTestSetResults acks before triggering actor-system termination —
  // otherwise the children get killed mid-write and the in-flight results land
  // in deadLetters. The deadline thread is the hard backstop in case the
  // serializer itself is wedged.
  private var forcedShutdown = false
  private var finalizeSent = false
  /** Hard deadline (ms) for the forced-shutdown drain before we give up and terminate anyway. */
  private val FORCED_DRAIN_DEADLINE_MS = 60000L

  override def receive: Receive = {

    case RunXQTS(xqtsVersion, xqtsPath, features, specs, xmlVersions, xsdVersions, maxCacheBytes, testSets, testCases, excludeTestSets, excludeTestCases) =>
      started = System.currentTimeMillis()
      logger.info(s"Running XQTS: ${XQTSVersion.label(xqtsVersion)}")

      {
        import scala.concurrent.duration._
        // watchdog: detect stalls where no test cases complete for 120 seconds
        timers.startTimerAtFixedRate(TimerWatchdogKey, TimerWatchdogCheck, 10.seconds)
        if (logger.isDebugEnabled()) {
          // prints stats about the state of this actor (i.e. test set progress)
          timers.startTimerAtFixedRate(TimerStatsKey, TimerPrintStats, 5.seconds)
        }
      }

      val readFileRouter = context.actorOf(FromConfig.props(Props(classOf[ReadFileActor])), name = "ReadFileRouter")
      val commonResourceCacheActor = context.actorOf(Props(classOf[CommonResourceCacheActor], readFileRouter, maxCacheBytes))

      val testCaseRunnerRouter = context.actorOf(FromConfig.withSupervisorStrategy(resumeOnNonFatal).props(Props(classOf[TestCaseRunnerActor], existServer, commonResourceCacheActor)), name = "TestCaseRunnerRouter")

      // For XQFTTS, the catalog parser sends directly to the test case runner (no test-set parser needed).
      // For QT3, the catalog parser sends to a test-set parser pool which then sends to test case runners.
      val parserActor = if (xqtsVersion == XQTS_FTTS_1_0) {
        context.actorOf(Props(parserActorClass, xmlParserBufferSize, testCaseRunnerRouter, existServer), parserActorClass.getSimpleName)
      } else {
        val testSetParserRouter = context.actorOf(FromConfig.withSupervisorStrategy(resumeOnNonFatal).props(Props(classOf[XQTS3TestSetParserActor], xmlParserBufferSize, testCaseRunnerRouter)), "XQTS3TestSetParserRouter")
        context.actorOf(Props(parserActorClass, xmlParserBufferSize, testSetParserRouter), parserActorClass.getSimpleName)
      }

      parserActor ! Parse(xqtsVersion, xqtsPath, features, specs, xmlVersions, xsdVersions, testSets, testCases, excludeTestSets, excludeTestCases)


    case TimerPrintStats =>
      val stats = Stats(this.knownTestSets.size - this.parsedTestSets.size, (this.testCases.size, this.testCases.values.foldLeft(0)(_ + _.size)), (this.completedTestCases.size, this.completedTestCases.values.foldLeft(0)(_ + _.size)), this.inFlightSerializations.size)
      logger.debug(stats.asMessage)
      if (stats.equals(previousStats)) {
        unchangedStatsTicks = unchangedStatsTicks + 1
      }

      // if stats have not changed for 5 ticks, dump some info about incomplete test sets
      if (unchangedStatsTicks > 5) {
        val incompleteTestSets = testCases
          .map { case (testSetRef, testCaseNames) => (testSetRef, testCaseNames.removedAll(completedTestCases.get(testSetRef).map(_.keySet).getOrElse(Set.empty))) }
          .filter { case (_, testCaseNames) => testCaseNames.nonEmpty }

        logger.debug(s"incompleteTestSets=${incompleteTestSets.map { case (testSetRef, testCaseNames) => (testSetRef.name, testCaseNames) }}")

        // reset
        unchangedStatsTicks = 0;
      }
      previousStats = stats

    case TimerWatchdogCheck =>
      val currentCompletedCount = this.completedTestCases.values.foldLeft(0)(_ + _.size)
      if (currentCompletedCount > watchdogPreviousCompletedCount) {
        watchdogStalledTicks = 0
      } else if (this.testCases.nonEmpty) {
        // only count stall ticks after we've started receiving test cases
        watchdogStalledTicks += 1
      }
      watchdogPreviousCompletedCount = currentCompletedCount

      if (watchdogStalledTicks >= STALL_TIMEOUT_TICKS) {
        val totalCases = this.testCases.values.foldLeft(0)(_ + _.size)
        // Identify which test cases started but never completed (hung tests)
        val hungTests = for {
          (testSetRef, started) <- startedTestCases
          completed = completedTestCases.getOrElse(testSetRef, Map.empty).keySet
          testCase <- started -- completed
        } yield s"${testSetRef.name}/$testCase"
        logger.warn(s"Watchdog: no progress for ${STALL_TIMEOUT_TICKS * 10}s ($currentCompletedCount/$totalCases cases completed, ${inFlightSerializations.size} serializations in flight). Forcing shutdown.")
        if (hungTests.nonEmpty) {
          logger.warn(s"Hung test cases (started but never completed): ${hungTests.mkString(", ")}")
        }
        forceSerializeAndShutdown()
      }

    case ParseComplete(xqtsVersion, _, matchedTestSets) =>
      logger.info(s"Matched $matchedTestSets Test Sets in XQTS ${XQTSVersion.toVersionName(xqtsVersion)}...")
      if (matchedTestSets == 0) {
        logger.warn("Nothing to do! Did you specify your Test Set names/patterns correctly?")
        shutdown()
      } else {
        expectedTestSetCount = Some(matchedTestSets)
        maybeFinalize()
      }

    case ParsingTestSet(testSetRef) =>
      knownTestSets += testSetRef

    case ParsedTestSet(testSetRef, parsedTestCases) =>
      knownTestSets += testSetRef
      testCases = addTestCases(testCases, testSetRef, parsedTestCases)
      parsedTestSets += testSetRef
      // NOTE: results could have finished arriving before the ParsedTestSet message
      maybeSerialize(testSetRef)
      maybeFinalize()

    case RunningTestCase(testSetRef, testCase) =>
      logger.info(s"Starting execution of Test Case: ${testSetRef.name}/${testCase}...")
      testCases = addTestCase(testCases, testSetRef, testCase)
      startedTestCases = addTestCase(startedTestCases, testSetRef, testCase)

    case RanTestCase(testSetRef, testResult) =>
      logger.info(s"Finished execution of Test Case: ${testSetRef.name}/${testResult.testCase}.")
      testCases = addTestCase(testCases, testSetRef, testResult.testCase)
      completedTestCases = mergeTestCases(completedTestCases, testSetRef, testResult)

      if (dispatchedSerializations.contains(testSetRef)) {
        // a result arrived after this test set was already serialized (can only
        // happen under forced shutdown, or if a case reports without having
        // been parsed or started): rewrite the JUnit file so it contains the
        // full result set
        if (inFlightSerializations.contains(testSetRef)) {
          dirtyTestSets += testSetRef
        } else {
          dispatchSerialization(testSetRef)
        }
      } else {
        maybeSerialize(testSetRef)
      }

    case SerializedTestSetResults(testSetRef) =>
      inFlightSerializations -= testSetRef
      if (dirtyTestSets.contains(testSetRef)) {
        // further results arrived while the serialization was in flight: rewrite
        dispatchSerialization(testSetRef)
      } else {
        maybeFinalize()
      }

    case FinishedSerialization =>
      // all tests have run, and serialization is finished
      logger.info(s"Completed XQTS in (${System.currentTimeMillis() - started} ms)")
      shutdown()
  }

  private def forceSerializeAndShutdown(): Unit = {
    // Idempotent: a second watchdog tick (or a re-entry from another path)
    // must not start a parallel drain.
    if (forcedShutdown) {
      return
    }
    forcedShutdown = true

    // Stop the watchdog now that we're committed to draining; we don't want
    // another stall tick to log "Forcing shutdown" while serialization is
    // already in progress.
    timers.cancel(TimerWatchdogKey)

    // The JUnit output of any test set that has not completed will be partial;
    // say so explicitly, per test set, so a stalled run cannot be mistaken for
    // a complete one.
    for (testSetRef <- knownTestSets if !isTestSetCompleted(testSetRef)) {
      val expected = testCases.getOrElse(testSetRef, Set.empty).size
      val completed = completedTestCases.getOrElse(testSetRef, Map.empty).size
      logger.warn(s"Forced shutdown: test set ${testSetRef.name} is INCOMPLETE ($completed/$expected recorded cases); its JUnit output will be partial")
    }

    // Serialize the latest results of every test set that has any and is not
    // already up to date on disk. Test sets that are in flight (and possibly
    // dirty) drain through the SerializedTestSetResults handler.
    for {
      (testSetRef, results) <- completedTestCases
      if results.nonEmpty
      if !inFlightSerializations.contains(testSetRef)
      if dirtyTestSets.contains(testSetRef) || !dispatchedSerializations.contains(testSetRef)
    } {
      dispatchSerialization(testSetRef)
    }

    if (inFlightSerializations.isEmpty) {
      // Nothing in flight — fall straight through the normal finalize/finish
      // handshake so the serializer router gets a chance to flush its own state.
      maybeFinalize()
    } else {
      logger.info(s"Draining ${inFlightSerializations.size} in-flight TestSetResults before shutdown (deadline ${FORCED_DRAIN_DEADLINE_MS / 1000}s)")
    }

    // Hard backstop: if the serializer never acks (e.g. wedged write), give
    // up on the drain after FORCED_DRAIN_DEADLINE_MS and shut down anyway.
    // The 30s deadline thread inside shutdown() is a separate backstop for
    // actor-system termination itself.
    val backstop = new Thread(() => {
      try {
        Thread.sleep(FORCED_DRAIN_DEADLINE_MS)
        logger.warn(s"Forced-shutdown drain did not complete within ${FORCED_DRAIN_DEADLINE_MS / 1000}s; terminating anyway (${inFlightSerializations.size} TestSetResults still unacked)")
        // Re-enter via a self-message so shutdown() runs on the actor thread.
        self ! FinishedSerialization
      } catch {
        case _: InterruptedException =>
      }
    }, "xqts-forced-drain-backstop")
    backstop.setDaemon(true)
    backstop.start()
  }

  private var shutdownCalled = false
  private def shutdown(): Unit = {
    if (shutdownCalled) {
      return
    }
    shutdownCalled = true
    timers.cancel(TimerWatchdogKey)
    if (logger.isDebugEnabled()) {
      timers.cancel(TimerStatsKey)
    }
    // Hard deadline: force exit if actor system termination hangs.
    // BrokerPool threads can block the Pekko dispatcher, preventing
    // CoordinatedShutdown from completing. This standalone thread
    // runs outside Pekko and forces JVM exit after 30 seconds.
    logger.info("Starting 30-second shutdown deadline thread")
    val deadline = new Thread(() => {
      try {
        Thread.sleep(30000)
        logger.warn("Actor system shutdown did not complete within 30 seconds, forcing exit")
        Runtime.getRuntime.halt(0)
      } catch {
        case _: InterruptedException =>
          logger.info("Shutdown deadline thread interrupted (clean exit)")
      }
    }, "xqts-shutdown-deadline")
    deadline.setDaemon(true)
    deadline.start()
    context.stop(self)
    context.system.terminate()
  }

  /** Send the current results of a test set to the serializer, replacing any
   * previously written JUnit file for it (the serializer's write truncates). */
  private def dispatchSerialization(testSetRef: TestSetRef): Unit = {
    dispatchedSerializations += testSetRef
    inFlightSerializations += testSetRef
    dirtyTestSets -= testSetRef
    resultsSerializerRouter ! TestSetResults(testSetRef, completedTestCases.getOrElse(testSetRef, Map.empty).values.toSeq)
  }

  /** Serialize a test set's results if (and only if) its ground truth is fully
   * accounted for and it has not been serialized before. */
  private def maybeSerialize(testSetRef: TestSetRef): Unit = {
    if (!dispatchedSerializations.contains(testSetRef)
        && isTestSetCompleted(testSetRef)
        && completedTestCases.getOrElse(testSetRef, Map.empty).nonEmpty) {
      dispatchSerialization(testSetRef)
    }
  }

  /** A test set is completed when its parsed ground-truth case list has been
   * received AND every known case (parsed ∪ started) has a recorded result.
   * `subsetOf` (rather than equality) means a case that completes without ever
   * having been announced cannot wedge the set into "never complete". */
  private def isTestSetCompleted(testSetRef: TestSetRef): Boolean = {
    parsedTestSets.contains(testSetRef) &&
      testCases.getOrElse(testSetRef, Set.empty)
        .subsetOf(completedTestCases.getOrElse(testSetRef, Map.empty).keySet)
  }

  /** The run is complete when the catalog parser has enumerated all matched
   * test sets, every announced test set is completed, and no serializations
   * are outstanding. */
  private def allTestSetsCompleted(): Boolean = {
    expectedTestSetCount.contains(knownTestSets.size) &&
      knownTestSets.forall(isTestSetCompleted) &&
      inFlightSerializations.isEmpty &&
      dirtyTestSets.isEmpty
  }

  private def maybeFinalize(): Unit = {
    // Under a forced shutdown, hung-but-never-completed test cases mean
    // `allTestSetsCompleted()` will never be true; relax to "all serialization
    // acks received" so the drain can finalize. Also guard against sending
    // FinalizeSerialization more than once.
    val readyToFinalize =
      !finalizeSent &&
        (allTestSetsCompleted() || (forcedShutdown && inFlightSerializations.isEmpty && dirtyTestSets.isEmpty))
    if (readyToFinalize) {
      finalizeSent = true
      resultsSerializerRouter ! FinalizeSerialization
    }
  }

  @unused
  private def add(map: Map[TestSetRef, Map[String, Option[TestResult]]], testSetRef: TestSetRef): Map[TestSetRef, Map[String, Option[TestResult]]] = {
    if (map.contains(testSetRef)) {
      map
    } else {
      map + (testSetRef -> Map.empty)
    }
  }

  @unused
  private def add(map: Map[TestSetRef, Map[String, Option[TestResult]]], testSetRef: TestSetRef, testCase: String): Map[TestSetRef, Map[String, Option[TestResult]]] = {
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
  private def add(map: Map[TestSetRef, Map[String, Option[TestResult]]], testSetRef: TestSetRef, testCases: Seq[String]): Map[TestSetRef, Map[String, Option[TestResult]]] = {
    if (map.contains(testSetRef)) {
      map + (testSetRef -> (map(testSetRef) ++ testCases.filterNot(map(testSetRef).contains(_)).map((_, None)).toMap))
    } else {
      map + (testSetRef -> testCases.map((_, None)).toMap)
    }
  }

  private def addTestCase(map: Map[TestSetRef, Set[String]], testSetRef: TestSetRef, testCase: String): Map[TestSetRef, Set[String]] = {
    if (map.contains(testSetRef)) {
      map + (testSetRef -> (map(testSetRef) + testCase))
    } else {
      map + (testSetRef -> Set(testCase))
    }
  }

  private def addTestCases(map: Map[TestSetRef, Set[String]], testSetRef: TestSetRef, testCases: Seq[String]): Map[TestSetRef, Set[String]] = {
    if (map.contains(testSetRef)) {
      map + (testSetRef -> (map(testSetRef) ++ testCases.toSet))
    } else {
      map + (testSetRef -> testCases.toSet)
    }
  }

  @unused
  private def removeOutstanding(map: Map[TestSetRef, Set[String]], testSetRef: TestSetRef, testCase: String): Map[TestSetRef, Set[String]] = {
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
