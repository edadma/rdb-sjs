package xyz.hyperreal.rdb

import scala.util.parsing.input.{CharSequenceReader, Positional}
import util.parsing.combinator.RegexParsers

import xyz.hyperreal.lia.Math


class RQLParser extends RegexParsers {
	def pos = positioned( success(new Positional{}) ) ^^ { _.pos }

	def number: Parser[ValueExpression] = positioned( """\d+(\.\d*)?""".r ^^ {
		case n if n contains '.' => FloatLit( n )
		case n => IntegerLit( n ) } )

	def string: Parser[ValueExpression] =
		positioned(
			(("'" ~> """[^'\n]+""".r <~ "'") |
			("\"" ~> """[^"\n]+""".r <~ "\"")) ^^ StringLit )

	def ident = pos ~ """[a-zA-Z_$][a-zA-Z0-9_#$]*""".r ^^ { case p ~ n => Ident( p, n ) }

	def statement: Parser[StatementAST] =
		assignStatement |
		insertStatement |
		deleteStatement |
		tupleseq |
		relation

	def assignStatement =
		(ident <~ "<-") ~ relation ^^ { case n ~ r => AssignRelationStatement( n, r ) }

	def insertStatement =
		("create" ~> ident) ~ columns ^^ { case n ~ c => InsertRelationStatement( n, ListRelationExpression(c, Nil) ) } |
		("insert" ~> ident) ~ relation ^^ { case n ~ r => InsertRelationStatement( n, r ) } |
		("insert" ~> ident) ~ tupleseq ^^ { case n ~ t => InsertTupleseqStatement( n, t ) } |
		("insert" ~> ident) ~ tuple ^^ { case n ~ t => InsertTupleseqStatement( n, TupleseqLit(List(t)) ) }

	def deleteStatement =
		("delete" ~> ident) ~ ("[" ~> logicalExpression <~ "]") ^^ { case n ~ c => DeleteStatement( n, c ) }

	def tupleseq =
		relation ~ ("(" ~> rep1sep(valueExpression, ",") <~ ")") ^^ { case r ~ c => ProjectionTupleseqExpression( r, c ) } |
		tupleseqLit

	def tupleseqLit =
		"[" ~> rep1sep(tuple, ",") <~ "]" ^^ TupleseqLit

	def relation: Parser[RelationExpression] =
		projectionRelation

	def projectionRelation = positioned(
		selectionRelation ~ ("[" ~> rep1sep(ident, ",") <~ "]") ^^ { case r ~ c => ProjectionRelationExpression( r, c ) } |
		selectionRelation
		)

	def selectionRelation = positioned(
		innerJoinRelation ~ ("[" ~> logicalExpression <~ "]") ^^ { case r ~ c => SelectionRelationExpression( r, c ) } |
		innerJoinRelation
		)

	def innerJoinRelation = positioned(
		relationPrimary ~ ("[" ~> logicalExpression <~ "]") ~ relationPrimary ^^ { case l ~ c ~ r => InnerJoinRelationExpression( l, c, r ) } |
		relationPrimary
		)

	def relationPrimary = positioned(
		"(" ~> relation <~ ")" |
		("{" ~> columns) ~ (repsep(tuple, ",") <~ "}") ^^ { case c ~ d => ListRelationExpression( c, d ) } |
		ident ^^ RelationVariableExpression
		)

	def columns = "[" ~> rep1sep(column, ",") <~ "]"

	def column =
		ident ~ (":" ~> ident) ~ opt(pos <~ "*") ^^ {
			case n ~ t ~ None => ColumnSpec( n, t.pos, Some(t.name), null, null, null )
			case n ~ t ~ Some(p) => ColumnSpec( n, t.pos, Some(t.name), p, null, null ) } |
		ident ~ pos ~ opt(pos <~ "*") ^^ {
			case n ~ tp ~ None => ColumnSpec( n, tp, None, null, null, null )
			case n ~ tp ~ Some(pkp) => ColumnSpec( n, tp, None, pkp, null, null ) }

	def tuple = positioned( "(" ~> rep1sep(valueExpression, ",") <~ ")" ^^ TupleExpression )

	def valueExpression: Parser[ValueExpression] =
		additiveExpression

	def additiveExpression: Parser[ValueExpression] = multiplicativeExpression ~ rep("+" ~ multiplicativeExpression | "-" ~ multiplicativeExpression) ^^ {
		case expr ~ list => list.foldLeft( expr ) {
			case (x, o ~ y) => BinaryValueExpression( x, o, lookup(o), y )
		}
	}

	def multiplicativeExpression: Parser[ValueExpression] = valuePrimary ~ rep("*" ~ valuePrimary | "/" ~ valuePrimary) ^^ {
		case expr ~ list => list.foldLeft( expr ) {
			case (x, o ~ y) => BinaryValueExpression( x, o, lookup(o), y )
		}
	}

	def applicativeExpression: Parser[ValueExpression] =
		valuePrimary ~ ("(" ~> repsep(valueExpression, ",") <~ ")") ^^ {
			case f ~ args => ApplicativeValueExpression( f, args ) } |
		valuePrimary

	def valuePrimary: Parser[ValueExpression] =
		number |
		string |
		positioned( "A" ^^^ MarkLit(A) ) |
		positioned( "I" ^^^ MarkLit(I) ) |
		positioned( ident ~ ("." ~> ident) ^^ { case t ~ c => ValueColumnExpression( t, c ) } ) |
		positioned( ident ^^ ValueVariableExpression )

	private def lookup( s: String ) =
		Math.lookup(Symbol(
			s match {
				case "=" => "=="
				case "/=" => "!="
				case _ => s
			} ) )

	def comparison = "<" | "<=" | "=" | "/=" | ">" | ">="

	def logicalExpression =
		valueExpression ~ rep1(comparison ~ valueExpression) ^^ {
			case l ~ cs => ComparisonExpression( l, cs map {case c ~ v => (c, lookup(c), v)} ) } |
		logicalPrimary

	def logicalPrimary = positioned(
		"true" ^^^ LogicalLit( TRUE ) |
		"false" ^^^ LogicalLit( FALSE ) |
		"maybe-a" ^^^ LogicalLit( MAYBE_A ) |
		"maybe-i" ^^^ LogicalLit( MAYBE_I )
		)

	def parseFromString[T]( src: String, grammar: Parser[T] ) = {
		parseAll( grammar, new CharSequenceReader(src) ) match {
			case Success( tree, _ ) => tree
			case NoSuccess( error, rest ) => problem( rest.pos, error )
		}
	}

}