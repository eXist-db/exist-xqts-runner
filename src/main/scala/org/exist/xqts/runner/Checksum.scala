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

import java.io.InputStream
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scalaz.\/
import scalaz.syntax.std.either._
import cats.effect.{IO, Resource}

import scala.annotation.tailrec

/**
  * Checksum operations.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
object Checksum {

  sealed trait Algorithm
  case object MD2 extends Algorithm
  case object MD5 extends Algorithm
  case object SHA1 extends Algorithm
  case object SHA256 extends Algorithm
  case object SHA384 extends Algorithm
  case object SHA512 extends Algorithm

  /**
    * Calculates the checksum of a file.
    * 
    * @param file the path to the file to checksum
    * @param algorithm the algorithm to use for calculating the checksum.
    * @param bufferSize the size of the buffer to use when calculating the checksum (default is 16 KB)
    */
  def checksum(file: Path, algorithm: Algorithm, bufferSize: Int = 16 * 1024) : Throwable \/ Array[Byte] = {

    def digestStream(is: InputStream, buf: Array[Byte], digest: MessageDigest): Array[Byte] = {
      @tailrec
      def digestAll() : Unit = {
        val read = is.read(buf)
        if (read == -1) {
          return
        }

        digest.update(buf, 0, read)
        digestAll()
      }

      digestAll()
      digest.digest()
    }

    val checksumIO = Resource.make(IO { Files.newInputStream(file) })(is => IO { is.close() }).use { is =>
      getHash(algorithm).flatMap { digest =>
        // 16 KB buffer
        IO.pure(Array.ofDim[Byte](bufferSize)).flatMap { buf =>
          IO { digestStream(is, buf, digest) }
        }
      }
    }

    checksumIO
      .attempt
      .map(_.disjunction)
      .unsafeRunSync()
  }

  /**
    * Gets Message Digest algorithm.
    *
    * @param algorithm the type of algorith required for a message digest.
    *
    * @return the message digest
    */
  private def getHash(algorithm: Algorithm) : IO[MessageDigest] = {
    IO {
      algorithm match {
        case MD2 =>
          MessageDigest.getInstance("MD2")
        case MD5 =>
          MessageDigest.getInstance("MD5")
        case SHA1 =>
          MessageDigest.getInstance("SHA-1")
        case SHA256 =>
          MessageDigest.getInstance("SHA-256")
        case SHA384 =>
          MessageDigest.getInstance("SHA-384")
        case SHA512 =>
          MessageDigest.getInstance("SHA-512")
        case _ =>
          throw new UnsupportedOperationException(s"Support for $algorithm not yet implemented.")
      }
    }
  }
}
