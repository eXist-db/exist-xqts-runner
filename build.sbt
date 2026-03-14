import ReleaseTransformations._

// When using a custom local Maven repo (-Dmaven.repo.local), disable coursier
// to prevent it from resolving SNAPSHOT artifacts from ~/.m2/repository instead
// of the specified directory. Coursier has a hardcoded ~/.m2/repository fallback
// that cannot be overridden via sbt resolver settings.
ThisBuild / useCoursier := !sys.props.contains("maven.repo.local")

name := "exist-xqts-runner"

organization := "org.exist-db"

scalaVersion := "2.13.17"

semanticdbEnabled := true

semanticdbVersion := scalafixSemanticdb.revision

description := "An XQTS driver for eXist-db"

homepage := Some(url("https://github.com/exist-db/exist-xqts-runner"))

startYear := Some(2018)

organizationName := "The eXist Project"

organizationHomepage := Some(url("https://www.exist-db.org"))

licenses := Seq("LGPL-3.0" -> url("http://opensource.org/licenses/lgpl-3.0"))

headerLicense := Some(HeaderLicense.LGPLv3(startYear.value.map(_.toString).get, organizationName.value))

scmInfo := Some(ScmInfo(
  url(homepage.value.map(_.toString).get),
  "scm:git@github.com:exist-db/exist-xqts-runner.git",
  "scm:git@github.com:exist-db/exist-xqts-runner.git"
))

developers := List(
  Developer(
    id = "adamretter",
    name = "Adam Retter",
    email = "adam@evolvedbinary.com",
    url = url("https://www.evolvedbinary.com")
  ),
  Developer(
    id = "line0",
    name = "Juri Leino",
    email = "juri@existsolutions.com",
    url = url("http://existsolutions.com")
  )
)

versionScheme := Some("semver-spec")

libraryDependencies ++= {
  val existV = "7.0.0-SNAPSHOT"

  Seq(
    "org.apache.pekko" %% "pekko-actor" % "1.3.0",
    "com.github.scopt" %% "scopt" % "4.1.0",
    "org.typelevel" %% "cats-effect" % "3.6.3",
    // "com.fasterxml" %	"aalto-xml" % "1.3.4",
    "org.exist-db.thirdparty.com.fasterxml" % "aalto-xml" % "1.1.0-20180330",
    "org.parboiled" %% "parboiled" % "2.5.1",
    "org.apache.ant" % "ant-junit" % "1.10.15", // used for formatting junit style report

    "net.sf.saxon" % "Saxon-HE" % "9.9.1-8",
    "org.exist-db" % "exist-core" % existV changing(),
    "org.exist-db" % "exist-expath" % existV changing(),
    "org.xmlunit" % "xmlunit-core" % "2.11.0",

    "org.slf4j" % "slf4j-api" % "2.0.17",
    "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.25.2" % "runtime",
  )
}

autoAPIMappings := true

// we prefer Saxon over Xalan
excludeDependencies ++= Seq(
  ExclusionRule("xalan", "xalan"),
  ExclusionRule("org.eclipse.jetty.toolchain", "jetty-jakarta-servlet-api"),

  ExclusionRule("org.hamcrest", "hamcrest-core"),
  ExclusionRule("org.hamcrest", "hamcrest-library")
)

resolvers ++= {
  // Support per-worktree Maven repos: pass -Dmaven.repo.local=/path/to/.m2-repo
  // Uses MavenCache (file-based) instead of "at" (URL-based) for proper local resolution.
  // When a custom repo is set, skip Resolver.mavenLocal to avoid SNAPSHOT conflicts
  // across concurrent builds on the same machine.
  val customMavenLocal = sys.props.get("maven.repo.local").map { path =>
    MavenCache("Custom Local Maven", new java.io.File(path))
  }
  customMavenLocal.toSeq ++ (if (customMavenLocal.isDefined) Seq.empty else Seq(Resolver.mavenLocal)) ++ Seq(
    "eXist-db Releases" at "https://repo.exist-db.org/repository/exist-db/",
    "Github Package Registry" at "https://maven.pkg.github.com/exist-db/exist",
  )
}

javacOptions ++= Seq("-source", "21", "-target", "21")

scalacOptions ++= Seq("-target:jvm-21", "-encoding", "utf-8", "-deprecation", "-feature", "-Ywarn-unused", "-Xlint")

// Fancy up the Assembly JAR
Compile / packageBin / packageOptions +=  {
  import java.text.SimpleDateFormat
  import java.util.Calendar
  import java.util.jar.Manifest
  import scala.sys.process._

  val gitCommit = "git rev-parse HEAD".!!.trim
  val gitTag = s"git name-rev --tags --name-only $gitCommit".!!.trim

  val additional = Map(
    "Multi-Release" -> "true",  /* Required by log4j2 on JDK 11 and newer */
    "Build-Timestamp" -> new SimpleDateFormat("yyyyMMddHHmmss").format(Calendar.getInstance.getTime),
    "Built-By" -> sys.props("user.name"),
    "Build-Tag" -> gitTag,
    "Source-Repository" -> "scm:git:https://github.com/exist-db/exist-xqts-runner.git",
    "Git-Commit-Abbrev" -> gitCommit.substring(0, 7),
    "Git-Commit" -> gitCommit,
    "Build-Jdk" -> sys.props("java.runtime.version"),
    "Description" -> "An XQTS driver for eXist-db",
    "Build-Version" -> "N/A",
    "License" -> "GNU Lesser General Public License, version 3"
  )

  val manifest = new Manifest
  val attributes = manifest.getMainAttributes
  for((k, v) <- additional)
    attributes.putValue(k, v)
  Package.JarManifest(manifest)
}
// assembly merge strategy for duplicate files from dependencies
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "versions", "9" ,"OSGI-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("META-INF", "versions", "9" ,"module-info.class") => MergeStrategy.discard
  case PathList("org", "exist", "xquery", "lib", "xqsuite", "xqsuite.xql") => MergeStrategy.first
  case x if x.equals("module-info.class") || x.endsWith(s"${java.io.File.separatorChar}module-info.class") => MergeStrategy.discard
  case "version.properties" => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

// make the assembly executable with basic shell scripts
import sbtassembly.AssemblyPlugin.defaultUniversalScript

assemblyPrependShellScript := Some(defaultUniversalScript(shebang = false))


// Add assembly to publish step
Compile / assembly / artifact := {
  val art = (Compile / assembly / artifact).value
  art.withClassifier(Some("assembly"))
}

addArtifact(Compile / assembly / artifact, assembly)

// Publish to Maven Repo
publishMavenStyle := true

// Use GitHub Packages if GITHUB_TOKEN is set, otherwise use local connection in credentials file
credentials += {
  sys.env.get("GITHUB_TOKEN") match {
    case Some(token) => Credentials("GitHub Package Registry", "maven.pkg.github.com", "_", token)
    case _ => Credentials(Path.userHome / ".ivy2" / ".credentials")
  }
}

publishTo := {
  if (isSnapshot.value)
    Some("snapshots" at "https://maven.pkg.github.com/exist-db/exist-xqts-runner")
  else
    Some(Opts.resolver.sonatypeStaging)
}

Test / publishArtifact := false

releaseCrossBuild := false

releaseVersionBump := sbtrelease.Version.Bump.Minor

releaseTagName := s"${if (releaseUseGlobalVersion.value) (ThisBuild / version).value else version.value}"

releaseIgnoreUntrackedFiles := true

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommand("publishSigned"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
