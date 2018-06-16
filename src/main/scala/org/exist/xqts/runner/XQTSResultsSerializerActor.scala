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

import akka.actor.Actor
import org.exist.xqts.runner.TestCaseRunnerActor.TestResult
import org.exist.xqts.runner.XQTSParserActor.TestSetRef

/**
  * An Actor that serializes the
  * results of executing the tests
  * within an XQTS.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
trait XQTSResultsSerializerActor extends Actor {
}

/**
  * Objects and Classes that are used for serializing the
  * an results of executing the tests
  * within an XQTS.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
object XQTSResultsSerializerActor {
  case class TestSetResults(testSetRef: TestSetRef, results: Seq[TestResult])
  case class SerializedTestSetResults(testSetRef: TestSetRef)
  case object FinalizeSerialization
  case object FinishedSerialization
}
