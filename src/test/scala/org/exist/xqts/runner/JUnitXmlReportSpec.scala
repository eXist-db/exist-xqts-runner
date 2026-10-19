/*
 * Copyright (C) 2026  The eXist Project
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

import java.io.{ByteArrayInputStream, StringWriter}
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import javax.xml.parsers.DocumentBuilderFactory

import org.exist.xqts.runner.JUnitXmlReport._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.w3c.dom.{Document, Element}

class JUnitXmlReportSpec extends AnyWordSpec with Matchers {

  private def report(testCases: Seq[TestCase], properties: Seq[(String, String)] = Seq.empty): String = {
    val writer = new StringWriter()
    JUnitXmlReport.write(writer, "XQTS_HEAD.fn-doc", properties, testCases, LocalDateTime.of(2026, 10, 19, 12, 45, 30, 123))
    writer.toString
  }

  private def parse(xml: String): Document = {
    val dbf = DocumentBuilderFactory.newInstance()
    dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
  }

  private def testCase(doc: Document, i: Int): Element =
    doc.getElementsByTagName("testcase").item(i).asInstanceOf[Element]

  private def child(e: Element, name: String): Option[Element] =
    Option(e.getElementsByTagName(name).item(0)).map(_.asInstanceOf[Element])

  "JUnitXmlReport" should {

    "write the test suite counts, timing and properties" in {
      val doc = parse(report(
        Seq(
          TestCase("pass", 250, Passed),
          TestCase("skip", 0, Skipped("Missing: [feature=schemaImport]")),
          TestCase("fail", 1, Failed("Expected: 'Error(FODC0002)', but query returned: ''")),
          TestCase("error", 5, Errored(new IllegalStateException("boom")))
        ),
        Seq("file" -> "fn/doc.xml", "name" -> "fn-doc", "version" -> "XQTS_HEAD")
      ))

      val suite = doc.getDocumentElement
      suite.getTagName shouldBe "testsuite"
      suite.getAttribute("name") shouldBe "XQTS_HEAD.fn-doc"
      suite.getAttribute("tests") shouldBe "4"
      suite.getAttribute("skipped") shouldBe "1"
      suite.getAttribute("failures") shouldBe "1"
      suite.getAttribute("errors") shouldBe "1"
      suite.getAttribute("time") shouldBe "0.256"
      suite.getAttribute("timestamp") shouldBe "2026-10-19T12:45:30"
      suite.getAttribute("hostname") should not be empty

      val properties = doc.getElementsByTagName("property")
      (0 until properties.getLength).map { i =>
        val p = properties.item(i).asInstanceOf[Element]
        p.getAttribute("name") -> p.getAttribute("value")
      } shouldBe Seq("file" -> "fn/doc.xml", "name" -> "fn-doc", "version" -> "XQTS_HEAD")
    }

    "write one testcase per result, with the outcome as a child element" in {
      val doc = parse(report(Seq(
        TestCase("pass", 1, Passed),
        TestCase("skip", 0, Skipped("not supported")),
        TestCase("fail", 2, Failed("wrong result")),
        TestCase("error", 3, Errored(new IllegalStateException("boom")))
      )))

      doc.getElementsByTagName("testcase").getLength shouldBe 4

      val pass = testCase(doc, 0)
      pass.getAttribute("classname") shouldBe "XQTS_HEAD.fn-doc"
      pass.getAttribute("name") shouldBe "pass"
      pass.getAttribute("time") shouldBe "0.001"
      pass.getChildNodes.getLength shouldBe 0

      child(testCase(doc, 1), "skipped").map(_.getAttribute("message")) shouldBe Some("not supported")

      val failure = child(testCase(doc, 2), "failure").get
      failure.getAttribute("message") shouldBe "wrong result"
      failure.getAttribute("type") shouldBe FAILURE_TYPE
      failure.getTextContent shouldBe "wrong result"

      val error = child(testCase(doc, 3), "error").get
      error.getAttribute("message") shouldBe "boom"
      error.getAttribute("type") shouldBe "java.lang.IllegalStateException"
      error.getTextContent should startWith("java.lang.IllegalStateException: boom")
    }

    "omit the message of an error without one" in {
      val doc = parse(report(Seq(TestCase("npe", 0, Errored(new NullPointerException())))))
      child(testCase(doc, 0), "error").get.hasAttribute("message") shouldBe false
    }

    "escape markup and keep line breaks in attributes" in {
      val message = "Expected <a b=\"c\"/> & 'd'\n\tat line 2"
      val doc = parse(report(Seq(TestCase("t", 0, Failed(message)))))
      val failure = child(testCase(doc, 0), "failure").get
      failure.getAttribute("message") shouldBe message
      failure.getTextContent shouldBe message
    }

    "replace characters which XML does not allow, keeping supplementary characters" in {
      val message = "nul:\u0000 esc:\u001b lone:\ud800 emoji:😀"
      val doc = parse(report(Seq(TestCase("t", 0, Failed(message)))))
      val failure = child(testCase(doc, 0), "failure").get
      failure.getAttribute("message") shouldBe "nul:� esc:� lone:� emoji:😀"
    }
  }
}
