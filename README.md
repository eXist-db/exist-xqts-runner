# W3C XQTS driver for eXist-db

This application executes a W3C XQTS against an embedded eXist-db server.


## Compiling from Source
To build from source you will need the following pre-requisites:

1. Git Command Line tools.
2. Java 8+
3. SBT (Simple Build Tool) 1.1.2+

In the following steps, we assume that all of the above tools are available on your system path.

Once the pre-requisites are met, to build from source you can execute the following commands from your console/terminal:

1. `git clone https://github.com/exist-db/exist-xqts-runner.git`
2. `cd exist-xqts-runner`
3. `sbt compile`

The compiled application is now available in the sub-directory `target/scala-2.12`.


### Running from Compiled Source
If you wish to run the application from the compiled source code, you can run the following to display the arguments accepted by `exist-xqts-runner`:

```bash
sbt "run --help"
```

Obviously you should study the output from `--help`, and make sure to set the command line arguments that you need.

**NOTE**: When running `exist-xqts-runner` via. `sbt`, the `run` command and any subsequent arguments to `exist-xqts-runner` must all be enclosed in the same double-quotes.


### Packaging the Application from Compiled Source
* If you require a standard Jar file for some purpose you can run `sbt package`, which will generate `target/scala-2.12/exist-xqts-runner_2.12-1.0.0.jar`.

* If you wish to create a standalone application (also known as an Uber Jar, Assembly, etc.) you can run `sbt assembly`, which will generate `target/scala-2.12/exist-xqts-runner-assembly-1.0.0.jar`. 


### Running the Packaged Application
Given the standalone application, you can execute it by running either:

1. `java -jar exist-xqts-runner-assembly-1.0.0.jar`

2. or, even by just executing the `exist-xqts-runner-assembly-1.0.0.jar` file directly, as we compile an executable header into the Jar file. e.g. (on Linux/Mac): `./exist-xqts-runner-assembly-1.0.0.jar`.


## XQTS Results
The results of executing the XQTS will be formatted as JUnit test output.

* The JUnit report data will be written to the `target/junit/xml` folder.
* An HTML aggregate report summary will be written to `target/junit/html/index.html`.


## Application Architecture
The application is constructed using [Akka](https://akka.io), and as such makes use of Actors to perform many tasks in parallel; This hopefully speeds up the process of running the XQTS which includes many test cases.

When the Application first executes, it will check for a local copy of the XQTS in a sub-directory named `work`, if it cannot find a local copy of the XQTS then it will download it from the W3C.


### Actor Hierarchy

**//TODO(AR):** turn the below into a diagram

* `/XQTSRunnerSystem`
* `/XQTSRunnerSystem/XQTSRunner`
* `/XQTSRunnerSystem/XQTSRunner/TestCaseRunnerRouter`
* `/XQTSRunnerSystem/XQTSRunner/XQTS3CatalogParserActor`
* `/XQTSRunnerSystem/XQTSRunner/XQTS3CatalogParserActor/XQTS3TestSetParserRouter`

### Actor Message Flows

**//TODO(AR):** turn the below into a diagram

1. XQTSRunner (App) -> [RunXQTS] -> XQTSRunnerActor

2. XQTSRunnerActor -> [Parse] -> XQTS3CatalogParserActor

3. XQTS3CatalogParserActor (For each testset) -> [ParseTestSet] -> XQTS3TestSetParserActor (Router=10)

4. XQTS3TestSetParserActor (For each testcase) -> [RunTestCase] -> TestCaseRunnerActor (Router=15)
