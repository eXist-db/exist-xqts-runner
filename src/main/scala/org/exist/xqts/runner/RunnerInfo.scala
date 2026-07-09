/*
 * Copyright (C) 2026  The eXist Project
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

import java.io.File
import java.nio.file.{Files, Path}
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.jar.{JarFile, Manifest}

import scala.util.Try

/**
 * Captures metadata about the runner JAR build and the current XQTS run, and
 * writes it to a sibling `runner-info.xml` in the output directory.
 *
 * Two consumers exist for this metadata:
 *   1. `eXist-db/exist`'s `compare-results.xslt`, which surfaces a
 *      `comparison-warning kind="runner-drift"` element when the previous and
 *      current runs' runner JAR build SHAs (or the embedded `exist-core`
 *      version) differ.
 *   2. Future audit tooling that wants a deterministic record of which runner
 *      build produced a given JUnit XML output dir.
 *
 * All fields gracefully degrade to `unknown="true"` when not available (e.g.,
 * when the runner is started from `sbt run` rather than an assembled JAR).
 *
 * See https://github.com/eXist-db/exist/issues/6326 for the originating issue.
 */
object RunnerInfo {

  private val NS = "http://exist-db.org/exist-xqts-runner/runner-info"

  case class JarInfo(
    gitSha: Option[String],
    gitShaAbbrev: Option[String],
    buildTag: Option[String],
    buildVersion: Option[String],
    builtTimestamp: Option[String],
    builtBy: Option[String],
    buildJdk: Option[String],
    sha256: Option[String],
    jarPath: Option[Path]
  )

  case class EmbeddedExistCoreInfo(
    version: Option[String],
    groupId: Option[String],
    artifactId: Option[String],
    pomPropertiesPath: Option[String]
  )

  case class RunInfo(
    started: Instant,
    completed: Instant,
    xqtsVersion: Option[String],
    testCount: Long
  )

  /**
   * Locate the JAR file containing this class. Returns None when the runner is
   * launched from a class directory (e.g. `sbt run`), which is the expected
   * dev workflow.
   */
  private def runnerJarPath: Option[Path] = {
    Try {
      val cs = getClass.getProtectionDomain.getCodeSource
      if (cs == null) None
      else {
        val url = cs.getLocation
        if (url == null) None
        else {
          val f = new File(url.toURI)
          if (f.isFile && f.getName.endsWith(".jar")) Some(f.toPath) else None
        }
      }
    }.toOption.flatten
  }

  /** Read the runner JAR's MANIFEST.MF and compute its sha256. */
  def collectJarInfo(): JarInfo = {
    runnerJarPath match {
      case Some(jarPath) =>
        val manifest: Option[Manifest] = Try {
          val jar = new JarFile(jarPath.toFile)
          try Option(jar.getManifest) finally jar.close()
        }.toOption.flatten

        def attr(key: String): Option[String] =
          manifest
            .flatMap(m => Option(m.getMainAttributes.getValue(key)))
            .map(_.trim)
            .filter(_.nonEmpty)

        val sha256 = Checksum.checksum(jarPath, Checksum.SHA256) match {
          case Right(bytes) => Some(bytes.map(b => f"${b & 0xff}%02x").mkString)
          case Left(_)      => None
        }

        JarInfo(
          gitSha = attr("Git-Commit"),
          gitShaAbbrev = attr("Git-Commit-Abbrev"),
          buildTag = attr("Build-Tag"),
          buildVersion = attr("Build-Version"),
          builtTimestamp = attr("Build-Timestamp"),
          builtBy = attr("Built-By"),
          buildJdk = attr("Build-Jdk"),
          sha256 = sha256,
          jarPath = Some(jarPath)
        )

      case None =>
        JarInfo(None, None, None, None, None, None, None, None, None)
    }
  }

  /**
   * Read embedded `exist-core` metadata from
   * `META-INF/maven/org.exist-db/exist-core/pom.properties`. Works whether
   * `exist-core` is a sibling JAR on the classpath (Maven appassembler layout)
   * or shaded into the runner JAR (sbt-assembly fat-JAR layout) -- in both
   * cases the resource is reachable via the runner's classloader.
   */
  def collectEmbeddedExistCoreInfo(): EmbeddedExistCoreInfo = {
    val pomPath = "META-INF/maven/org.exist-db/exist-core/pom.properties"
    Option(getClass.getClassLoader.getResourceAsStream(pomPath)) match {
      case Some(is) =>
        try {
          val props = new Properties()
          props.load(is)
          EmbeddedExistCoreInfo(
            version = Option(props.getProperty("version")),
            groupId = Option(props.getProperty("groupId")),
            artifactId = Option(props.getProperty("artifactId")),
            pomPropertiesPath = Some(pomPath)
          )
        } finally is.close()
      case None =>
        EmbeddedExistCoreInfo(None, None, None, None)
    }
  }

  /** Build the runner-info.xml document as a string. */
  def render(jar: JarInfo, ec: EmbeddedExistCoreInfo, run: RunInfo): String = {
    val sb = new StringBuilder
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append(s"""<runner-info xmlns="$NS">""").append('\n')

    sb.append("    <runner-jar>\n")
    sb.append(elem("git-sha", jar.gitSha))
    sb.append(elem("git-sha-abbrev", jar.gitShaAbbrev))
    sb.append(elem("build-tag", jar.buildTag))
    sb.append(elem("build-version", jar.buildVersion))
    sb.append(elem("built-timestamp", jar.builtTimestamp))
    sb.append(elem("built-by", jar.builtBy))
    sb.append(elem("build-jdk", jar.buildJdk))
    sb.append(elem("sha256", jar.sha256))
    sb.append(elem("jar-path", jar.jarPath.map(_.toAbsolutePath.toString)))
    sb.append("    </runner-jar>\n")

    sb.append("    <embedded-exist-core>\n")
    sb.append(elem("version", ec.version))
    sb.append(elem("group-id", ec.groupId))
    sb.append(elem("artifact-id", ec.artifactId))
    sb.append(elem("pom-properties-source", ec.pomPropertiesPath))
    sb.append("    </embedded-exist-core>\n")

    sb.append("    <run-info>\n")
    sb.append(elem("started", Some(DateTimeFormatter.ISO_INSTANT.format(run.started))))
    sb.append(elem("completed", Some(DateTimeFormatter.ISO_INSTANT.format(run.completed))))
    sb.append(elem("xqts-version", run.xqtsVersion))
    sb.append(elem("test-count", Some(run.testCount.toString)))
    sb.append("    </run-info>\n")

    sb.append("</runner-info>\n")
    sb.toString
  }

  private def elem(name: String, value: Option[String]): String = value match {
    case Some(v) if v.nonEmpty => s"        <$name>${escape(v)}</$name>\n"
    case _                     => s"        <$name unknown=\"true\"/>\n"
  }

  private def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  /**
   * Write `runner-info.xml` to the output directory root. Returns the path
   * that was written, or a Throwable if the write failed.
   */
  def write(outputDir: Path, run: RunInfo): Either[Throwable, Path] = Try {
    val target = outputDir.resolve("runner-info.xml")
    Files.createDirectories(outputDir)
    val xml = render(collectJarInfo(), collectEmbeddedExistCoreInfo(), run)
    Files.writeString(target, xml)
    target
  }.toEither
}
