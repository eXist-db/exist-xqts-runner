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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.xmlunit.diff.DefaultComparisonFormatter

class XmlAssertComparisonSpec extends AnyWordSpec with Matchers {

  private val formatter = new DefaultComparisonFormatter()

  private def differs(expected: String, actual: String, ignorePrefixes: Boolean): Boolean =
    XmlAssertComparison.findDifferences(expected, actual, normalizeWs = false, ignorePrefixes, formatter) match {
      case Right(result) => result.isDefined
      case Left(e) => fail(e)
    }

  "assert-xml without ignore-prefixes" should {

    "report a result whose element prefix differs from the expected one" in {
      differs("""<a:x xmlns:a="urn:u"/>""", """<b:x xmlns:b="urn:u"/>""", ignorePrefixes = false) shouldBe true
    }

    "report a result whose attribute prefix differs from the expected one" in {
      differs("""<x xmlns:a="urn:u" a:att="1"/>""", """<x xmlns:b="urn:u" b:att="1"/>""", ignorePrefixes = false) shouldBe true
    }

    "report a prefixed element where the default namespace was expected" in {
      differs("""<x xmlns="urn:u"/>""", """<p:x xmlns:p="urn:u"/>""", ignorePrefixes = false) shouldBe true
    }

    "accept an identical result" in {
      differs("""<a:x xmlns:a="urn:u" a:att="1"/>""", """<a:x xmlns:a="urn:u" a:att="1"/>""", ignorePrefixes = false) shouldBe false
    }

    "accept a result that differs only in unused namespace declarations" in {
      differs("""<a:x xmlns:a="urn:u"/>""", """<a:x xmlns:a="urn:u" xmlns:z="urn:unused"/>""", ignorePrefixes = false) shouldBe false
    }
  }

  "assert-xml with ignore-prefixes=\"true\"" should {

    "accept a result whose prefixes differ from the expected ones" in {
      differs("""<a:x xmlns:a="urn:u" a:att="1"/>""", """<b:x xmlns:b="urn:u" b:att="1"/>""", ignorePrefixes = true) shouldBe false
    }
  }

  "assert-xml in either mode" should {

    "report a namespace URI difference, which is never a prefix question" in {
      differs("""<a:x xmlns:a="urn:u"/>""", """<a:x xmlns:a="urn:v"/>""", ignorePrefixes = false) shouldBe true
      differs("""<a:x xmlns:a="urn:u"/>""", """<a:x xmlns:a="urn:v"/>""", ignorePrefixes = true) shouldBe true
    }
  }
}
