# W3C XQTS driver for eXist-db

[![CI](https://github.com/eXist-db/exist-xqts-runner/workflows/CI/badge.svg)](https://github.com/eXist-db/exist-xqts-runner/actions?query=workflow%3ACI)
[![Scala 2.13](https://img.shields.io/badge/scala-2.13-red.svg)](http://scala-lang.org)
[![License](https://img.shields.io/badge/license-LGPL%203.0-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0.html)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.exist-db/exist-xqts-runner_2.13/badge.svg)](https://search.maven.org/search?q=g:org.exist-db)

This application executes a W3C XQTS against an embedded eXist-db server.


## Compiling from Source

### Prerequisites

To build from source you will need the following pre-requisites:

1. Git Command Line tools.
2. Java 8+
3. SBT (Simple Build Tool) 1.5.5+

In the following steps, we assume that all of the above tools are available on your system path.

### Get the source code

1. `git clone https://github.com/exist-db/exist-xqts-runner.git`
2. `cd exist-xqts-runner`

### Select target eXist-db version

The version of eXist-db that the XQTS driver is compiled for is set in [build.sbt](build.sbt). 
If you wish to compile against a newer or custom version of eXist-db, you must modify this to the version of an eXist-db Maven/Ivy artifact which you have available to your system, e.g.:

```scala
val existV = "5.3.0"
``` 

exist-xqts-runner will check your local maven repository for a version matching the value of `existV`.

In your local checkout of eXistdb run:

```bash
mvn clean install -DskiptTests=true
```

This will add your local development version to the maven repository (usually `~/.m2`).

### Packaging the Application from Compiled Source

Create a standalone application (also known as an Uber Jar, Assembly, etc.) with

```bash
sbt assembly
```

You should now have it available at `target/scala-2.13/exist-xqts-runner-assembly-1.0.0.jar`. 

**NOTE** If you require a standard Jar file for some purpose you can run `sbt package`, which will generate `target/scala-2.13/exist-xqts-runner_2.13-1.0.0.jar`.

### Running the Packaged Application

Given the standalone application, you can execute it by running either (on Linux/Mac):

- `target/scala-2.13/exist-xqts-runner-assembly-1.0.0.jar`
  as the executable header is compiled into the Jar file.
- `java -jar exist-xqts-runner-assembly-1.0.0.jar` also works

**NOTE:** It is recommended to run against the latest version of the testsuite with 

```bash
target/scala-2.13/exist-xqts-runner-assembly-1.0.0.jar -x HEAD
```

### Compiling

Once the pre-requisites are met, to build from source execute the following commands from your console/terminal:

```bash
sbt compile
```

The compiled application is now available in the sub-directory `target/scala-2.13`.

### Running from Compiled Source

If you wish to run the application from the compiled source code, you can run the following to display the arguments accepted by `exist-xqts-runner`:

```bash
sbt "run --help"
```

Obviously you should study the output from `--help`, and make sure to set the command line arguments that you need.

**NOTE**: When running `exist-xqts-runner` via. `sbt`, the `run` command and any subsequent arguments to `exist-xqts-runner` must all be enclosed in the same double-quotes. If you want to execute the complete test suite, running the [packaged application](#Packaging-the-Application-from-Compiled-Source) is advised.

## Publishing to Maven Central / Evolved Binary Snapshots
1. Run `sbt clean release`
2. Answer the questions
3. Login to https://oss.sonatype.org/ then Close, and Release the Staging Repository

## XQTS Results
The results of executing the XQTS will be formatted as JUnit test output.

* The JUnit report data will be written to the `target/junit/data` folder.
* An HTML aggregate report summary will be written to `target/junit/html/index.html`.


## Application Architecture
The application is constructed using [Akka](https://akka.io), and as such makes use of Actors to perform many tasks in parallel; This hopefully speeds up the process of running the XQTS which includes many test cases.
When the Application first executes, it will check for a local copy of the XQTS in a sub-directory named `work`, if it cannot find a local copy of the XQTS then it will download it from the W3C.


### Actor Hierarchy
Actors naturally have a supervision hierarchy, where if an Actor fails it's supervisor may be notified; The supervisor is responsible for responding to, and recovering from, failures of its supervisees. 

![Actor Supervisor Hierarchy](https://github.com/exist-db/exist-xqts-runner/raw/main/doc/actor-supervisor-hierarchy.png "Actor Supervisor Hierarchy")


### Actor Message Flows
Actors in the system communicate by sending messages between each other. The message flow of an Akka Actor system can sometimes be tricky to follow by reading the code. The diagram below attempts to inform the developer about the message flows in the system.

![Actor Message Flow](https://github.com/exist-db/exist-xqts-runner/raw/main/doc/actor-message-flow.png "Actor Message Flow")
