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

import com.fasterxml.aalto.stax.InputFactoryImpl
import com.fasterxml.aalto.{AsyncInputFeeder, AsyncXMLStreamReader}

import javax.xml.XMLConstants

package object xqftts {

  // Catalog file constants
  val CATALOG_FILE = "XQFTTSCatalog.xml"
  val XQUERY_QUERY_OFFSET = "Queries/XQuery/"
  val RESULT_OFFSET = "ExpectedTestResults/"
  val XQUERY_FILE_EXT = ".xq"

  // Element names (XQFTTS namespace: http://www.w3.org/2005/02/query-test-full-text)
  val ELEM_TEST_SUITE = "test-suite"
  val ELEM_SOURCES = "sources"
  val ELEM_SOURCE = "source"
  val ELEM_TEST_GROUP = "test-group"
  val ELEM_GROUP_INFO = "GroupInfo"
  val ELEM_TITLE = "title"
  val ELEM_DESCRIPTION = "description"
  val ELEM_TEST_CASE = "test-case"
  val ELEM_QUERY = "query"
  val ELEM_INPUT_FILE = "input-file"
  val ELEM_INPUT_URI = "input-URI"
  val ELEM_OUTPUT_FILE = "output-file"
  val ELEM_CONTEXT_ITEM = "contextItem"
  val ELEM_EXPECTED_ERROR = "expected-error"
  val ELEM_SPEC_CITATION = "spec-citation"

  // Attribute names
  val ATTR_NAME = "name"
  val ATTR_ID = "ID"
  val ATTR_FILE_NAME = "FileName"
  val ATTR_FILE_PATH = "FilePath"
  val ATTR_SCENARIO = "scenario"
  val ATTR_IS_XPATH2 = "is-XPath2"
  val ATTR_CREATOR = "Creator"
  val ATTR_COMPARE = "compare"
  val ATTR_ROLE = "role"
  val ATTR_VARIABLE = "variable"
  val ATTR_DATE = "date"
  val ATTR_XQUERY_QUERY_OFFSET_PATH = "XQueryQueryOffsetPath"
  val ATTR_RESULT_OFFSET_PATH = "ResultOffsetPath"
  val ATTR_XQUERY_FILE_EXTENSION = "XQueryFileExtension"
  val ATTR_SOURCE_OFFSET_PATH = "SourceOffsetPath"

  // Comparison types
  val COMPARE_XML = "XML"
  val COMPARE_FRAGMENT = "Fragment"
  val COMPARE_TEXT = "Text"
  val COMPARE_INSPECT = "Inspect"
  val COMPARE_IGNORE = "Ignore"

  // Scenario types
  val SCENARIO_STANDARD = "standard"
  val SCENARIO_PARSE_ERROR = "parse-error"
  val SCENARIO_RUNTIME_ERROR = "runtime-error"

  private[xqftts] val PARSER_FACTORY = new InputFactoryImpl

  implicit class AsyncXMLStreamReaderPimp[F <: AsyncInputFeeder](asyncXmlStreamReader: AsyncXMLStreamReader[F]) {
    def getAttributeValue(localName: String): String = asyncXmlStreamReader.getAttributeValue(XMLConstants.NULL_NS_URI, localName)
    def getAttributeValueOpt(localName: String): Option[String] = Option(asyncXmlStreamReader.getAttributeValue(XMLConstants.NULL_NS_URI, localName))
    def getAttributeValueOptNE(localName: String): Option[String] = getAttributeValueOpt(localName).filter(_.nonEmpty)
  }
}
