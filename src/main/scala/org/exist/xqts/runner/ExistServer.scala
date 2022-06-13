/*
 * Copyright (C) 2018  The eXist Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.exist.xqts.runner

import java.io._
import java.nio.charset.Charset
import java.util.Properties
import org.exist.source.{Source, StringSource}
import org.exist.storage.{DBBroker, XQueryPool}
import org.exist.test.ExistEmbeddedServer
import org.exist.util.serializer.XQuerySerializer
import org.exist.xquery.{CompiledXQuery, Function, XPathException, XQuery, XQueryContext}

import scala.util.{Failure, Success, Try}
import scalaz.\/
import scalaz.syntax.either._
import ExistServer.{CompilationTime, _}
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import com.evolvedbinary.j8fu.function.{QuadFunctionE, TriFunctionE}
import grizzled.slf4j.Logger

import javax.xml.namespace.QName
import javax.xml.transform.OutputKeys
import org.exist.dom.memtree.DocumentImpl
import org.exist.storage.txn.Txn
import org.exist.xmldb.XmldbURI
import org.exist.xqts.runner.XQTSParserActor.{DecimalFormat, Module, Namespace}
import org.exist.xquery.value._

object ExistServer {
  type ExecutionTime = Long
  type CompilationTime = Long

  case class ExistServerException(t: Throwable, compilationTime: CompilationTime = 0, executionTime: ExecutionTime = 0) extends Exception(t)

  object Result {
    def apply(queryError: QueryError, compilationTime: CompilationTime, executionTime: ExecutionTime) = new Result(queryError.left, compilationTime, executionTime)
    def apply(queryResult: QueryResult, compilationTime: CompilationTime, executionTime: ExecutionTime) = new Result(queryResult.right, compilationTime, executionTime)
  }
  case class Result(result: QueryError \/ QueryResult, compilationTime: CompilationTime, executionTime: ExecutionTime)

  type QueryResult = Sequence
  object QueryError {
    def apply(xpathException: XPathException) = new QueryError(xpathException.getErrorCode.getErrorQName.getLocalPart, xpathException.getMessage)
  }
  case class QueryError(errorCode: String, message: String)

  /**
    * Starts up an eXist-db server.
    *
    * @return A reference to the server, or an exception.
    */
  def start(): Throwable \/ ExistServer = {
    val server = new ExistServer
    server.startServer() match {
      case Success(_) =>
        server.right
      case Failure(e) =>
        e.left
    }
  }

  def getVersion() : String = org.exist.Version.getVersion()

  def getCommitAbbrev() : String = {
    val commit = Option(org.exist.Version.getGitCommit()).filter(_.nonEmpty)
    commit.map(_.substring(0, 7)).getOrElse("UNKNOWN")
  }
}

/**
  * Encapsulation of operations for an exist-db server.
  */
class ExistServer {
  private val existServer = new ExistEmbeddedServer(true, true)
  private val logger = Logger(classOf[ExistServer])

  /**
    * Starts the eXist-db server.
    *
    * @return Success or Failure.
    */
  private def startServer() : Try[Unit] = Try(existServer.startDb())

  /**
    * Get a connection to the eXist-db server.
    */
  def getConnection() : ExistConnection = {
    val brokerRes = Resource.make {
      // build
      IO.delay(existServer.getBrokerPool.getBroker)
    } {
      // release
      broker =>
        IO.delay(broker.close()).handleErrorWith { t =>
          logger.warn(s"Error releasing DBBroker: ${t.getMessage}", t)
          IO.unit
        }
    }

    ExistConnection(brokerRes)
  }

  /**
    * Shutdown the eXist-db server.
    */
  def stopServer(): Unit = {
    existServer.stopDb()
  }
}

private object ExistConnection {
  def apply(brokerRes: Resource[IO, DBBroker]) = new ExistConnection(brokerRes)
}

/**
  * Represents a connection
  * to an eXist-db server, i.e. a {@link org.exist.storage.DBBroker}
  *
  * @param broker the eXist-db broker to wrap.
  */
class ExistConnection(brokerRes: Resource[IO, DBBroker]) {

