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

import org.exist.xqts.runner.IgnorableWrapper.IGNORABLE_WRAPPER_ELEM_NAME
import org.xmlunit.XMLUnitException
import org.xmlunit.builder.{DiffBuilder, Input}
import org.xmlunit.diff.{Comparison, ComparisonFormatter, ComparisonResult, ComparisonType, DifferenceEvaluator, DifferenceEvaluators}

/**
 * The XML comparison behind the QT3 {@code assert-xml} assertion.
 *
 * <p>XMLUnit's default evaluator classifies a namespace-prefix difference as merely
 * <em>similar</em>, so {@code checkForSimilar()} on its own treats {@code <a:x xmlns:a="u"/>} and
 * {@code <b:x xmlns:b="u"/>} as equal. That is right only when the test says so: the catalog's
 * {@code ignore-prefixes="true"} marks the rare results whose prefixes are system-generated. For
 * every other {@code assert-xml} -- 1,759 of the 1,833 in the suite -- the prefixes are part of the
 * expected result, and a prefix difference is a failure.</p>
 */
object XmlAssertComparison {

  /** Promotes a namespace-prefix difference from similar to different. */
  private val prefixesAreSignificant: DifferenceEvaluator = new DifferenceEvaluator {
    override def evaluate(comparison: Comparison, outcome: ComparisonResult): ComparisonResult =
      if (outcome == ComparisonResult.SIMILAR && comparison.getType == ComparisonType.NAMESPACE_PREFIX) {
        ComparisonResult.DIFFERENT
      } else {
        outcome
      }
  }

  /**
   * Finds the differences between an expected and an actual serialized XML result.
   *
   * @param expected       the expected XML.
   * @param actual         the actual XML.
   * @param normalizeWs    whether to collapse insignificant whitespace (XQFTTS Fragment comparisons).
   * @param ignorePrefixes the assertion's {@code ignore-prefixes} attribute.
   * @param formatter      formats the description of any differences.
   * @return a description of the differences, None if there are none, or the comparison error.
   */
  def findDifferences(expected: String, actual: String, normalizeWs: Boolean, ignorePrefixes: Boolean,
                      formatter: ComparisonFormatter): Either[XMLUnitException, Option[String]] = {
    try {
      val expectedSource = Input.fromString(s"<$IGNORABLE_WRAPPER_ELEM_NAME>$expected</$IGNORABLE_WRAPPER_ELEM_NAME>").build()
      val actualSource = Input.fromString(s"<$IGNORABLE_WRAPPER_ELEM_NAME>$actual</$IGNORABLE_WRAPPER_ELEM_NAME>").build()
      val builder = DiffBuilder.compare(expectedSource)
        .withTest(actualSource)
      // XQFTTS Fragment comparisons additionally collapse insignificant whitespace.
      val builderWithWs = if (normalizeWs) builder.normalizeWhitespace() else builder
      val evaluator =
        if (ignorePrefixes) DifferenceEvaluators.Default
        else DifferenceEvaluators.chain(DifferenceEvaluators.Default, prefixesAreSignificant)
      val diff = builderWithWs
        .withNodeFilter(new org.xmlunit.util.Predicate[org.w3c.dom.Node] {
          override def test(node: org.w3c.dom.Node): Boolean =
            !(node.getNodeType == org.w3c.dom.Node.TEXT_NODE && node.getTextContent.trim.isEmpty)
        })
        .withDifferenceEvaluator(evaluator)
        .withComparisonFormatter(formatter)
        .checkForSimilar()
        .build()

      if (diff.hasDifferences) {
        Right(Some(diff.toString))
      } else {
        Right(None)
      }
    } catch {
      case e: XMLUnitException =>
        Left(e)
    }
  }
}
