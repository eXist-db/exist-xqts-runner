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

import cats.effect.{IO, Resource}
import cats.syntax.either._
import cats.effect.unsafe.IORuntime
import org.apache.commons.io.input.UnsynchronizedByteArrayInputStream
import org.exist.Namespaces
import org.exist.dom.memtree.{DocumentImpl, SAXAdapter}
import org.exist.xqts.runner.ExistServer.ExistServerException
import org.xml.sax.InputSource

import javax.xml.parsers.SAXParserFactory

object SAXParser {
  private val saxParserFactory = SAXParserFactory.newInstance()
  saxParserFactory.setNamespaceAware(true)

  /**
    * Parses a String representation of an XML Document
    * to an in-memory DOM.
    *
    * @param xml the string of xml to parse.
    *
    * @return either the Document object, or an exception.
    */
  def parseXml(xml: Array[Byte]): Either[ExistServerException, DocumentImpl] = {
    val xmlRes = Resource.make(IO { new UnsynchronizedByteArrayInputStream(xml) })(is => IO { is.close() })

    val parseIO = xmlRes.use(is => IO.blocking {
      val saxAdapter = new SAXAdapter()
      val saxParser = saxParserFactory.newSAXParser()
      val xmlReader = saxParser.getXMLReader()

      xmlReader.setContentHandler(saxAdapter)
      xmlReader.setProperty(Namespaces.SAX_LEXICAL_HANDLER, saxAdapter)
      xmlReader.parse(new InputSource(is))

      saxAdapter.getDocument
    })

    // TODO(AR) should we just return IO from here and allow the caller to do the execution?
    implicit val runtime = IORuntime.global

    parseIO
      .attempt
      .map(_.leftMap(ExistServerException(_)))
      .unsafeRunSync()
  }
}
