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

import cats.effect.unsafe.IORuntime

import java.io.{IOException, InputStream, OutputStream}
import java.nio.file.{Files, Path}
import java.util.zip.{ZipEntry, ZipInputStream}
import cats.effect.{IO, Resource}

import scala.annotation.tailrec

/**
  * Small functions for unzipping data.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
object Unzip {

  /**
    * Unzip a file to a directory.
    *
    * @param src the zip file.
    * @param dir the directory to unzip the {@code src} file into.
    *
    * @throws IOException if an error occurs whilst unzipping the {@code src} file.
    */
  @throws[IOException]
  def unzip(src: Path, dir: Path): Unit = {

    def writeZipEntry(zipInputStream: ZipInputStream, zipEntry: ZipEntry): Unit = {
      val destFile = dir.resolve(zipEntry.getName)
      Files.createDirectories(destFile.getParent) // ensure dir path exists

      var os: Option[OutputStream] = None
      try {
        os = Some(Files.newOutputStream(destFile))
        val buffer = new Array[Byte](4096)
        copy(buffer)(zipInputStream, os.get)
      } finally {
          os.map(_.close())
      }
    }

    @tailrec
    def copy(buffer: Array[Byte])(is: InputStream, os: OutputStream): Unit = {
      val read = is.read(buffer)
      if (read <= 0) {
        return
      }

      os.write(buffer, 0, read)

      copy(buffer)(is, os)
    }

    @tailrec
    def processEntries[T](zis: ZipInputStream)(entryFn: ZipEntry => Unit): Unit = {
      val zipEntry = zis.getNextEntry
      if (zipEntry == null) {
        return
      }

      if (!zipEntry.isDirectory) {
        entryFn(zipEntry)
      }

      processEntries(zis)(entryFn)
    }


    val unzipIO : IO[Unit] =
      Resource
        .make(IO { Files.newInputStream(src)} )(fis => IO { fis.close() })
        .use { fis =>
          Resource.make(IO { new ZipInputStream(fis) })(zis => IO { zis.close() })
            .use { zis =>
              IO {
                processEntries(zis)(writeZipEntry(zis, _))
              }
            }
        }

    implicit val runtime = IORuntime.global

    unzipIO
      .unsafeRunSync()
  }
}
