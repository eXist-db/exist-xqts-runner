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

import org.parboiled2._
import org.parboiled2.CharPredicate.AlphaNum
import AssertTypeParser._
import org.exist.xqts.runner.AssertTypeParser.TypeCardinality.TypeCardinality
import org.exist.xqts.runner.AssertTypeParser.TypeNode.{ExistTypeDescription, ExplicitExistTypeDescription, WildcardExistTypeDescription}
import org.exist.xquery.{XPathException, Cardinality => ExistCardinality}
import org.exist.xquery.value.{Type => ExistType}

import scala.util.Try

object AssertTypeParser {
  private val Hyphen = CharPredicate.from(_ == '-')

  // AST Nodes generated by the parser
  sealed trait AstNode

  object TypeNode {
    type ExistCardinalityType = Int  // eXist-db's Cardinality type
    type ExistTypeType = Int  // eXist-db's Cardinality type
    sealed trait ExistTypeDescription {
      def hasParameterTypes : Boolean
    }
    case class ExplicitExistTypeDescription(base: ExistTypeType, parameterTypes: Option[Seq[ExistTypeDescription]], cardinality: Option[ExistCardinalityType]) extends ExistTypeDescription {
      override def hasParameterTypes = parameterTypes.map(_.nonEmpty).getOrElse(false)
    }
    case object WildcardExistTypeDescription extends ExistTypeDescription {
      override def hasParameterTypes = false
    }
  }
  sealed trait TypeNode extends AstNode {
    /**
      * Convert to a representation
      * which can be used more easily
      * with eXist-db.
      *
      * @return a representation which
      *  is more easily used with eXist-db
      */
    @throws[XPathException]
    def asExistTypeDescription : ExistTypeDescription
  }


  trait ExplicitTypeNode extends TypeNode {
    def name: TypeNameNode;
    def typeParameters: Option[ParametersNode];
    def cardinality: Option[CardinalityNode];

    @throws[XPathException]
    override def asExistTypeDescription = {
      def isFunctionType(name: TypeNameNode) : Boolean = {
        name.localName.equals("function") || name.localName.equals("map") || name.localName.equals("array")
      }

      val existType = ExistType.getType(
        typeParameters.flatMap { parametersNode =>
          if (isFunctionType(name) || (parametersNode.parameters.size == 1 && parametersNode.parameters.head == WildcardTypeNode)) {
            Some(name.asXdmTypeName + "(*)")
          } else {
            Some(name.asXdmTypeName + "()")
          }
        }.getOrElse(name.asXdmTypeName)
      )

      //      match {
      //          case "node()" =>
      //            // NOTE: for some reason eXist-db does not support looking up Node type by name?!?
      //            ExistType.NODE
      //          case typeName =>
      //            ExistType.getType(typeName)
      //        }

      ExplicitExistTypeDescription(
        existType,
        typeParameters.map(x => x.parameters.map(y => y.asExistTypeDescription)),
        cardinality.map(_.cardinality  match {
          case TypeCardinality.* => ExistCardinality.ZERO_OR_MORE
          case TypeCardinality.+ => ExistCardinality.ONE_OR_MORE
          case TypeCardinality.? => ExistCardinality.ZERO_OR_ONE
        })
      )
    }
  }

  case class ExplicitFunctionTypeNode(typeParameters: Option[ParametersNode], returnType: Option[TypeNode], cardinality: Option[CardinalityNode]) extends ExplicitTypeNode {
    def name = TypeNameNode(None, "function")
  }

  case class ExplicitNonFunctionTypeNode(name: TypeNameNode, typeParameters: Option[ParametersNode], cardinality: Option[CardinalityNode]) extends ExplicitTypeNode

  case object WildcardTypeNode extends TypeNode {
    override def asExistTypeDescription = WildcardExistTypeDescription
  }

