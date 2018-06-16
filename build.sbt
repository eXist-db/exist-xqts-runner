name := "exist-xqts-runner"

organization := "org.exist-db"

version := "1.0.0"

scalaVersion := "2.12.5"

licenses := Seq("LGPL-3.0" -> url("http://opensource.org/licenses/lgpl-3.0"))

headerLicense := Some(HeaderLicense.LGPLv3("2018", "The eXist Project"))

homepage := Some(url("https://github.com/exist-db/exist-xqts-runner"))


libraryDependencies ++= {
  //val existV = "5.0.0-RC1"
  val existV = "20180712"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % "2.5.13",
    "org.scalaz" %% "scalaz-core" % "7.2.20",
    "com.github.scopt" %% "scopt" % "3.7.0",
    "org.typelevel" %% "cats-effect" % "1.0.0-RC2",  //"0.10",
    //"com.fasterxml" %	"aalto-xml" % "1.1.0-SNAPSHOT",
    "org.exist-db.thirdparty.com.fasterxml" %	"aalto-xml" % "1.1.0-20180330",
    "org.parboiled" %% "parboiled" % "2.1.4",
    "org.clapper" %% "grizzled-slf4j" % "1.3.2",
    "org.apache.ant" % "ant-junit" % "1.10.3",   // used for formatting junit style report

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
