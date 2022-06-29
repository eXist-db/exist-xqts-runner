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

import java.net.URI
import java.nio.file.Path
import java.util.regex.Pattern
import akka.actor.Actor

import javax.xml.namespace.QName
import net.sf.saxon.value.AnyURIValue
import org.exist.xqts.runner.XQTSParserActor.DependencyType.DependencyType
import org.exist.xqts.runner.XQTSParserActor.Feature.Feature
import org.exist.xqts.runner.XQTSParserActor.Spec.Spec
import org.exist.xqts.runner.XQTSParserActor.XmlVersion.XmlVersion
import org.exist.xqts.runner.XQTSParserActor.XsdVersion.XsdVersion

import scala.annotation.unused

/**
  * An initial Actor that parses
  * an XQTS.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
trait XQTSParserActor extends Actor {
}

/**
  * Objects and Classes that are used for parsing an XQTS.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
object XQTSParserActor {
  case class Parse(xqtsVersion: XQTSVersion, xqtsPath: Path, features: Set[Feature], specs: Set[Spec], xmlVersions: Set[XmlVersion], xsdVersions: Set[XsdVersion], testSets: Either[Set[String], Pattern], testCases: Set[String], excludeTestSets: Set[String], excludeTestCases: Set[String])
  case class ParseComplete(xqtsVersion: XQTSVersion, xqtsPath: Path, matchedTestSets: Int)

  object Validation extends Enumeration {
    type Validation = Value
    val strict, lax, skip = Value
  }

  object Role {
    def parse(role : String) : Role = {
      role match {
        case "." => ContextItemRole
        case _ if role.charAt(0).equals('$') => ExternalVariableRole(role.substring(1))
        case _ => throw new IllegalArgumentException(s"source role: '$role' is not a valid role")
      }
    }

    def isContextItem(role: Role): Boolean = {
      role == ContextItemRole
    }
  }
  sealed abstract class Role
  case object ContextItemRole extends Role
  case class ExternalVariableRole(name: String) extends Role


  case class Environment(name: String, schemas: List[Schema] = List.empty, sources: List[Source] = List.empty, resources: List[Resource] = List.empty, params: List[Param] = List.empty, contextItem: Option[String] = None, decimalFormats: List[DecimalFormat] = List.empty, namespaces: List[Namespace] = List.empty, collections: List[Collection] = List.empty, staticBaseUri: Option[String] = None, collation: Option[Collation] = None)
  case class Schema(uri: Option[AnyURIValue], file: Option[Path], xsdVersion: Float = 1.0f, description: Option[String] = None, created: Option[Created] = None, modifications: List[Modified] = List.empty)
  case class Source(role: Option[Role], file: Path, uri: Option[String], validation: Option[Validation.Validation] = None, description: Option[String] = None, created: Option[Created] = None, modifications: List[Modified] = List.empty)
  case class Resource(file: Path, uri: String, mediaType: Option[String] = None, encoding: Option[String], description: Option[String] = None, created: Option[Created] = None, modifications: List[Modified] = List.empty)
  case class Param(name: String, select: Option[String] = None, as: Option[String] = None, source: Option[String] = None, declared: Boolean = false)
  case class DecimalFormat(name: Option[QName] = None, decimalSeparator: Option[Int] = None, exponentSeparator: Option[Int] = None, groupingSeparator: Option[Int] = None, zeroDigit: Option[Int] = None, digit: Option[Int] = None, minusSign: Option[Int] = None, percent: Option[Int] = None, perMille: Option[Int] = None, patternSeparator: Option[Int] = None, infinity: Option[String] = None, notANumber: Option[String] = None)
  case class Namespace(prefix: String, uri: URI)
  case class Collection(uri: AnyURIValue, sources: List[Source] = List.empty)
  case class Collation(uri: URI, default: Boolean = false)

  case class Created(by: String, on: String)
  case class Modified(by: String, on: String, change: String)

  case class TestSetRef(xqtsVersion: XQTSVersion, name: String, file: Path)

  type TestSetName = String
  type TestCaseName = String
  type TestCaseId = (TestSetName, TestCaseName)

  case class TestSet(name: TestSetName, covers: String, description: Option[String] = None, links: Seq[Link] = List.empty, dependencies: Seq[Dependency] = Seq.empty, testCases: Seq[TestCase] = Seq.empty)
  case class Link(`type`: String, document: String, section: Option[String] = None)
  case class Module(uri: AnyURIValue, file: Path)
  case class Dependency(`type`: DependencyType, value: String, satisfied: Boolean)
  case class TestCase(file: Path, name: TestCaseName, covers: String, description: Option[String] = None, created: Option[Created] = None, modifications: Seq[Modified] = Seq.empty, environment: Option[Environment] = None, modules: Seq[Module] = Seq.empty, dependencies: Seq[Dependency] = Seq.empty, test: Option[Either[String, Path]] = None, result: Option[Result] = None)
  sealed trait Result

  /*
   XQTS Assertions
  */
  sealed trait Assertion extends Result
  sealed trait Assertions extends Result {
    def assertions: List[Result]
    def :+(assertion: Result) : Assertions
  }
  case class AllOf(assertions: List[Result]) extends Assertions {
    override def :+(assertion: Result) : AllOf = this.copy(assertions = assertion +: this.assertions)
  }
  case class AnyOf(assertions: List[Result]) extends Assertions {
    override def :+(assertion: Result) : AnyOf = this.copy(assertions = assertion +: this.assertions)
  }
  sealed trait ValueAssertion[T] extends Assertion {
    def expected: T
  }
  case class Assert(expected: String) extends ValueAssertion[String]
  case class AssertCount(expected: Int) extends ValueAssertion[Int]
  case class AssertDeepEquals(expected: String) extends ValueAssertion[String]
  case class AssertEq(expected: String) extends ValueAssertion[String]
  case class AssertPermutation(expected: String) extends ValueAssertion[String]
  case class AssertSerializationError(expected: String) extends ValueAssertion[String]
  case class AssertStringValue(value: String, normalizeSpace: Boolean) extends Assertion
  case class AssertType(expected: String) extends ValueAssertion[String]
  case class AssertXml(expected: Either[String, Path], ignorePrefixes: Boolean = false) extends ValueAssertion[Either[String, Path]]
  case class SerializationMatches(expected: Either[String, Path], flags: Option[String] = None) extends ValueAssertion[Either[String, Path]]
  case object AssertEmpty extends Assertion
  case object AssertTrue extends Assertion
  case object AssertFalse extends Assertion
  case class Error(expected: String) extends ValueAssertion[String]

  /**
    * Enumeration of XQTS dependency types.
    */
  object DependencyType extends Enumeration {
    import scala.language.implicitConversions

    protected case class DependencyTypeVal(xqtsName: String) extends super.Val
    implicit def valueToDependencyTypeVal(x: Value): DependencyTypeVal = x.asInstanceOf[DependencyTypeVal]
    type DependencyType = DependencyTypeVal

    @throws[IllegalArgumentException]
    def fromXqtsName(xqtsName: String) : DependencyType = {
      this.values.find(_.xqtsName == xqtsName) match {
        case Some(dependencyType) => dependencyType
        case None => throw new IllegalArgumentException(s"No dependency type with XQTS name: $xqtsName")
      }
    }

    val Calendar = DependencyTypeVal("calendar")
    val CollectionStability = DependencyTypeVal("collection-stability")
    val DefaultLanguage = DependencyTypeVal("default-language")
    val DirectoryAsCollectionUri = DependencyTypeVal("directory-as-collection-uri")
    val Feature = DependencyTypeVal("feature")
    val FormatIntegerSequence = DependencyTypeVal("format-integer-sequence")
    val Language = DependencyTypeVal("language")
    val Limits = DependencyTypeVal("limits")
    val Spec = DependencyTypeVal("spec")
    val SchemaAware = DependencyTypeVal("schemaAware")
    val UnicodeVersion = DependencyTypeVal("unicode-version")
    val UnicodeNormalizationForm = DependencyTypeVal("unicode-normalization-form")
    val XmlVersion = DependencyTypeVal("xml-version")
    val XsdVersion = DependencyTypeVal("xsd-version")
  }

  /**
    * Enumeration of XQTS dependency spec values.
    */
  object Spec extends Enumeration {
    type Spec = Value
    val XP10, XP20, XP30, XP31, XQ10, XQ30, XQ31, XT30 = Value

    /**
      * Returns all specs which implement at
      * least {@code spec}.
      *
      * @param spec the least spec.
      *
      * @return all specs that implement at least {@code spec}.
      */
    def atLeast(spec: Spec) : Set[Spec] = {
      spec match {
        case XP10 =>
          Set(XP10, XP20, XP30, XP31)
        case XP20 =>
          Set(XP20, XP30, XP31)
        case XP30 =>
          Set (XP30, XP31)
        case XP31 =>
          Set (XP31)

        case XQ10 =>
          Set(XQ10, XQ30, XQ31)
        case XQ30 =>
          Set(XQ30, XQ31)
        case XQ31 =>
          Set(XQ31)

        case XT30 =>
          Set(XT30)
      }
    }
  }

  /**
    * Enumeration of XQTS dependency feature values.
    */
  object Feature extends Enumeration {
    import scala.language.implicitConversions

    protected case class FeatureVal(xqtsName: String) extends super.Val
    implicit def valueToFeatureVal(x: Value): FeatureVal = x.asInstanceOf[FeatureVal]
    type Feature = FeatureVal

    @throws[IllegalArgumentException]
    def fromXqtsName(xqtsName: String) : Feature = {
      this.values.find(_.xqtsName == xqtsName) match {
        case Some(feature) => feature
        case None => throw new IllegalArgumentException(s"No feature with XQTS name: $xqtsName")
      }
    }

    val CollectionStability = FeatureVal("collection-stability")
    val DirectoryAsCollectionUri = FeatureVal("directory-as-collection-uri")
    val HigherOrderFunctions = FeatureVal("higherOrderFunctions")
    val InfosetDTD = FeatureVal("infoset-dtd")
    val ModuleImport = FeatureVal("moduleImport")
    val NamespaceAxis = FeatureVal("namespace-axis")
    val SchemaLocationHint = FeatureVal("schema-location-hint")
    val SchemaAware = FeatureVal("schema-aware")
    val SchemaImport = FeatureVal("schema-import")
    val SchemaValidation = FeatureVal("schema-validation")
    val Serialization = FeatureVal("serialization")
    val StaticTyping = FeatureVal("staticTyping")
    val TypedData = FeatureVal("typedData")
    val XPath_1_0_Compatibility = FeatureVal("xpath-1.0-compatibility")
    val TransformXSLT = FeatureVal("fn-transform-XSLT")
    val TransformXSLT_30 = FeatureVal("fn-transform-XSLT30")
  }

  /**
    * Enumeration of XQTS dependency xsd-version values.
    */
  object XsdVersion extends Enumeration {
    import scala.language.implicitConversions

    protected case class XsdVersionVal(xqtsName: String) extends super.Val
    implicit def valueToXsdVersionVal(x: Value): XsdVersionVal = x.asInstanceOf[XsdVersionVal]
    type XsdVersion = XsdVersionVal

    @throws[IllegalArgumentException]
    def fromXqtsName(xqtsName: String): XsdVersion = {
      this.values.find(_.xqtsName == xqtsName) match {
        case Some(feature) => feature
        case None => throw new IllegalArgumentException(s"No XSD Version with XQTS name: $xqtsName")
      }
    }

    val XSD10 = XsdVersionVal("1.0")
    val XSD11 = XsdVersionVal("1.1")
  }

  /**
    * Enumeration of XQTS dependency xml-version values.
    */
  object XmlVersion extends Enumeration {
    import scala.language.implicitConversions

    protected case class XmlVersionVal(xqtsName: String) extends super.Val
    implicit def valueToXmlVersionVal(x: Value): XmlVersionVal = x.asInstanceOf[XmlVersionVal]
    type XmlVersion = XmlVersionVal

    @throws[IllegalArgumentException]
    def fromXqtsName(xqtsName: String): XmlVersion = {
      this.values.find(_.xqtsName == xqtsName) match {
        case Some(feature) => feature
        case None => throw new IllegalArgumentException(s"No XML Version with XQTS name: $xqtsName")
      }
    }

    val XML10_4thOrEarlier = XmlVersionVal("1.0:4-")
    val XML10_5thOrLater = XmlVersionVal("1.0:5+")
    val XML10 = XmlVersionVal("1.0")
    val XML11 = XmlVersionVal("1.1")

    /**
      * Given a base version,
      * returns all compatible versions.
      *
      * @param base the base versions
      * @return the compatible versions
      */
    def compatible(base: XmlVersion) : Set[XmlVersion] = {
      base match {
        case XML10_4thOrEarlier =>
          Set(XML10_4thOrEarlier)

        case XML10_5thOrLater =>
          Set(XML10_5thOrLater, XML10, XML11)

        case XML10 =>
          Set(XML10_5thOrLater, XML10, XML11)

        case XML11 =>
          Set(XML11)

        case unknownVersion =>
          throw new IllegalStateException(s"Unsupported XML version: $unknownVersion")
      }
    }
  }

  type Missing = Seq[String]

  /**
    * Checks for missing dependencies.
    *
    * @param required The required dependencies.
    * @param enabledFeatures The features which are enabled.
    * @param enabledSpecs The specifications which are enabled.
    * @param enabledXmlVersions The XML versions which are enabled.
    * @param enabledXsdVersions The XSD versions which are enabled.
    *
    * @return A list of all missing dependencies, or an empty list
    *         if there are no missing depdencies.
    */
  def missingDependencies(required: Seq[Dependency], enabledFeatures: Set[Feature], enabledSpecs: Set[Spec], enabledXmlVersions: Set[XmlVersion], enabledXsdVersions: Set[XsdVersion]) : Missing = {
    type Missed = Option[String]

    def hasEnabledFeature(requiredFeature: String) : Missed = {
      if (enabledFeatures.map(_.xqtsName).contains(requiredFeature)) {
        None
      } else {
       Some(requiredFeature)
      }
    }

    def hasEnabledSpec(requiredSpec: String) : Missed = {
      def lookupSpecs(specStr: String) : Set[Spec] = {
        if (specStr.endsWith("+")) {
          Spec.atLeast(Spec.withName(specStr.substring(0, specStr.length - 1)))
        } else {
          Set(Spec.withName(specStr))
        }
      }
      val anyRequiredSpecs : Set[Spec] = requiredSpec.split(' ').toSet.flatMap(lookupSpecs)
      if (anyRequiredSpecs.find(enabledSpecs.contains(_)).nonEmpty) {
        None
      } else {
        Some(requiredSpec)
      }
    }

    def hasEnabledXmlVersion(requiredXmlVersion: String) : Missed = {
      def check(version: String): Missed = {
        val compatibleVersions : Set[XmlVersion] = XmlVersion.compatible(XmlVersion.fromXqtsName(version))
        if (compatibleVersions.find(enabledXmlVersions.contains(_)).nonEmpty) {
          None
        } else {
          Some(version)
        }
      }

      val requiredXmlVersions = requiredXmlVersion.split(' ')
      requiredXmlVersions.foldLeft(Option.empty[String]){(accum, x) =>
       accum match {
         case missing @ Some(_) => missing
         case None =>
           check(x)
       }
      }
    }

    def hasEnabledXsdVersion(requiredXsdVersion: String) : Missed = {
      if (enabledXsdVersions.map(_.xqtsName).contains(requiredXsdVersion)) {
        None
      } else {
        Some(requiredXsdVersion)
      }
    }

    @unused
    def firstMissing(test: String => Missed, required: Seq[Dependency]) : Missed = {
      required.foldLeft(Option.empty[String]) { case (accum, x) =>
        accum.orElse(test(x.value))
      }
    }

    def allMissing(test: String => Missed, required: Seq[Dependency]) : Missing = {
      required.foldLeft(Seq.empty[String]) { case (accum, x) =>
        test(x.value)
          .filter(_ => x.satisfied)
          .map(missed => s"${x.`type`.xqtsName}=${missed}")
          .map(_ +: accum)
          .getOrElse(accum)
      }
    }

    //TODO(AR) implement further dependency types
    val requiredFeatures = required.filter(_.`type` == DependencyType.Feature)
    val requiredSpecs = required.filter(_.`type` == DependencyType.Spec)
    val requiredXmlVersions = required.filter(_.`type` == DependencyType.XmlVersion)
    val requiredXsdVersions = required.filter(_.`type` == DependencyType.XsdVersion)

    allMissing(hasEnabledFeature, requiredFeatures) ++
      allMissing(hasEnabledSpec, requiredSpecs) ++
        allMissing(hasEnabledXmlVersion, requiredXmlVersions) ++
          allMissing(hasEnabledXsdVersion, requiredXsdVersions)
  }
}

/**
  * Exception type for errors that occure during
  * parsing of XQTS, due to unexpected structure
  * or values within the XQTS.
  *
  * @param message a description of the error.
  */
case class XQTSParseException(message: String) extends Exception(message)