  /**
    * Execute an XQuery with eXist-db.
    *
    * @param query The XQuery to execute.
    * @param cacheCompiled true if you want to cache the compiled query form.
    * @param staticBaseUri An optional static-baseURI for the XQuery.
    * @param contextSequence An optional context sequence for the XQuery to operate on.
    * @param availableDocuments Any dynamically available Documents that should be available to the XQuery.
    * @param availableCollections Any dynamically available Collections that should be available to the XQuery.
    * @param availableTextResources Any dynamically available Text Resources that should be available to the XQuery.
    * @param externalVariables Any external variables that should be bound for the XQuery.
    * @param decimalFormats Any changes to the `unnamed` decimal format.
    *
    * @return the result or executing the query, or an exception.
    */
  def executeQuery(query: String, cacheCompiled: Boolean, staticBaseUri: Option[String], contextSequence: Option[Sequence], availableDocuments: Seq[(String, DocumentImpl)] = Seq.empty, availableCollections: Seq[(String, List[DocumentImpl])] = Seq.empty, availableTextResources: Seq[(String, Charset, String)] = Seq.empty, namespaces: Seq[Namespace] = Seq.empty, externalVariables: Seq[(String, Sequence)] = Seq.empty, decimalFormats: Seq[DecimalFormat] = Seq.empty, modules: Seq[Module] = Seq.empty, xpath1Compatibility : Boolean = false) : ExistServerException \/ Result = {
    /**
      * Gets the XQuery Pool.
      *
      * @param broker the database broker.
      *
      * @return the XQuery Pool.
      */
    def getXQueryPool(broker: DBBroker) : IO[XQueryPool] = {
      IO.pure(broker.getBrokerPool.getXQueryPool)
    }

    /**
      * Gets the XQuery Service.
      *
      * @param broker the database broker.
      *
      * @return the XQuery Service.
      */
    def getXQueryService(broker: DBBroker) : IO[XQuery] = {
      IO.pure(broker.getBrokerPool.getXQueryService)
    }

    /**
      * Data class for a Compiled XQuery.
      *
      * @param compiledXquery the compiled query itself.
      * @param xqueryContext the context prepared for use when executing the compiled query.
      * @param compilationTime the time it took to compile the XQuery.
      */
    case class CompiledQuery(compiledXquery: CompiledXQuery, xqueryContext: XQueryContext, compilationTime: CompilationTime)

    /**
      * Gets a compiled XQuery from an the XQuery Pool.
      *
      * @param broker the database broker.
      * @param xqueryPool the XQuery Pool.
      * @param source the source of the XQuery.
      * @param fnConfigureContext a function that can configure the context of the query.
      *
      * @return The compiled XQuery from the pool, or None if the pool did not have a compiled query available.
      */
    def compiledXQueryFromPool(broker: DBBroker, xqueryPool: XQueryPool, source: Source, fnConfigureContext: XQueryContext => XQueryContext) : Resource[IO, Option[CompiledQuery]] = {
      Resource.make {
        // build
        for (
          startCompilationTime <- IO.pure(System.currentTimeMillis());
          maybeCompiledXQuery <- IO.blocking(Option(xqueryPool.borrowCompiledXQuery(broker, source)));
          maybeCompiledXQueryContext <- IO.blocking(maybeCompiledXQuery.map(compiledXQuery => fnConfigureContext(compiledXQuery.getContext)));
          endCompilationTime <- IO.pure(System.currentTimeMillis())
        ) yield maybeCompiledXQuery.zip(maybeCompiledXQueryContext).map{ case (compiledXQuery, compiledXQueryContext) => CompiledQuery(compiledXQuery, compiledXQueryContext, endCompilationTime - startCompilationTime)}
      } {
        // release
        _ match {
          case Some(compiledQuery) =>
            for (
              _ <- IO.blocking(compiledQuery.xqueryContext.runCleanupTasks());
              _ <- IO.blocking(xqueryPool.returnCompiledXQuery(source, compiledQuery.compiledXquery))
            ) yield ()
          case None =>
            IO.unit
        }
      }
    }

    /**
      * Compiles an XQuery.
      *
      * @param broker the database broker.
      * @param source the source of the XQuery.
      * @param fnConfigureContext a function that can configure the context of the query.
      * @param maybeXQueryPool if present, the query will be returned to the pool after it is used.
      *
      * @return The compiled XQuery.
      */
    def compileXQuery(broker: DBBroker, source: Source, fnConfigureContext: XQueryContext => XQueryContext, maybeXQueryPool: Option[XQueryPool]) : Resource[IO, CompiledQuery] = {
      val xqueryContextRes = Resource.make {
        // build
        IO.blocking {
          fnConfigureContext(new XQueryContext(broker.getBrokerPool()))
        }
      } {
        // release
        xqueryContext =>
          xqueryContext.runCleanupTasks()
          IO.unit
      }

      xqueryContextRes.flatMap { xqueryContext =>
        Resource.make {
          // build
          for (
            startCompilationTime <- IO.pure(System.currentTimeMillis());
            xqueryService <- getXQueryService(broker);
            compiledXQuery <- IO.blocking(xqueryService.compile(xqueryContext, source));
            endCompilationTime <- IO.pure(System.currentTimeMillis())
          )
          yield CompiledQuery(compiledXQuery, xqueryContext, endCompilationTime - startCompilationTime)
        } {
          // release
          compiledQuery =>
            maybeXQueryPool match {
              case Some(xqueryPool) => {
                xqueryPool.returnCompiledXQuery(source, compiledQuery.compiledXquery)
                IO.unit
              }
              case None => IO.unit
            }
        }
      }
    }

    /**
      * Gets a compiled XQuery from an XQuery source.
      *
      * Handles caching of compiled XQuery:
      *
      *   1. If the cache should be used, then it will try
      *   and retrieve a compiled version. If there is no
      *   compiled version, the query source will be
      *   compiled and added to the cache, before being
      *   returned.
      *
      *   2. If the cache should not be used, then the
      *   query source will be compiled before being
      *   returned.
      *
      * @param broker the database broker.
      * @param source the source of the XQuery.
      * @param fnConfigureContext a function that can configure the context of the query.
      * @param maybeXQueryPool if present, the query will be returned to the pool after it is used.
      *
      * @return The compiled XQuery.
      */
    def compiledXQuery(broker: DBBroker, source: Source, fnConfigureContext: XQueryContext => XQueryContext, maybeXqueryPool: Option[XQueryPool]): Resource[IO, CompiledQuery] = {
      maybeXqueryPool match {
        case Some(xqueryPool) =>
          compiledXQueryFromPool(broker, xqueryPool, source, fnConfigureContext).flatMap { maybeCompiledQueryFromPool =>
            maybeCompiledQueryFromPool match {
              case Some(compiledQueryFromPool) => Resource.pure(compiledQueryFromPool)            // use the existing query from the pool
              case None => compileXQuery(broker, source, fnConfigureContext, maybeXqueryPool)     // no existing query in the pool, fallback to compiling a new query
            }
          }
        case None => compileXQuery(broker, source, fnConfigureContext, maybeXqueryPool)           // compile a new query
      }
    }

    /**
      * Executes a compiled XQuery.
      *
      * @param broker the database broker.
      * @param xqueryService the XQuery Service.
      * @param compiledQuery the compiled XQuery to execute.
      * @param contextSequence an optional context sequence for the XQuery to execute over.
      *
      * @return the result of the query, or an exception.
      */
    def executeCompiledQuery(broker: DBBroker, xqueryService: XQuery, compiledQuery: CompiledQuery, contextSequence: Option[Sequence]): IO[ExistServerException \/ Result] = {
      def execute(broker: DBBroker, compiledQuery: CompiledQuery, executionStartTime: ExecutionTime, contextSequence: Option[Sequence]) : IO[ExistServerException \/ Result] = {
        IO.blocking {
          try {
            val resultSequence = xqueryService.execute(broker, compiledQuery.compiledXquery, contextSequence.getOrElse(null))
            Result(resultSequence, compiledQuery.compilationTime, System.currentTimeMillis() - executionStartTime).right[ExistServerException]
          } catch {
            // NOTE(AR): bugs in eXist-db's XQuery implementation can produce a StackOverflowError - handle as any other server exception
            case e: StackOverflowError =>
              ExistServerException(e, compiledQuery.compilationTime, System.currentTimeMillis() - executionStartTime).left
          }
        }
      }

      for (
        executionStartTime <- IO.pure(System.currentTimeMillis());
        errorOrResult <- execute(broker, compiledQuery, executionStartTime, contextSequence)
          .handleErrorWith(fromExecutionException(_, compiledQuery.compilationTime, System.currentTimeMillis() - executionStartTime))
      ) yield errorOrResult
    }

    /**
      * Handler to manage an XPathException
      * differently from any other type of throwable.
      *
      * XPathException will be converted to a {@link Result}
      * of {@link QueryError}, wilst any other exception
      * will be converted to an {@link ExistServerException}.
      *
      * @param t the exception.
      * @param compilationTime the time taken to compile the XQuery.
      * @param executionTime the time taken to execute the XQuery.
      *
      * @return either a {@link Result}, or a {@link ExistServerException}.
      */
    def fromExecutionException(t: Throwable, compilationTime: CompilationTime, executionTime: ExecutionTime) : IO[ExistServerException \/ Result] = {
      IO {
        if (t.isInstanceOf[XPathException]) {
          Result(QueryError(t.asInstanceOf[XPathException]), compilationTime, executionTime).right[ExistServerException]
        } else if (t.isInstanceOf[ExistServerException]) {
          t.asInstanceOf[ExistServerException].left[Result] // pass-through
        } else {
          ExistServerException(t, compilationTime, executionTime).left[Result]
        }
      }
    }

    /**
      * Sets up the XQuery Context.
      *
      * @param context The XQuery Context to configure
      */
    def setupContext(context: XQueryContext)(staticBaseUri: Option[String], availableDocuments: Seq[(String, DocumentImpl)] = Seq.empty, availableCollections: Seq[(String, List[DocumentImpl])] = Seq.empty, availableTextResources: Seq[(String, Charset, String)] = Seq.empty, namespaces: Seq[Namespace] = Seq.empty, externalVariables: Seq[(String, Sequence)] = Seq.empty, decimalFormats: Seq[DecimalFormat] = Seq.empty, modules: Seq[Module] = Seq.empty, xpath1Compatibility : Boolean = false): XQueryContext = {

      // Turn on/off XPath 1.0 backwards compatibility.
      context.setBackwardsCompatibility(xpath1Compatibility)

      // set dynamically available documents
      type DocumentSupplier = TriFunctionE[DBBroker, Txn, String, com.evolvedbinary.j8fu.Either[DocumentImpl, org.exist.dom.persistent.DocumentImpl], XPathException]
      for ((uri, doc) <- availableDocuments) {
        val supplier: DocumentSupplier = (_, _, _) => com.evolvedbinary.j8fu.Either.Left(doc)
        context.addDynamicallyAvailableDocument(uri, supplier)
      }

      // set dynamically available collections
      type CollectionSupplier = TriFunctionE[DBBroker, Txn, String, Sequence, XPathException]
      for ((uri, docs) <- availableCollections) {
        val sequence = new ValueSequence()
        docs.map(sequence.add)
        val supplier: CollectionSupplier = (_, _, _) => sequence
        context.addDynamicallyAvailableCollection(uri, supplier)
      }

      // set dynamically available text resources
      type TextResourceSupplier = QuadFunctionE[DBBroker, Txn, String, Charset, Reader, XPathException]
      for ((uri, charset, text) <- availableTextResources) {
        val supplier: TextResourceSupplier = (_, _, _, _) => new StringReader(text)
        context.addDynamicallyAvailableTextResource(uri, charset, supplier)
      }

      // set the static base uri
      staticBaseUri.map(baseUri => context.setBaseURI(new AnyURIValue(baseUri)))

      // setup any static namespace
      for (namespace <- namespaces) {
        context.declareInScopeNamespace(namespace.prefix, namespace.uri.toString)
      }

      // bind external variables
      for ((name, value) <- externalVariables) {
        context.declareVariable(name, value)
      }

      // modify/create the decimal formats
      for (df <- decimalFormats ) {
        val unnamedDecimalFormat = context.getStaticDecimalFormat(null)
        val modifiedDecimalFormat = new org.exist.xquery.DecimalFormat(
          df.decimalSeparator.getOrElse(unnamedDecimalFormat.decimalSeparator),
          df.exponentSeparator.getOrElse(unnamedDecimalFormat.exponentSeparator),
          df.groupingSeparator.getOrElse(unnamedDecimalFormat.groupingSeparator),
          df.percent.getOrElse(unnamedDecimalFormat.percent),
          df.perMille.getOrElse(unnamedDecimalFormat.perMille),
          df.zeroDigit.getOrElse(unnamedDecimalFormat.zeroDigit),
          df.digit.getOrElse(unnamedDecimalFormat.digit),
          df.patternSeparator.getOrElse(unnamedDecimalFormat.patternSeparator),
          df.infinity.getOrElse(unnamedDecimalFormat.infinity),
          df.notANumber.getOrElse(unnamedDecimalFormat.NaN),
          df.minusSign.getOrElse(unnamedDecimalFormat.minusSign)
        )

        val decimalFormatName = df.name.getOrElse(new QName(Function.BUILTIN_FUNCTION_NS, "__UNNAMED__"))
        context.setStaticDecimalFormat(org.exist.dom.QName.fromJavaQName(decimalFormatName), modifiedDecimalFormat)
      }

      for (module <- modules) {
        val fileUri : XmldbURI = XmldbURI.createInternal(module.file.toAbsolutePath.toUri.toString)
        context.mapModule(module.uri.getStringValue, fileUri)
      }

      context
    }

    val executeQueryIo: IO[ExistServerException \/ Result] = brokerRes.use { broker =>

      val source = new StringSource(query)

      val maybeXQueryPoolIo : IO[Option[XQueryPool]] = IO.pure(cacheCompiled).flatMap { _ match {
        case true => getXQueryPool(broker).map(Some(_))
        case false => IO.none
      }}

      getXQueryService(broker).flatMap { xqueryService =>
          maybeXQueryPoolIo.flatMap { maybeXqueryPool =>

            val fnConfigureContext: XQueryContext => XQueryContext = setupContext(_)(staticBaseUri, availableDocuments, availableCollections, availableTextResources, namespaces, externalVariables, decimalFormats, modules, xpath1Compatibility)

            compiledXQuery(broker, source, fnConfigureContext, maybeXqueryPool)
              .use(compiledQuery => executeCompiledQuery(broker, xqueryService, compiledQuery, contextSequence))
              .handleErrorWith(throwable =>
                fromExecutionException(throwable, 0L, 0L)
              ) // We use 0L, 0L because an error here was caused by compilation, so there was no complete compilation, and also no execution
          }
      }
    }

    // TODO(AR) should we just return IO from here and allow the caller to do the execution?
    // run compilation and execution
    val executorRes : IO[ExistServerException \/ Result] = Resource.make {
      // build
      IO.delay(SingleThreadedExecutorPool.borrowSingleThreadedExecutor())
    } {
      // release
      singleThreadedExecutionContext =>
        IO.delay(SingleThreadedExecutorPool.returnSingleThreadedExecutor(singleThreadedExecutionContext))
    }.use(singleThreadedExecutor => executeQueryIo.evalOn(singleThreadedExecutor.executionContext))  // NOTE: eXist-db requires the broker to be acquired, used (e.g XQuery compilation and execution), and then released by the same thread.

    implicit val runtime = IORuntime.global
    val queryResult = executorRes.unsafeRunSync()
    queryResult
  }

