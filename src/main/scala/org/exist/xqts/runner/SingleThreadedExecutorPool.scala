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

import java.util.concurrent.{ConcurrentLinkedDeque, ExecutorService, Executors}
import scala.concurrent.ExecutionContext

case class SingleThreadedExecutor(executorService: ExecutorService, executionContext: ExecutionContext)

object SingleThreadedExecutorPool {

  private val brokerExecutorPool = new ConcurrentLinkedDeque[SingleThreadedExecutor]()

  private def createSingleThreadedExecutionContext() : SingleThreadedExecutor = {
    val executorService = Executors.newSingleThreadExecutor()
    val executionContext = ExecutionContext.fromExecutorService(executorService)
    SingleThreadedExecutor(executorService, executionContext)
  }

  def borrowSingleThreadedExecutor() : SingleThreadedExecutor = {
    val maybeBrokerExecutor = Option(brokerExecutorPool.poll())
    maybeBrokerExecutor.getOrElse(createSingleThreadedExecutionContext())
  }

  def returnSingleThreadedExecutor(singleThreadedExecutor: SingleThreadedExecutor) : Unit = {
    brokerExecutorPool.push(singleThreadedExecutor)
  }

  def newResource() : Resource[IO, SingleThreadedExecutor] = {
    Resource.make {
      // build
      IO.delay(SingleThreadedExecutorPool.borrowSingleThreadedExecutor())
//        .flatTap(_ => IOUtil.printlnExecutionContext("SingleThreadedExecutorPool/Build"))  // enable for debugging
    } {
      // release
      singleThreadedExecutionContext =>
        IO.delay(SingleThreadedExecutorPool.returnSingleThreadedExecutor(singleThreadedExecutionContext))
//          .flatTap(_ => IOUtil.printlnExecutionContext("SingleThreadedExecutorPool/Release"))  // enable for debugging
    }
  }
}