  case class TypeNameNode(prefix: Option[String], localName: String) extends AstNode {
    def asXdmTypeName : String = prefix.map(_ + ':' + localName).getOrElse(localName)
  }

  case class ParametersNode(parameters: Seq[TypeNode]) extends AstNode

  case class CardinalityNode(cardinality: TypeCardinality) extends AstNode

  object TypeCardinality extends Enumeration {
    type TypeCardinality = Value
    val *, +, ? = Value
  }

  /**
    * Parses a type description
    * as expressed in XQTS.
    *
    * @param text The complete test to parse.
    *
    * @return the parse result.
    */
  def parse(text: String) : Try[TypeNode] = {
    new AssertTypeParser(text)
      .InputLine
      .run()
  }
}

/**
  * Simple parser for parsing type descriptions
  * expressed in XQTS as a String.
  *
  * For example:
  *
  * document-node()*
  * xs:boolean
  * element(employee)
  * xs:anyURI?
  * map(*)
  * map(xs:string, xs:integer)
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
class AssertTypeParser(val input: ParserInput) extends Parser {

  /**
    * Parse all input for a type description.
    *
    * @return The parse result
    */
  def InputLine = rule { Type ~ EOI }

  /**
    * Parse any input, matching the first type description.
    *
    * @return The parse result
    */
  def Type: Rule1[TypeNode] = rule {
    WildcardType | ExplicitType
  }

  private def WildcardType: Rule1[TypeNode] = rule {
    ch('*') ~> (() => WildcardTypeNode)
  }

  private def ExplicitType: Rule1[ExplicitTypeNode] = rule {
    ExplicitFunctionType | ExplicitNonFunctionType
  }

  private def ExplicitFunctionType : Rule1[ExplicitFunctionTypeNode] = rule {
    optional("xs:") ~ "function" ~ optional(Parameters) ~ optional(FunctionReturnType) ~ optional(Cardinality) ~> ExplicitFunctionTypeNode
  }

  private def FunctionReturnType : Rule1[TypeNode] = rule {
    ws() ~ "as" ~ ws() ~ Type
  }

  private def ExplicitNonFunctionType: Rule1[ExplicitNonFunctionTypeNode] = rule {
    TypeName ~ optional(Parameters) ~ optional(Cardinality) ~> ExplicitNonFunctionTypeNode
  }


  private def TypeName : Rule1[TypeNameNode] = rule {
    optional(Prefix) ~ LocalPart ~> ((prefix: Option[String], localPart: String) => TypeNameNode(prefix, localPart))
  }

  private def Prefix : Rule1[String] = rule {
    capture(oneOrMore(AlphaNum)) ~ ':'
  }

  private def LocalPart : Rule1[String] = rule {
    capture(oneOrMore(AlphaNum ++ Hyphen))
  }

  private def Parameters: Rule1[ParametersNode] = rule {
    ch('(') ~ optional(ParameterTypes) ~ ch(')') ~> ((parameterTypes: Option[Seq[TypeNode]]) => ParametersNode(parameterTypes.getOrElse(Seq.empty)))
  }

  private def ParameterTypes : Rule1[Seq[TypeNode]] = rule {
    ParameterType ~ zeroOrMore(ch(',') ~ ws() ~ ParameterType) ~> ((first: TypeNode, others: Seq[TypeNode]) => first +: others)
  }

  private def ParameterType : Rule1[TypeNode] = Type

  private def Cardinality : Rule1[CardinalityNode] = rule {
    ch('*') ~> (() => CardinalityNode(TypeCardinality.*)) |
      ch('+') ~> (() => CardinalityNode(TypeCardinality.+)) |
        ch('?') ~> (() => CardinalityNode(TypeCardinality.?))
  }

  /**
    * Whitespace!
    */
  private def ws() : Rule0 = rule {
    zeroOrMore(CharPredicate(Seq(' ', '\t', '\n', 0x0B.toChar, '\f', '\r')))
  }
}
