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

import org.exist.xqts.runner.XQTSParserActor.Feature.Feature
import org.exist.xqts.runner.XQTSParserActor.Spec.Spec
import org.exist.xqts.runner.XQTSParserActor.XmlVersion.XmlVersion
import org.exist.xqts.runner.XQTSParserActor.XsdVersion.XsdVersion
import org.exist.xqts.runner.XQTSParserActor._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class XQTSParserActorSpec extends AnyWordSpec with Matchers {

  private val enabledFeatures: Set[Feature] = Set(Feature.HigherOrderFunctions)
  private val enabledSpecs: Set[Spec] = Set(Spec.XQ31)
  private val enabledXmlVersions: Set[XmlVersion] = Set(XmlVersion.XML10)
  private val enabledXsdVersions: Set[XsdVersion] = Set(XsdVersion.XSD10)

  private def missing(dependencies: Dependency*): Missing =
    missingDependencies(dependencies, enabledFeatures, enabledSpecs, enabledXmlVersions, enabledXsdVersions)

  private def feature(value: String, satisfied: Boolean) =
    Dependency(DependencyType.Feature, value, satisfied)

  "missingDependencies" when {

    "the dependency must be satisfied" should {

      "be met when the feature is enabled" in {
        missing(feature("higherOrderFunctions", satisfied = true)) shouldBe empty
      }

      "be unmet when the feature is not enabled" in {
        missing(feature("staticTyping", satisfied = true)) should contain only "feature=staticTyping"
      }
    }

    // XQTS uses satisfied="false" to mean "this test only applies where the
    // dependency is absent", e.g. require-higher-order-function-1-ns asserts
    // that an error is raised when higher order functions are unsupported.
    "the dependency must not be satisfied" should {

      "be unmet when the feature is enabled" in {
        missing(feature("higherOrderFunctions", satisfied = false)) should
          contain only "feature=higherOrderFunctions,satisfied=false"
      }

      "be met when the feature is not enabled" in {
        missing(feature("staticTyping", satisfied = false)) shouldBe empty
      }
    }

    "inverting non-feature dependencies" should {

      "report an enabled spec as unmet when it must not be satisfied" in {
        missing(Dependency(DependencyType.Spec, "XQ31", satisfied = false)) should
          contain only "spec=XQ31,satisfied=false"
      }

      "report a disabled spec as met when it must not be satisfied" in {
        missing(Dependency(DependencyType.Spec, "XQ10", satisfied = false)) shouldBe empty
      }

      "report an enabled xsd-version as unmet when it must not be satisfied" in {
        missing(Dependency(DependencyType.XsdVersion, "1.0", satisfied = false)) should
          contain only "xsd-version=1.0,satisfied=false"
      }

      "report an enabled xml-version as unmet when it must not be satisfied" in {
        missing(Dependency(DependencyType.XmlVersion, "1.0", satisfied = false)) should
          contain only "xml-version=1.0,satisfied=false"
      }
    }

    "given several dependencies" should {

      "collect every unmet dependency" in {
        missing(
          feature("higherOrderFunctions", satisfied = true),
          feature("staticTyping", satisfied = true),
          Dependency(DependencyType.Spec, "XQ10", satisfied = true)
        ) should contain theSameElementsAs Seq("feature=staticTyping", "spec=XQ10")
      }

      "be met when all dependencies hold" in {
        missing(
          feature("higherOrderFunctions", satisfied = true),
          feature("staticTyping", satisfied = false),
          Dependency(DependencyType.Spec, "XQ31", satisfied = true),
          Dependency(DependencyType.XmlVersion, "1.0", satisfied = true),
          Dependency(DependencyType.XsdVersion, "1.0", satisfied = true)
        ) shouldBe empty
      }
    }

    "a spec dependency names several alternatives" should {

      "be met when any alternative is enabled" in {
        missing(Dependency(DependencyType.Spec, "XQ10 XQ31", satisfied = true)) shouldBe empty
      }

      "be met when an open ended range covers an enabled spec" in {
        missing(Dependency(DependencyType.Spec, "XQ30+", satisfied = true)) shouldBe empty
      }
    }
  }

  "Feature.fromXqtsName" should {

    "round-trip every feature's xqtsName back to itself" in {
      for (f <- Feature.values) {
        Feature.fromXqtsName(f.xqtsName) shouldBe f
      }
    }
  }
}
