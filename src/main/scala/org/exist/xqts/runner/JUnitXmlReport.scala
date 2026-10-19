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

import java.io.{PrintWriter, StringWriter, Writer}
import java.net.InetAddress
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{LocalDateTime, ZoneOffset}

import scala.util.Try

/**
 * Writes the results of a test set in the (legacy) JUnit XML report format,
 * the format of Ant's `XMLJUnitResultFormatter`, which the HTML report
 * stylesheets and eXist-db's XQTS result comparison read.
 *
 * Written by hand rather than through a JUnit or Ant API, so that the runner
 * does not depend on the JUnit version of the eXist-db it runs against.
 */
object JUnitXmlReport {

  sealed trait Outcome
  case object Passed extends Outcome
  case class Skipped(reason: String) extends Outcome
  case class Failed(reason: String) extends Outcome
  case class Errored(t: Throwable) extends Outcome

  /**
   * @param name the name of the test case.
   * @param timeMillis the time taken by the test case, in milliseconds.
   * @param outcome the outcome of the test case.
   */
  case class TestCase(name: String, timeMillis: Long, outcome: Outcome)

  /**
   * Type reported for a failed assertion, which has no exception of its own.
   */
  val FAILURE_TYPE = "AssertionFailure"

  private val TIMESTAMP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME

  private lazy val hostname: String = Try(InetAddress.getLocalHost.getHostName).getOrElse("localhost")

  /**
   * Write a test suite report.
   *
   * @param writer the writer to write the report to, it is not closed.
   * @param suiteName the name of the test suite.
   * @param properties the properties of the test suite, in document order.
   * @param testCases the test cases of the test suite, in document order.
   * @param timestamp when the test suite was run (UTC).
   */
  def write(writer: Writer, suiteName: String, properties: Seq[(String, String)], testCases: Seq[TestCase],
            timestamp: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)): Unit = {
    def count(p: Outcome => Boolean): Int = testCases.count(tc => p(tc.outcome))

    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
    writer.write("<testsuite")
    attribute(writer, "errors", count(_.isInstanceOf[Errored]).toString)
    attribute(writer, "failures", count(_.isInstanceOf[Failed]).toString)
    attribute(writer, "hostname", hostname)
    attribute(writer, "name", suiteName)
    attribute(writer, "skipped", count(_.isInstanceOf[Skipped]).toString)
    attribute(writer, "tests", testCases.size.toString)
    attribute(writer, "time", seconds(testCases.foldLeft(0L)(_ + _.timeMillis)))
    attribute(writer, "timestamp", TIMESTAMP_FORMAT.format(timestamp.truncatedTo(ChronoUnit.SECONDS)))
    writer.write(">\n")

    writer.write("  <properties>\n")
    for ((name, value) <- properties) {
      writer.write("    <property")
      attribute(writer, "name", name)
      attribute(writer, "value", value)
      writer.write(" />\n")
    }
    writer.write("  </properties>\n")

    for (testCase <- testCases) {
      writer.write("  <testcase")
      attribute(writer, "classname", suiteName)
      attribute(writer, "name", testCase.name)
      attribute(writer, "time", seconds(testCase.timeMillis))
      testCase.outcome match {
        case Passed =>
          writer.write(" />\n")

        case Skipped(reason) =>
          writer.write(">\n    <skipped")
          attribute(writer, "message", reason)
          writer.write(" />\n  </testcase>\n")

        case Failed(reason) =>
          writer.write(">\n")
          problem(writer, "failure", Option(reason), FAILURE_TYPE, Option(reason).getOrElse(""))
          writer.write("  </testcase>\n")

        case Errored(t) =>
          writer.write(">\n")
          problem(writer, "error", Option(t.getMessage), t.getClass.getName, stackTrace(t))
          writer.write("  </testcase>\n")
      }
    }

    writer.write("</testsuite>\n")
  }

  private def problem(writer: Writer, elementName: String, message: Option[String], typeName: String, text: String): Unit = {
    writer.write(s"    <$elementName")
    message.filter(_.nonEmpty).foreach(attribute(writer, "message", _))
    attribute(writer, "type", typeName)
    writer.write(">")
    writer.write(escape(text, inAttribute = false))
    writer.write(s"</$elementName>\n")
  }

  private def attribute(writer: Writer, name: String, value: String): Unit = {
    writer.write(s""" $name="""")
    writer.write(escape(value, inAttribute = true))
    writer.write("\"")
  }

  private def seconds(millis: Long): String = (millis / 1000D).toString

  private def stackTrace(t: Throwable): String = {
    val sw = new StringWriter()
    val pw = new PrintWriter(sw)
    t.printStackTrace(pw)
    pw.flush()
    sw.toString
  }

  /**
   * Escape a string for XML 1.0 content. Characters that XML 1.0 does not
   * allow (e.g. most C0 controls, or an unpaired surrogate in an error
   * message) are replaced with U+FFFD, so that the report stays well-formed.
   */
  private[runner] def escape(s: String, inAttribute: Boolean): String = {
    val sb = new java.lang.StringBuilder(s.length + 16)
    var i = 0
    while (i < s.length) {
      val cp = s.codePointAt(i)
      cp match {
        case '&' => sb.append("&amp;")
        case '<' => sb.append("&lt;")
        case '>' => sb.append("&gt;")
        case '"' => sb.append("&quot;")
        case '\'' => sb.append("&apos;")
        case '\n' if inAttribute => sb.append("&#10;")
        case '\r' => sb.append("&#13;")
        case '\t' if inAttribute => sb.append("&#9;")
        case _ if isXmlChar(cp) => sb.appendCodePoint(cp)
        case _ => sb.append('�')
      }
      i += Character.charCount(cp)
    }
    sb.toString
  }

  private def isXmlChar(cp: Int): Boolean =
    cp == 0x9 || cp == 0xA || cp == 0xD ||
      (cp >= 0x20 && cp <= 0xD7FF) ||
      (cp >= 0xE000 && cp <= 0xFFFD) ||
      (cp >= 0x10000 && cp <= 0x10FFFF)
}
