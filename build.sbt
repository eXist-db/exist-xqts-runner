import ReleaseTransformations._
import xerial.sbt.Sonatype.sonatypeCentralHost

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
  val existV = "7.0.0-beta3"

  Seq(
    "org.apache.pekko" %% "pekko-actor" % "1.3.0",
    "com.github.scopt" %% "scopt" % "4.1.0",
    "org.typelevel" %% "cats-effect" % "3.6.3",
    // "com.fasterxml" %	"aalto-xml" % "1.3.4",
    "org.exist-db.thirdparty.com.fasterxml" % "aalto-xml" % "1.1.0-20180330",
    "org.parboiled" %% "parboiled" % "2.5.1",
    "org.apache.ant" % "ant-junit" % "1.10.15", // used for formatting junit style report

    "net.sf.saxon" % "Saxon-HE" % "12.5",
    "org.exist-db" % "exist-core" % existV,
    "org.xmlunit" % "xmlunit-core" % "2.11.0",

    "org.slf4j" % "slf4j-api" % "2.0.17",
    "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.25.2" % "runtime",

    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    "org.apache.pekko" %% "pekko-testkit" % "1.3.0" % Test,
  )
}

autoAPIMappings := true

// Exclude transitive dependencies the runner doesn't need.
// Jetty exclusions allow building against both Jetty 11 (develop) and Jetty 12 (next) —
// Ivy can't resolve Jetty 12 Maven POM constructs, and the runner doesn't use Jetty anyway.
excludeDependencies ++= Seq(
  ExclusionRule("xalan", "xalan"),

  ExclusionRule("org.eclipse.jetty"),
  ExclusionRule("org.eclipse.jetty.toolchain"),
  ExclusionRule("org.eclipse.jetty.websocket"),
  ExclusionRule("org.eclipse.jetty.ee10"),
  ExclusionRule("org.eclipse.jetty.ee10.websocket"),

  ExclusionRule("org.hamcrest", "hamcrest-core"),
  ExclusionRule("org.hamcrest", "hamcrest-library")
)

resolvers ++= Seq(
  Resolver.mavenLocal,
  "eXist-db Releases" at "https://repo.exist-db.org/repository/exist-db/",
  "Github Package Registry" at "https://maven.pkg.github.com/exist-db/exist",
)

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
  // jline 4.1.0 and jansi 4.0.14 (both transitive via exist-core 7.0.0-SNAPSHOT)
  // ship overlapping org/jline/** classes with differing bytecode; jline is only
  // used for terminal output here, so taking the first copy is safe.
  case PathList("org", "jline", _*) => MergeStrategy.first
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

ThisBuild / sonatypeCredentialHost := sonatypeCentralHost

// Use GitHub Packages if GITHUB_TOKEN is set, otherwise use a local credentials
// file if one exists. The file must stay optional: the "Publish to Maven
// Central" CI step sets neither GITHUB_TOKEN nor a credentials file — it
// authenticates solely via the Sonatype entry below — and a mandatory file
// makes sbt fail before that entry is ever consulted.
credentials ++= {
  sys.env.get("GITHUB_TOKEN") match {
    case Some(token) => Seq(Credentials("GitHub Package Registry", "maven.pkg.github.com", "_", token))
    case _ =>
      val credentialsFile = Path.userHome / ".ivy2" / ".credentials"
      if (credentialsFile.exists) Seq(Credentials(credentialsFile)) else Seq.empty
  }
}

// Sonatype Central Portal credentials, used when publishing releases there (see PUBLISH_TO_GITHUB below)
credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  sonatypeCredentialHost.value,
  sys.env.getOrElse("CENTRAL_TOKEN_USERNAME", ""),
  sys.env.getOrElse("CENTRAL_TOKEN_PASSWORD", "")
)

// Toggle publishing target via environment variable
val useGitHub = sys.env.get("PUBLISH_TO_GITHUB").isDefined

publishTo := {
  if (isSnapshot.value) {
    Some("snapshots" at "https://maven.pkg.github.com/exist-db/exist-xqts-runner")
  } else if (useGitHub) {
    Some("releases" at "https://maven.pkg.github.com/exist-db/exist-xqts-runner")
  } else {
    sonatypePublishToBundle.value
  }
}

Test / publishArtifact := false

releaseCrossBuild := false

releaseVersionBump := sbtrelease.Version.Bump.Minor

releaseTagName := s"${if (releaseUseGlobalVersion.value) (ThisBuild / version).value else version.value}"

releaseIgnoreUntrackedFiles := true

// Publishing happens in CI on tag push (see .github/workflows/release.yml), not as part of this process.
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  setNextVersion,
  commitNextVersion,
  pushChanges
)

useCoursier := false