  // TODO(AR) should return Throwable \/ String type
  /**
    * Serializes a Sequence to a String
    * using Adaptive serialization.
    *
    * @param sequence the sequence to serialize.
    *
    * @return the result of serializing the sequence.
    */
  def sequenceToStringAdaptive(sequence: Sequence) : String = {
    val outputProperties = new Properties()
    outputProperties.setProperty(OutputKeys.METHOD, "adaptive") // improves the output for expected value messages
    outputProperties.setProperty(OutputKeys.INDENT, "no")
    sequenceToString(sequence, outputProperties)
  }

  // TODO(AR) should return Throwable \/ String type
  /**
    * Serializes a Sequence to a String
    * using XML serialization.
    *
    * @param sequence the sequence to serialize.
    *
    * @return the result of serializing the sequence.
    */
  def sequenceToString(sequence: Sequence) : String = {
    sequenceToString(sequence, new Properties())
  }

  // TODO(AR) should return Throwable \/ String type
  /**
    * Serializes a Sequence to a String.
    *
    * If the serialization method is not
    * set in the properties, the the XML
    * method will be used.
    *
    * @param sequence the sequence to serialize.
    * @param outputProperties the serialization settings.
    *
    * @return the result of serializing the sequence.
    */
  def sequenceToString(sequence: Sequence, outputProperties: Properties): String = {
    val writerRes = Resource.make(IO { new StringWriter() })(writer => IO { writer.close() })

    val serializationIO : IO[String] = brokerRes.both(writerRes).use { case (broker, writer) =>
      IO.blocking {
        val serializer = new XQuerySerializer(broker, outputProperties, writer)
        serializer.serialize(sequence)
        writer.getBuffer.toString
          .replace("\r", "").replace("\n", ", ")  // further improves the output for expected value messages
      }
    }

    // TODO(AR) should we just return IO from here and allow the caller to do the execution?
    implicit val runtime = IORuntime.global
    serializationIO.unsafeRunSync()
  }
}
