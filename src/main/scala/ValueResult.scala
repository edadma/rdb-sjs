package xyz.hyperreal.rdb

import scala.util.parsing.input.Position

import xyz.hyperreal.lia.FunctionMap


trait ValueResult {
	val pos: Position
	val heading: String
	val typ: Type
}

case class LiteralValue( pos: Position, heading: String, typ: Type, value: AnyRef ) extends ValueResult
case class VariableValue( pos: Position, heading: String, typ: Type, value: AnyRef ) extends ValueResult
case class FieldValue( pos: Position, heading: String, typ: Type, index: Int ) extends ValueResult
case class MarkedValue( pos: Position, heading: String, typ: Type, m: Mark ) extends ValueResult
case class BinaryValue( pos: Position, heading: String, typ: Type, left: ValueResult, operation: String, func: FunctionMap,
												right: ValueResult ) extends ValueResult
case class AggregateFunctionValue( pos: Position, heading: String, typ: Type, af: AggregateFunction, args: List[ValueResult] ) extends ValueResult {
	var func: AggregateFunctionInstance = _
	}
case class ScalarFunctionValue( pos: Position, heading: String, typ: Type, func: ScalarFunction, args: List[ValueResult] ) extends ValueResult
case class UnaryValue( pos: Position, heading: String, typ: Type, v: ValueResult, operation: String, func: FunctionMap ) extends ValueResult
case class LogicalValue( pos: Position, heading: String, typ: Type, logical: LogicalResult ) extends ValueResult
case class AliasValue( pos: Position, heading: String, typ: Type, apos: Position, v: ValueResult ) extends ValueResult