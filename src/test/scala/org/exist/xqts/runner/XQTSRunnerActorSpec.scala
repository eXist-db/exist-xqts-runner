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

import java.nio.file.{Path, Paths}

import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.exist.xqts.runner.TestCaseRunnerActor.PassResult
import org.exist.xqts.runner.XQTSParserActor.{ParseComplete, TestSetRef}
import org.exist.xqts.runner.XQTSRunnerActor.{ParsedTestSet, ParsingTestSet, RanTestCase, RunningTestCase}
import org.exist.xqts.runner.XQTSResultsSerializerActor.{FinalizeSerialization, SerializedTestSetResults, TestSetResults}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt

object XQTSRunnerActorSpec {
  /**
   * Stands in for the results serializer router: forwards every message to a
   * TestProbe, so tests observe exactly what the runner sends and control when
   * (and whether) the SerializedTestSetResults ack is delivered.
   */
  class ForwardingSerializer(target: ActorRef) extends Actor {
    override def receive: Receive = {
      case msg => target ! msg
    }
  }
}

/**
 * Tests for the test-set accounting and serialization triggering of
 * XQTSRunnerActor, driving the message protocol directly.
 *
 * See https://github.com/eXist-db/exist-xqts-runner/issues/74: results must be
 * serialized exactly once, from parsed ground truth, with any late-arriving
 * result forcing a full rewrite — never a silently dropped test case.
 */
class XQTSRunnerActorSpec extends TestKit(ActorSystem("XQTSRunnerActorSpec"))
    with AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  import XQTSRunnerActorSpec._

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val catalogPath: Path = Paths.get("catalog.xml")

  private def testSetRef(name: String): TestSetRef =
    TestSetRef(XQTS_HEAD, name, Paths.get(s"$name.xml"))

  private def pass(testSet: TestSetRef, testCase: String): RanTestCase =
    RanTestCase(testSet, PassResult(testSet.name, testCase, 0L, 0L))

  private def newRunner(serializerProbe: TestProbe): ActorRef =
    system.actorOf(Props(new XQTSRunnerActor(
      4096,
      null,
      classOf[XQTSParserActor],
      classOf[XQTSResultsSerializerActor],
      None,
      Paths.get("target/test-output"),
      Some(Props(new ForwardingSerializer(serializerProbe.ref))))))

  "XQTSRunnerActor" should {

    "not serialize a test set before its ParsedTestSet has been received" in {
      val probe = TestProbe()
      val runner = newRunner(probe)
      val ts = testSetRef("ts-parse-gate")

      runner ! ParsingTestSet(ts)
      runner ! RunningTestCase(ts, "a")
      runner ! pass(ts, "a")
      // all *started* cases are complete here, but the parsed ground truth is
      // still unknown — serializing now is the exact bug of issue #74
      probe.expectNoMessage(300.millis)

      runner ! ParsedTestSet(ts, Seq("a", "b"))
      // case "b" is still outstanding
      probe.expectNoMessage(300.millis)

      runner ! RunningTestCase(ts, "b")
      runner ! pass(ts, "b")
      val results = probe.expectMsgType[TestSetResults]
      results.testSetRef shouldBe ts
      results.results.map(_.testCase) should contain theSameElementsAs Seq("a", "b")
      probe.expectNoMessage(300.millis)
    }

    "serialize exactly once when all results arrived before ParsedTestSet" in {
      val probe = TestProbe()
      val runner = newRunner(probe)
      val ts = testSetRef("ts-results-first")

      runner ! ParsingTestSet(ts)
      for (testCase <- Seq("a", "b", "c")) {
        runner ! RunningTestCase(ts, testCase)
        runner ! pass(ts, testCase)
      }
      probe.expectNoMessage(300.millis)

      runner ! ParsedTestSet(ts, Seq("a", "b", "c"))
      val results = probe.expectMsgType[TestSetResults]
      results.results.map(_.testCase) should contain theSameElementsAs Seq("a", "b", "c")
      probe.expectNoMessage(300.millis)
    }

    "rewrite a test set when a result arrives while its serialization is in flight" in {
      val probe = TestProbe()
      val runner = newRunner(probe)
      val ts = testSetRef("ts-dirty")

      runner ! ParsingTestSet(ts)
      runner ! ParsedTestSet(ts, Seq("a"))
      runner ! RunningTestCase(ts, "a")
      runner ! pass(ts, "a")
      probe.expectMsgType[TestSetResults].results.map(_.testCase) should contain only "a"

      // a further (unannounced) result lands while the ack is outstanding
      runner ! pass(ts, "b")
      probe.expectNoMessage(300.millis)

      // the ack must trigger a full rewrite including the late result
      runner ! SerializedTestSetResults(ts)
      val rewrite = probe.expectMsgType[TestSetResults]
      rewrite.testSetRef shouldBe ts
      rewrite.results.map(_.testCase) should contain theSameElementsAs Seq("a", "b")
    }

    "finalize only after the catalog parser has enumerated all matched test sets" in {
      val probe = TestProbe()
      val runner = newRunner(probe)
      val ts1 = testSetRef("ts-final-1")
      val ts2 = testSetRef("ts-final-2")

      runner ! ParseComplete(XQTS_HEAD, catalogPath, 2)

      runner ! ParsingTestSet(ts1)
      runner ! ParsedTestSet(ts1, Seq("a"))
      runner ! RunningTestCase(ts1, "a")
      runner ! pass(ts1, "a")
      probe.expectMsgType[TestSetResults]
      runner ! SerializedTestSetResults(ts1)
      // the second matched test set has not even been announced yet
      probe.expectNoMessage(300.millis)

      runner ! ParsingTestSet(ts2)
      runner ! ParsedTestSet(ts2, Seq("b"))
      runner ! RunningTestCase(ts2, "b")
      runner ! pass(ts2, "b")
      probe.expectMsgType[TestSetResults]
      runner ! SerializedTestSetResults(ts2)
      probe.expectMsg(FinalizeSerialization)
    }

    "not let an empty test set block finalization, nor serialize it" in {
      val probe = TestProbe()
      val runner = newRunner(probe)
      val tsEmpty = testSetRef("ts-empty")
      val ts = testSetRef("ts-nonempty")

      runner ! ParseComplete(XQTS_HEAD, catalogPath, 2)

      runner ! ParsingTestSet(tsEmpty)
      runner ! ParsedTestSet(tsEmpty, Seq.empty)

      runner ! ParsingTestSet(ts)
      runner ! ParsedTestSet(ts, Seq("a"))
      runner ! RunningTestCase(ts, "a")
      runner ! pass(ts, "a")

      val results = probe.expectMsgType[TestSetResults]
      results.testSetRef shouldBe ts
      runner ! SerializedTestSetResults(ts)
      probe.expectMsg(FinalizeSerialization)
    }
  }
}
