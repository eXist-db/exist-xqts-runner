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
import scala.jdk.CollectionConverters._;

/**
  * Configuration file settings, read from {@code application.conf}.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
class SettingsImpl(config: Config) extends Extension {

  val xqtsVersions : Map[String, XqtsVersionConfig] = {
    (for (versionConfig <- config.getConfigList("xqtsrunner.xqts.versions").asScala.toSeq)
      yield (versionConfig.getString("version") -> XqtsVersionConfig(
        versionConfig.getString("version"),
        versionConfig.getString("url"),
        versionConfig.getString("sha256"),
        Option(versionConfig.getString("has-dir")),
        versionConfig.getString("check-file")
      ))).toMap
  }

  val xqtsLocalDir = config.getString("xqtsrunner.xqts.local-dir")

  val xmlParserBufferSize = config.getInt("xqtsrunner.xml-parser-buffer-size")
  val outputDir = config.getString("xqtsrunner.output-dir")

  val commonResourceCacheMaxSize = config.getLong("xqtsrunner.common-resource-cache.max-size")

  case class XqtsVersionConfig(version: String, url: String, sha256: String, hasDir: Option[String], checkFile: String)
}

object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {
  override def lookup = Settings
  override def createExtension(system: ExtendedActorSystem) =
    new SettingsImpl(system.settings.config)
}
