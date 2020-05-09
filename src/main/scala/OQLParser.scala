package xyz.hyperreal.rdb_sjs

import scala.util.parsing.input.{CharSequenceReader, Positional}
import util.parsing.combinator.RegexParsers

object OQLParser {

  def parseStatement(query: String) = {
    val p = new OQLParser

    p.parseFromString(query, p.query)
  }

}

class OQLParser extends RegexParsers {

  def pos = positioned(success(new Positional {})) ^^ {
    _.pos
  }

  def number: Parser[ValueExpression] =
    positioned("""\-?\d+(\.\d*)?""".r ^^ {
      case n if n contains '.' => FloatLit(n)
      case n                   => IntegerLit(n)
    })

  def string: Parser[ValueExpression] =
    positioned(
      (("'" ~> """[^'\n]*""".r <~ "'") |
        ("\"" ~> """[^"\n]*""".r <~ "\"")) ^^ StringLit)

  def ident =
    positioned("""[a-zA-Z_#$][a-zA-Z0-9_#$]*""".r ^^ Ident)

  def query =
    ident ~ opt(select) ~ opt(project) ~ opt(order) ~ opt(group) ^^ {
      case r ~ s ~ p ~ o ~ g => OQLQuery(r, s, p, o, g)
    }

  def project =
    "{" ~> rep1sep(projectExpression, ",") <~ "}" | "." ~ simpleProjectExpression | "^" ~ simpleProjectExpression

  def simpleProjectExpression = fieldProject | ident

  def projectExpression = fieldProject | expression

  def fieldProject = ident ~ project

  def variable = rep1sep(ident, ".") ^^

  def expression = ident

  def select = "[" ~> expression <~ "]"

  def order = "<" ~> rep1sep(orderExpression, ",") <~ ">"

  def orderExpression = expression ~ opt("/" | "\\")

  def group = "(" ~> rep1sep(expression, ",") <~ ")"

  def parseFromString[T](src: String, grammar: Parser[T]) = {
    parseAll(grammar, new CharSequenceReader(src)) match {
      case Success(tree, _)       => tree
      case NoSuccess(error, rest) => problem(rest.pos, error)
    }
  }

}
