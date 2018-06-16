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

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config

/**
  * Configuration file settings, read from {@code application.conf}.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
class SettingsImpl(config: Config) extends Extension {
  val xqts3url = config.getString("xqtsrunner.xqts.version3.url")
  val xqts3sha256 = config.getString("xqtsrunner.xqts.version3.sha256")
  val xqts3HasDir: Option[String] = Option(config.getString("xqtsrunner.xqts.version3.has-dir"))
  val xqts3checkFile = config.getString("xqtsrunner.xqts.version3.check-file")

  val xqtsLocalDir = config.getString("xqtsrunner.xqts.local-dir")

  val xmlParserBufferSize = config.getInt("xqtsrunner.xml-parser-buffer-size")
  val outputDir = config.getString("xqtsrunner.output-dir")

  val commonResourceCacheMaxSize = config.getLong("xqtsrunner.common-resource-cache.max-size")
}

object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {
  override def lookup = Settings
  override def createExtension(system: ExtendedActorSystem) =
    new SettingsImpl(system.settings.config)
}
