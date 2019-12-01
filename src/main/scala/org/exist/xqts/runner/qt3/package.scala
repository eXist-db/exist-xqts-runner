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

/**
  * @author Adam Retter <adam@evolvedbinay.com>
  */
package object qt3 {

  /*
   Names of elements and attributes from catalog.xml
   and the various test-case XML files.
  */
  val ELEM_ENVIRONMENT = "environment"
  val ELEM_NAMESPACE = "namespace"
  val ELEM_SCHEMA = "schema"
  val ELEM_DESCRIPTION = "description"
  val ELEM_CREATED = "created"
  val ELEM_MODIFIED = "modified"
  val ELEM_SOURCE = "source"
  val ELEM_RESOURCE = "resource"
  val ELEM_PARAM = "param"
  val ELEM_CONTEXT_ITEM = "context-item"
  val ELEM_DECIMAL_FORMAT = "decimal-format"
  val ELEM_COLLECTION = "collection"
  val ELEM_STATIC_BASE_URI = "static-base-uri"
  val ELEM_COLLATION = "collation"
  val ELEM_TEST_SET = "test-set"
  val ELEM_LINK = "link"
  val ELEM_TEST_CASE = "test-case"
  val ELEM_DEPENDENCY = "dependency"
  val ELEM_TEST = "test"
  val ELEM_RESULT = "result"
  val ELEM_ALL_OF = "all-of"
  val ELEM_ANY_OF = "any-of"
  val ELEM_ASSERT = "assert"
  val ELEM_ASSERT_COUNT = "assert-count"
  val ELEM_ASSERT_DEEP_EQ = "assert-deep-eq"
  val ELEM_ASSERT_EMPTY = "assert-empty"
  val ELEM_ASSERT_EQ = "assert-eq"
  val ELEM_ASSERT_FALSE = "assert-false"
  val ELEM_ASSERT_PERMUTATION = "assert-permutation"
  val ELEM_ASSERT_SERIALIZATION_ERROR = "assert-serialization-error"
  val ELEM_ASSERT_STRING_VALUE = "assert-string-value"
  val ELEM_ASSERT_TRUE = "assert-true"
  val ELEM_ASSERT_TYPE = "assert-type"
  val ELEM_ASSERT_XML = "assert-xml"
  val ELEM_ERROR = "error"
  val ELEM_SERIALIZATION_MATCHES = "serialization-matches"
  val ATTR_NAME = "name"
  val ATTR_COVERS = "covers"
  val ATTR_FILE = "file"
  val ATTR_IGNORE_PREFIXES = "ignore-prefixes"
  val ATTR_FLAGS = "flags"
  val ATTR_PREFIX = "prefix"
  val ATTR_URI = "uri"
  val ATTR_BY = "by"
  val ATTR_ON = "on"
  val ATTR_ROLE = "role"
  val ATTR_VALIDATION = "validation"
  val ATTR_TYPE = "type"
  val ATTR_DOCUMENT = "document"
  val ATTR_VALUE = "value"
  val ATTR_SATISFIED = "satisfied"
  val ATTR_SECTION = "section"
  val ATTR_CHANGE = "change"
  val ATTR_CODE = "code"
  val ATTR_NORMALIZE_SPACE = "normalize-space"
  val ATTR_REF = "ref"
  val ATTR_SELECT = "select"
  val ATTR_AS = "as"
  val ATTR_SOURCE = "source"
  val ATTR_DECLARED = "declared"
  val ATTR_MEDIA_TYPE = "media-type"
  val ATTR_ENCODING = "encoding"
  val ATTR_DECIMAL_SEPARATOR = "decimal-separator"
  val ATTR_EXPONENT_SEPARATOR = "decimal-separator"
  val ATTR_GROUPING_SEPARATOR = "grouping-separator"
  val ATTR_ZERO_DIGIT = "zero-digit"
  val ATTR_DIGIT = "digit"
  val ATTR_MINUS_SIGN = "minus-sign"
  val ATTR_PERCENT = "percent"
  val ATTR_PER_MILLE = "per-mille"
  val ATTR_PATTERN_SEPARATOR = "pattern-separator"
  val ATTR_INFINITY = "infinity"
  val ATTR_NAN = "NaN"
  val ATTR_DEFAULT = "default"

  private[qt3] val PARSER_FACTORY = new InputFactoryImpl

  /**
    * Adds some convenience methods to {@link AsyncXMLStreamReader}.
    */
  implicit class AsyncXMLStreamReaderPimp[F <: AsyncInputFeeder](asynXmlStreamReader : AsyncXMLStreamReader[F]) {
    def getAttributeValue(localName: String) : String = asynXmlStreamReader.getAttributeValue(XMLConstants.NULL_NS_URI, localName)
    def getAttributeValueOpt(localName: String) : Option[String] = Option(asynXmlStreamReader.getAttributeValue(XMLConstants.NULL_NS_URI, localName))
    def getAttributeValueOptNE(localName: String) : Option[String] = getAttributeValueOpt(localName).filter(_.nonEmpty)
  }
}
