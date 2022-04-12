name := "exist-xqts-runner"

organization := "org.exist-db"

version := "1.0.0"

scalaVersion := "2.13.3"

description := "An XQTS driver for eXist-db"

homepage := Some(url("https://github.com/exist-db/exist-xqts-runner"))

startYear := Some(2018)

organizationName := "The eXist Project"

organizationHomepage := Some(url("https://www.exist-db.org"))

licenses := Seq("LGPL-3.0" -> url("http://opensource.org/licenses/lgpl-3.0"))

headerLicense := Some(HeaderLicense.LGPLv3(startYear.value.map(_.toString).get, organizationName.value))

scmInfo := Some(ScmInfo(
  url(homepage.value.map(_.toString).get),
  "scm:git@github.com:exist-db/exist-xqts-runner.git")
)

developers := List(
  Developer(
    id    = "adamretter",
    name  = "Adam Retter",
    email = "adam@evolvedbinary.com",
    url   = url("https://www.evolvedbinary.com")
  )
)

libraryDependencies ++= {
  val existV = "6.0.1"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % "2.6.19",
    "org.scalaz" %% "scalaz-core" % "7.3.6",
    "com.github.scopt" %% "scopt" % "4.0.1",
    "org.typelevel" %% "cats-effect" % "2.5.4",
    //"com.fasterxml" %	"aalto-xml" % "1.1.0-SNAPSHOT",
    "org.exist-db.thirdparty.com.fasterxml" %	"aalto-xml" % "1.1.0-20180330",
    "org.parboiled" %% "parboiled" % "2.4.0",
    "org.clapper" %% "grizzled-slf4j" % "1.3.4",
    "org.apache.ant" % "ant-junit" % "1.10.12",   // used for formatting junit style report

    "net.sf.saxon" % "Saxon-HE" % "9.9.1-7",
    "org.exist-db" % "exist-core" % existV,
    "org.xmlunit" % "xmlunit-core" % "2.9.0",

    "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.17.2" % "runtime"
  )
}

// we prefer Saxon over Xalan
excludeDependencies ++= Seq(
  ExclusionRule("xalan", "xalan"),

  ExclusionRule("org.hamcrest", "hamcrest-core"),
  ExclusionRule("org.hamcrest", "hamcrest-library")
)

resolvers +=
  Resolver.mavenLocal

resolvers +=
  "eXist-db Releases" at "https://repo.evolvedbinary.com/repository/exist-db/"

resolvers +=
  "eXist-db Snapshots" at "https://repo.evolvedbinary.com/repository/exist-db-snapshots/"

resolvers +=
  "eXist-db Maven Repo" at "https://raw.github.com/eXist-db/mvn-repo/master/"

// Fancy up the Assembly JAR
Compile / packageBin / packageOptions +=  {
  import java.text.SimpleDateFormat
  import java.util.Calendar
  import java.util.jar.Manifest
  import scala.sys.process._

  val gitCommit = "git rev-parse HEAD".!!.trim
  val gitTag = s"git name-rev --tags --name-only $gitCommit".!!.trim

  val additional = Map(
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
  val nexus = "https://repo.evolvedbinary.com/"
  if (isSnapshot.value)
    Some("snapshots" at eb + "repository/exist-db-snapshots/")
  else
    Some("releases" at nexus + "repository/exist-db-snapshots/")
}

Test / publishArtifact := false

pomExtra := (
  <developer>
    <id>adamretter</id>
    <name>Adam Retter</name>
    <email>adam@evolvedbinary.com</email>
    <url>https://www.adamretter.org.uk</url>
    <organization>Evolved Binary</organization>
    <organizationUrl>https://www.evolvedbinary.com</organizationUrl>
  </developer>
  <scm>
    <url>git@github.com:exist-db/exist-xqts-runner.git</url>
    <connection>scm:git:git@github.com:exist-db/exist-xqts-runner.git</connection>
    <developerConnection>scm:git:git@github.com:exist-db/exist-xqts-runner.git</developerConnection>
  </scm>
)
