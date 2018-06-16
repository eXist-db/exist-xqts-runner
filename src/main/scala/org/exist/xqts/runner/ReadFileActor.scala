package org.exist.xqts.runner

import java.io.IOException
import java.nio.file.{Files, Path}

import akka.actor.Actor
import cats.effect.IO
import org.exist.xqts.runner.ReadFileActor.{FileContent, FileReadError, ReadFile}
import scalaz.{-\/, \/, \/-}

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
        case -\/(ioe) =>
          sender ! FileReadError(path, ioe)
        case \/-(content) =>
          sender ! FileContent(path, content)
      }
  }

  private def readFile(path: Path) : IOException \/ Array[Byte] = {
    val fileIO = IO {
      \/.fromTryCatchThrowable[Array[Byte], IOException](Files.readAllBytes(path))
    }
    fileIO.unsafeRunSync()
  }
}

object ReadFileActor {
  case class ReadFile(path: Path)
  case class FileContent(path: Path, data: Array[Byte])
  case class FileReadError(path: Path, error: IOException)
}
