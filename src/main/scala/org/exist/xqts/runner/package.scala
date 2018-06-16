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

package org.exist.xqts

/**
  * @author Adam Retter <adam@evolvedbinary.com>
  */
package object runner {

  /**
    * Enumeration of XQTS versions.
    */
  sealed trait XQTSVersion
  object XQTS_1_0 extends XQTSVersion
  object XQTS_3_0 extends XQTSVersion
  object XQTS_3_1 extends XQTSVersion

  object XQTSVersion {

    /**
      * Gets an XQTS version from a string representation.
      *
      * @param s the XQTS version string.
      *
      * @return the XQTS Version.
      */
    @throws[IllegalArgumentException]
    def from(s: String): XQTSVersion = {
      s match {
        case "1.0" | "1" => XQTS_1_0
        case "3.0" | "3" => XQTS_3_0
        case "3.1" => XQTS_3_1
        case _ => throw new IllegalArgumentException(s"No such XQTS version: $s")
      }
    }

    /**
      * Gets an XQTS version from an integer representation.
      *
      * @param i the XQTS version number.
      *
      * @return the XQTS Version.
      */
    @throws[IllegalArgumentException]
    def from(i: Int): XQTSVersion = {
      i match {
        case 1 => XQTS_1_0
        case 3 => XQTS_3_0
        case _ => throw new IllegalArgumentException(s"No such XQTS version: $i")
      }
    }

    /**
      * Gets an XQTS version from a numeric representation.
      *
      * @param f the XQTS version number.
      *
      * @return the XQTS Version.
      */
    @throws[IllegalArgumentException]
    def from(f: Float) = {
      f match {
        case 1 | 1.0f => XQTS_1_0
        case 3 | 3.0f => XQTS_3_0
        case 3.1f => XQTS_3_1
        case _ => throw new IllegalArgumentException(s"No such XQTS version: $f")
      }
    }

    /**
      * Gets the XQTS version label string.
      *
      * @param xqtsVersion the XQTS version.
      *
      * @return the XQTS version label string.
      */
    def label(xqtsVersion: XQTSVersion) : String = {
      xqtsVersion match {
        case XQTS_1_0 =>
          "XQTS_1_0"
        case XQTS_3_0 =>
          "XQTS_3_0"
        case XQTS_3_1 =>
          "XQTS_3_1"
      }
    }
  }
}
