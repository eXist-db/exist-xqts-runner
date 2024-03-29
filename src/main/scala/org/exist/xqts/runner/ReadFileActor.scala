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

import java.io.IOException
import java.nio.file.{Files, Path}
import akka.actor.Actor
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.either._
import org.exist.xqts.runner.ReadFileActor.{FileContent, FileReadError, ReadFile}

/**
  * Actor which reads the entire
  * content of a file from the filesystem.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
class ReadFileActor extends Actor {
  override def receive: Receive = {
    case ReadFile(path) =>
      readFile(path) match {
        case Left(ioe) =>
          sender() ! FileReadError(path, ioe)
        case Right(content) =>
          sender() ! FileContent(path, content)
      }
  }

  private def readFile(path: Path) : Either[IOException, Array[Byte]] = {
    val fileIO = IO.blocking {
      Either.catchOnly[IOException](Files.readAllBytes(path))
    }

    implicit val runtime = IORuntime.global
    fileIO.unsafeRunSync()
  }
}

object ReadFileActor {
  case class ReadFile(path: Path)
  case class FileContent(path: Path, data: Array[Byte])
  case class FileReadError(path: Path, error: IOException)
}
