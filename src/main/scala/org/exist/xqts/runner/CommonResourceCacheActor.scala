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

import akka.actor.{Actor, ActorRef}
import grizzled.slf4j.Logger
import org.exist.xqts.runner.CommonResourceCacheActor._
import org.exist.xqts.runner.ReadFileActor.{FileContent, FileReadError, ReadFile}

/**
  * Actor which provides an LRU like cache for holding the content of files.
  *
  * @param readFileActor An actor which can read files from the filesystem
  * @param maxCacheBytes The total maximum size for the cache in bytes.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
class CommonResourceCacheActor(readFileActor: ActorRef, maxCacheBytes: Long) extends Actor {
  private var cached : Cache = emptyCache
  private var pending : Pending = nothingPending

  override def receive: Receive = {
    case GetResource(key) =>
      cached.get(key) match {
        case Some((_, value)) =>
          sender ! CachedResource(key, value)
          cached = incrementUseCount(cached)(key)

        case None =>
          if (!pending.contains(key)) {
            readFileActor ! ReadFile(key)
          }

          // we always need to send a response, so add to pending
          pending = addPending(pending)(key, sender)
      }

    case FileReadError(path, error) =>
      // update the pending actors
      pending.get(path).map(_.map(pending => pending ! ResourceGetError(path, error)))
      pending = removePending(pending)(path)

    case FileContent(path, data) =>
      val currentCacheBytes = getCachedBytes(cached)
      if (currentCacheBytes + data.length >= maxCacheBytes) {
        // cache does not have enough capacity, need to do some eviction
        cached = evictAtLeast(cached)(data.length)
      }
      cached = cache(cached)(path, (System.currentTimeMillis(), data))
      logger.info(s"Cached ${path}")
      // update the pending actors
      pending.get(path).map(_.map(pending => pending ! CachedResource(path, data)))
      pending = removePending(pending)(path)
  }
}

object CommonResourceCacheActor {

  private val logger = Logger(classOf[CommonResourceCacheActor])

  type Key = Path
  type Value = Array[Byte]
  private type Timestamp = Long
  private type TimestampedValue = (Timestamp, Value)
  private type Cache = Map[Key, TimestampedValue]
  private val emptyCache = Map.empty[Key, TimestampedValue]
  private type Pending = Map[Path, List[ActorRef]]
  private val nothingPending = Map.empty[Path, List[ActorRef]]

  case class GetResource(key: Key)
  case class CachedResource(key: Key, value: Value)
  case class ResourceGetError(key: Key, error: IOException)


  private def addPending(pending: Pending)(key: Key, actor: ActorRef) : Pending = {
    pending + (key ->
      pending.get(key)
        .map(actor +: _)
        .getOrElse(List(actor)))
  }

  private def removePending(pending: Pending)(key: Key) : Pending = pending - key

  private def incrementUseCount(cache: Cache)(key: Key) : Cache = {
    cache.get(key)
      .map { case (count, value) => (count + 1, value)}.map(updated => cache + (key -> updated))
      .getOrElse(cache)
  }

  private def getCachedBytes(cache: Cache) = cache.values.foldLeft(0)((accum, x) => accum + x._2.length)

  private def cache(cache: Cache)(key: Key, value: TimestampedValue): Cache = cache + (key -> value)

  private def evictAtLeast(cache: Cache)(bytes: Long) : Cache = {
    //TODO(AR) write a tail recursive version
    //@tailrec
    def keysForNBytes(cache: Cache, bytesToClaim: Long): Seq[Key] = {
      if (bytesToClaim <= 0 || cache.isEmpty) {
        return Seq.empty
      }

      val cacheEntry = cache.head
      keysForNBytes(cache.tail, bytesToClaim - cacheEntry._2._2.length) :+ cacheEntry._1
    }
    // sort the cache entries by the least-recently-used
    val lru = cache.toList.sortWith(_._2._1 < _._2._1)
    // find the first n bytes worth of keys
    val keysToEvict = keysForNBytes(cache, bytes)
    // remove the stale entries
    val smallerCache = cache -- keysToEvict
    logger.info(s"Evicted ${bytes} bytes from the cache")
    smallerCache
  }
}
