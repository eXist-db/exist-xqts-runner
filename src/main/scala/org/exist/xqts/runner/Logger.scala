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

import org.slf4j.{Logger => SLF4JLogger, LoggerFactory => SLF4JLoggerFactory}

final class Logger(val logger: SLF4JLogger) {

  @inline final def isInfoEnabled(): Boolean = logger.isInfoEnabled()

  @inline final def isWarnEnabled(): Boolean = logger.isWarnEnabled()

  @inline final def isErrorEnabled(): Boolean = logger.isErrorEnabled()

  @inline final def isDebugEnabled(): Boolean = logger.isDebugEnabled()

  /**
    * Log a message at INFO level.
    *
    * @param msg the message object on which `toString()` will be called.
    */
  @inline final def info(msg: => Any): Unit = {
    if (isInfoEnabled()) {
      logger.info(msg.toString)
    }
  }

  /**
    * Log a message with an exception at INFO level.
    *
    * @param msg the message object on which `toString()` will be called.
    * @param t   the exception to include in the log.
    */
  @inline final def info(msg: => Any, t: => Throwable): Unit = {
    if (isInfoEnabled()) {
      logger.info(s"$msg", t)
    }
  }

  /**
    * Log a message at WARN level.
    *
    * @param msg the message object on which `toString()` will be called.
    */
  @inline final def warn(msg: => Any): Unit = {
    if (isWarnEnabled()) {
      logger.warn(msg.toString)
    }
  }

  /**
    * Log a message at ERROR level.
    *
    * @param msg the message object on which `toString()` will be called.
    */
  @inline final def error(msg: => Any): Unit = {
    if (isErrorEnabled()) {
      logger.error(msg.toString)
    }
  }

  /**
    * Log a message at DEBUG level.
    *
    * @param msg the message object on which `toString()` will be called.
    */
  @inline final def debug(msg: => Any): Unit = {
    if (isDebugEnabled()) {
      logger.debug(msg.toString)
    }
  }
}

object Logger {

  /** Get the logger for the specified class.
    *
    * @param clazz  the class
    *
    * @return the `Logger`.
    */
  def apply(clazz: Class[_]): Logger = new Logger(SLF4JLoggerFactory.getLogger(clazz))
}
