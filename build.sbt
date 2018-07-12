name := "exist-xqts-runner"

organization := "org.exist-db"

version := "1.0.0"

scalaVersion := "2.12.6"

licenses := Seq("LGPL-3.0" -> url("http://opensource.org/licenses/lgpl-3.0"))

headerLicense := Some(HeaderLicense.LGPLv3("2018", "The eXist Project"))

homepage := Some(url("https://github.com/exist-db/exist-xqts-runner"))


libraryDependencies ++= {
  //val existV = "5.0.0-RC1"
  val existV = "20180712"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % "2.5.13",
    "org.scalaz" %% "scalaz-core" % "7.2.25",
    "com.github.scopt" %% "scopt" % "3.7.0",
    "org.typelevel" %% "cats-effect" % "1.0.0-RC2",  //"0.10",
    //"com.fasterxml" %	"aalto-xml" % "1.1.0-SNAPSHOT",
    "org.exist-db.thirdparty.com.fasterxml" %	"aalto-xml" % "1.1.0-20180330",
    "org.parboiled" %% "parboiled" % "2.1.4",
    "org.clapper" %% "grizzled-slf4j" % "1.3.2",
    "org.apache.ant" % "ant-junit" % "1.10.4",   // used for formatting junit style report

    "org.exist-db" % "exist-testkit" % existV,
    "net.sf.saxon" % "Saxon-HE" % "9.8.0-12",
    "junit" % "junit" % "4.12",           // NOTE: required by exist-testkit!
    "org.exist-db" % "exist-core" % existV,
    "org.xmlunit" %	"xmlunit-core" % "2.6.0",

    "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.11.0" % "runtime"
  )
}

// we prefer Saxon over Xalan
excludeDependencies ++= Seq(
  ExclusionRule("xalan", "xalan")
)

resolvers +=
  Resolver.mavenLocal

resolvers +=
  "eXist-db Releases" at "http://repo.evolvedbinary.com/repository/exist-db/"

resolvers +=
  "eXist-db Snapshots" at "http://repo.evolvedbinary.com/repository/exist-db-snapshots/"

resolvers +=
  "eXist-db Maven Repo" at "https://raw.github.com/eXist-db/mvn-repo/master/"

// Fancy up the Assembly JAR
packageOptions in (Compile, packageBin) +=  {
  import java.text.SimpleDateFormat
  import java.util.Calendar
  import java.util.jar.Manifest
  import scala.sys.process._

  val gitCommit = "git rev-parse HEAD".!!.trim
  val gitTag = "git name-rev --tags --name-only $(git rev-parse HEAD)".!!.trim

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
  case PathList("org", "exist", "xquery", "lib", "xqsuite", "xqsuite.xql")         => MergeStrategy.first
  case "module-info.class"                                => MergeStrategy.discard
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
  val nexus = "http://repo.evolvedbinary.com/"
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
