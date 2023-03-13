import ReleaseTransformations._

name := "exist-xqts-runner"

organization := "org.exist-db"

scalaVersion := "2.13.8"

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
    id    = "adamretter",
    name  = "Adam Retter",
    email = "adam@evolvedbinary.com",
    url   = url("https://www.evolvedbinary.com")
  )
)

versionScheme := Some("semver-spec")

libraryDependencies ++= {
  val existV = "7.0.0-SNAPSHOT"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % "2.6.20",
    "com.github.scopt" %% "scopt" % "4.0.1",
    "org.typelevel" %% "cats-effect" % "3.4.5",
    //"com.fasterxml" %	"aalto-xml" % "1.1.0-SNAPSHOT",
    "org.exist-db.thirdparty.com.fasterxml" %	"aalto-xml" % "1.1.0-20180330",
    "org.parboiled" %% "parboiled" % "2.4.1",
    "org.clapper" %% "grizzled-slf4j" % "1.3.4" exclude("org.slf4j", "slf4j-api"),
    "org.apache.ant" % "ant-junit" % "1.10.13",   // used for formatting junit style report

    "net.sf.saxon" % "Saxon-HE" % "9.9.1-8",
    "org.exist-db" % "exist-core" % existV exclude("org.eclipse.jetty.toolchain", "jetty-jakarta-servlet-api"),
    "org.xmlunit" % "xmlunit-core" % "2.9.1",

    "org.slf4j" % "slf4j-api" % "2.0.6" % "runtime",
    "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.19.0" % "runtime"
  )
}

// we prefer Saxon over Xalan
excludeDependencies ++= Seq(
  ExclusionRule("xalan", "xalan"),

  ExclusionRule("org.hamcrest", "hamcrest-core"),
  ExclusionRule("org.hamcrest", "hamcrest-library")
)

resolvers ++= Seq(
  Resolver.mavenLocal,
  "eXist-db Releases" at "https://repo.evolvedbinary.com/repository/exist-db/",
  "eXist-db Snapshots" at "https://repo.evolvedbinary.com/repository/exist-db-snapshots/",
  "eXist-db Maven Repo" at "https://raw.github.com/eXist-db/mvn-repo/master/"
)

javacOptions ++= Seq("-source", "17", "-target", "17")

scalacOptions ++= Seq("-target:jvm-17", "-encoding", "utf-8", "-deprecation", "-feature", "-Ywarn-unused")

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
  case PathList("org", "exist", "xquery", "lib", "xqsuite", "xqsuite.xql")       => MergeStrategy.first
  case x if x.equals("module-info.class") || x.endsWith(s"${java.io.File.separatorChar}module-info.class")    => MergeStrategy.discard
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

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

publishTo := {
  val eb = "https://repo.evolvedbinary.com/"
  if (isSnapshot.value)
    Some("snapshots" at eb + "repository/exist-db-snapshots/")
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
