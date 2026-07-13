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
2. Java 21
3. SBT (Simple Build Tool) 1.12.11 (the version pinned in [project/build.properties](project/build.properties))
4. a Github personal access token (PAT) with public read access

In the following steps, we assume that git, java and sbt are available on your system path.

### Get the source code

1. `git clone https://github.com/exist-db/exist-xqts-runner.git`
2. `cd exist-xqts-runner`

### Select target eXist-db version

The version of eXist-db that the XQTS driver is compiled for is set in [build.sbt](build.sbt). 
If you wish to compile against a newer or custom version of eXist-db, you must modify this to the version of an eXist-db Maven/Ivy artifact which you have available to your system, e.g.:

```scala
val existV = "7.0.0-SNAPSHOT"
``` 

exist-xqts-runner will check your local maven repository for a version matching the value of `existV`.

If you set the version to a SNAPSHOT version you want to load from Github Maven Package repository you need to authenticate with the Github PAT.

It can be provided via a credentials file in ~/.ivy2/.credentials or by setting the environment variable `GITHUB_TOKEN`.

Once the pre-requisites are met, to build from source you can execute the following commands from your console/terminal:

In your local checkout of eXistdb run:

```bash
mvn clean install -DskipTests=true
```

This will add your local development version to the maven repository (usually `~/.m2`).

### Packaging the Application from Compiled Source

Create a standalone application (also known as an Uber Jar, Assembly, etc.) with

```bash
sbt assembly
```

You should now have it available at `target/scala-2.13/exist-xqts-runner-assembly-2.0.0-SNAPSHOT.jar`.
The version in the file name is the one declared in [version.sbt](version.sbt), so adjust the paths below if it differs.

**NOTE** If you require a standard Jar file for some purpose you can run `sbt package`, which will generate `target/scala-2.13/exist-xqts-runner_2.13-2.0.0-SNAPSHOT.jar`.

### Running the Packaged Application

Given the standalone application, you can execute it by running either (on Linux/Mac):

- `target/scala-2.13/exist-xqts-runner-assembly-2.0.0-SNAPSHOT.jar`
  as the executable header is compiled into the Jar file.
- `java -jar exist-xqts-runner-assembly-2.0.0-SNAPSHOT.jar` also works

**NOTE:** It is recommended to run against the latest version of the testsuite with 

```bash
target/scala-2.13/exist-xqts-runner-assembly-2.0.0-SNAPSHOT.jar -x HEAD
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

## Publishing 

Releases are published to Maven Central by default but can also be published to Github Maven Package repository by setting the environment variable `PUBLISH_TO_GITHUB`.

Snaphots are __always__ published to Github Maven Package repository

You can also publish to your local m2 repository. This is useful for testing new builds within existdb's xqts-runner sub-project.

### Maven Central

1. Run `sbt clean release`
2. Answer the questions
3. Login to https://oss.sonatype.org/ then Close, and Release the Staging Repository

### Github Package Repository (GHP)

1. Run `sbt clean release` if you publish a snapshot or `PUBLISH_TO_GITHUB=true sbt clean release` to force release to GHP
2. Answer the questions

### Publish to local M2

1. run `sbt publishM2`

## XQTS Results
The results of executing the XQTS will be formatted as JUnit test output.

Everything is written below the output directory, which defaults to `target` and can be changed with `-o` (`--output-dir`):

* The JUnit report data will be written to the `junit/data` sub-folder.
* An HTML aggregate report summary will be written to `junit/html/index.html`.
* A `runner-info.xml` file will be written to the root of the output directory. It records which runner produced the results, so that a report can be traced back to the code that generated it. It contains:
  * `runner-jar` — the Git commit and build metadata compiled into the Jar's manifest, plus the SHA-256 checksum and path of the Jar itself.
  * `embedded-exist-core` — the version of the `exist-core` artifact that the run was executed against.
  * `run-info` — the start and completion timestamps, the XQTS version, and the number of test cases that were run.


## XQuery version hints

Many XQTS test queries omit an `xquery version "..."` prolog declaration and rely on the harness to run them under the right language version. eXist applies version-specific parsing and semantics, so the runner prepends a version declaration derived from each test case's `spec` dependency in the XQTS catalog.

Three similar-looking tokens are involved. They are **not** the same thing:

| Token | Kind | Meaning |
|-------|------|---------|
| `XQ10`, `XQ30`, `XQ31`, `XQ40` | XQTS catalog `spec` dependency value | the XQuery language version(s) a test targets |
| `XQ31+` (trailing `+`) | XQTS catalog `spec` dependency value | "this version **or any later**" |
| `XQTS_3_1`, `XQTS_HEAD` | the runner's `--xqts-version` selector | which **test suite** is being run |
| `"3.1"`, `"4.0"` | XQuery version string | what actually gets prepended to the query |

The runner chooses the prepended version as follows:

* If the query already declares a version (`xquery version` / `module namespace`), it is left unchanged.
* If a `spec` dependency names `XQ40` explicitly, prepend `"4.0"`.
* If a `spec` dependency uses the `+` ("or later") form, prepend the **floor of the suite being run**: `"3.1"` for `XQTS_3_1` and `XQTS_HEAD`, otherwise `"4.0"`.
* Otherwise prepend the highest strict (non-`+`) spec: `XQ31` → `"3.1"`, `XQ30` → `"3.0"`, `XQ10` → `"1.0"`.
* If there is no XQuery `spec` dependency, the query is left unchanged.

### Why cap the `+` form at the suite floor?

A dependency such as `XQ31+` marks a test as valid for XQuery 3.1 and every later version. When measuring XQTS 3.1 (`XQTS_3_1`) or the live community-maintained HEAD suite (`XQTS_HEAD`, which tracks the final XQuery 3.1 Recommendation plus corrections), we want those tests run as 3.1, not 4.0. Prepending `"4.0"` would ask the engine to parse them as XQuery 4.0, and an engine that does not accept `xquery version "4.0"` — as is the case for eXist-db's current `develop` HEAD — rejects them at parse time, turning would-be passes into spurious parse failures. Capping the `+` form at `"3.1"` for the 3.1-era suites keeps those tests running under the version they were authored for.


## Application Architecture
The application is constructed using [Apache Pekko](https://pekko.apache.org), and as such makes use of Actors to perform many tasks in parallel; This hopefully speeds up the process of running the XQTS which includes many test cases.
When the Application first executes, it will check for a local copy of the XQTS in a sub-directory named `work` (configurable with `-l`, `--local-dir`), if it cannot find a local copy of the XQTS then it will download it. Version `3.1` is downloaded from the W3C at https://dev.w3.org/2011/QT3-test-suite/releases/QT3_1_0.zip, whereas `HEAD` — the recommended version — is downloaded from the W3C's [qt3tests](https://github.com/w3c/qt3tests) repository on Github. The download locations are configured in [application.conf](src/main/resources/application.conf).


### Actor Hierarchy
Actors naturally have a supervision hierarchy, where if an Actor fails it's supervisor may be notified; The supervisor is responsible for responding to, and recovering from, failures of its supervisees. 

![Actor Supervisor Hierarchy](https://github.com/exist-db/exist-xqts-runner/raw/main/doc/actor-supervisor-hierarchy.png "Actor Supervisor Hierarchy")


### Actor Message Flows
Actors in the system communicate by sending messages between each other. The message flow of a Pekko Actor system can sometimes be tricky to follow by reading the code. The diagram below attempts to inform the developer about the message flows in the system.

![Actor Message Flow](https://github.com/exist-db/exist-xqts-runner/raw/main/doc/actor-message-flow.png "Actor Message Flow")
