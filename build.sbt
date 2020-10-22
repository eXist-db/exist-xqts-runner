name := "exist-xqts-runner"

organization := "org.exist-db"

version := "1.0.0"

scalaVersion := "2.13.3"

licenses := Seq("LGPL-3.0" -> url("http://opensource.org/licenses/lgpl-3.0"))

headerLicense := Some(HeaderLicense.LGPLv3("2018", "The eXist Project"))

homepage := Some(url("https://github.com/exist-db/exist-xqts-runner"))


libraryDependencies ++= {
  val existV = "5.3.0-SNAPSHOT"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % "2.6.10",
    "org.scalaz" %% "scalaz-core" % "7.3.2",
    "com.github.scopt" %% "scopt" % "3.7.1",
    "org.typelevel" %% "cats-effect" % "2.2.0",
    //"com.fasterxml" %	"aalto-xml" % "1.1.0-SNAPSHOT",
    "org.exist-db.thirdparty.com.fasterxml" %	"aalto-xml" % "1.1.0-20180330",
    "org.parboiled" %% "parboiled" % "2.2.1",
    "org.clapper" %% "grizzled-slf4j" % "1.3.4",
    "org.apache.ant" % "ant-junit" % "1.10.9",   // used for formatting junit style report

    "net.sf.saxon" % "Saxon-HE" % "9.9.1-7",
    "org.exist-db" % "exist-core" % existV,
    "org.xmlunit" % "xmlunit-core" % "2.7.0",

    "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.3" % "runtime"
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
packageOptions in (Compile, packageBin) +=  {
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
assemblyMergeStrategy in assembly := {
  case PathList("org", "exist", "xquery", "lib", "xqsuite", "xqsuite.xql")       => MergeStrategy.first
  case x if x.equals("module-info.class") || x.endsWith("/module-info.class")    => MergeStrategy.discard
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

// make the assembly executable with basic shell scripts
import sbtassembly.AssemblyPlugin.defaultUniversalScript

assemblyOption in assembly := (assemblyOption in assembly).value.copy(prependShellScript = Some(defaultUniversalScript(shebang = false)))


// Add assembly to publish step
artifact in (Compile, assembly) := {
  val art = (artifact in (Compile, assembly)).value
  art.withClassifier(Some("assembly"))
}

addArtifact(artifact in (Compile, assembly), assembly)

// Publish to Maven Repo

publishMavenStyle := true

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

publishTo := {
  val nexus = "https://repo.evolvedbinary.com/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "repository/exist-db-snapshots/")
  else
    Some("releases"  at nexus + "repository/exist-db/")
}

publishArtifact in Test := false

pomExtra := (
  <developers>
    <developer>
      <id>adamretter</id>
      <name>Adam Retter</name>
      <url>http://www.adamretter.org.uk</url>
    </developer>
  </developers>
    <scm>
      <url>git@github.com:exist-db/exist-xqts-runner.git</url>
      <connection>scm:git:git@github.com:exist-db/exist-xqts-runner.git</connection>
    </scm>)
