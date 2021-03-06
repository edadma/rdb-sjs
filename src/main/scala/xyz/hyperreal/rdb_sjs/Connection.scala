package xyz.hyperreal.rdb_sjs

import xyz.hyperreal.dal.BasicDAL.{compute, negate, relate}
import xyz.hyperreal.importer_sjs.{Importer, Table, Column => ImpColumn}

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.util.parsing.input.Position
import java.time.{Instant, ZonedDateTime}

class Connection {

  val baseRelations = new HashMap[Symbol, BaseRelation]
  val variables = new HashMap[String, Any]

  variables ++= Builtins.aggregateFunctions
  variables ++= Builtins.scalarFunctions
  variables ++= Builtins.constants

  def like(s: String, pattern: String, casesensitive: Boolean = true): Boolean = {
    var sp = 0
    var pp = 0
    val choices = new mutable.Stack[ChoicePoint]

    case class ChoicePoint(sp: Int, pp: Int)

    def move(): Unit = {
      sp += 1
      pp += 1
    }

    def choice: Boolean =
      if (choices nonEmpty) {
        val ChoicePoint(nsp, npp) = choices.pop()

        sp = nsp
        pp = npp
        true
      } else
        false

    while (sp < s.length || pp < pattern.length) {
      if (pp == pattern.length && !choice)
        return false
      else
        pattern(pp) match {
          case '%' =>
            if (pp == pattern.length - 1)
              return true

            if (sp < s.length - 1)
              choices push ChoicePoint(sp + 1, pp)

            pp += 1
          case '_' => move()
          case c =>
            if (c == '\\')
              pp += 1

            if (sp < s.length && ((casesensitive && s(sp) == pattern(pp)) || (!casesensitive && s(sp).toLower == pattern(
                  pp).toLower)))
              move()
            else if (!choice)
              return false
        }
    }

    true
  }

  def load(data: String, doubleSpaces: Boolean = false): Unit = {
    def types(t: String) =
      t match {
        case "currency" => DecimalType
        case "date"     => DateType
        case _          => Type.names(t)
      }

    val tables = Importer.importFromString(data, doubleSpaces)

    for (Table(name, header, data) <- tables) {
      val t =
        createTable(
          name,
          header map {
            case ImpColumn(cname, typ, Nil) =>
              BaseRelationColumn(name, cname, types(typ), None, false, false)
            case ImpColumn(cname, typ, List("pk")) =>
              BaseRelationColumn(name, cname, types(typ), Some(PrimaryKey), false, false)
            case ImpColumn(cname, typ, List("fk", tref, cref)) =>
              val trefsym = Symbol(tref)

              tables find (_.name == tref) match {
                case None =>
                  sys.error(s"unknown table: $tref")
                case Some(tab) =>
                  tab.header.indexWhere(_.name == cref) match {
                    case -1 => sys.error(s"unknown column: $cref")
                    case col =>
                      if (tab.header(col).args.contains("pk"))
                        BaseRelationColumn(name, cname, types(typ), Some(ForeignKey(trefsym, col)), false, false)
                      else
                        sys.error(s"target column must be a primary key: $cref")
                  }
              }
          }
        )

      for (row <- data)
        t.insertRow(row)
    }
  }

  def createTable(name: String, definition: Seq[BaseRelationColumn]) = {
    val sym = Symbol(name)

    if (baseRelations contains sym)
      sys.error(s"base relation '$name' already exists")
    else if (variables contains name)
      sys.error(s"variable relation '$name' already exists")

    val res = new BaseRelation(name, definition, baseRelations)

    baseRelations(sym) = res
    res
  }

  def executeRQLStatement(statement: String): StatementResult =
    executeStatement(RQLParser.parseStatement(statement))

  def executeSQLStatement(statement: String): StatementResult =
    executeStatement(SQLParser.parseStatement(statement))

  def executeSQLQuery(statement: String): Relation =
    executeStatement(SQLParser.parseStatement(statement))
      .asInstanceOf[RelationResult]
      .relation

  def executeStatement(ast: AST): StatementResult =
    ast match {
      case CreateBaseRelationStatement(table @ Ident(base), columns) =>
        if (baseRelations contains Symbol(base))
          problem(table.pos, "a base relation by that name already exists")
        else if (variables contains base)
          problem(table.pos, "a variable relation by that name already exists")

        var hset = Set[String]()
        var pk = false

        for (ColumnDef(id @ Ident(n), _, _, pkpos, _, _, _) <- columns)
          if (hset(n))
            problem(id.pos, "duplicate column")
          else {
            hset += n

            if (pkpos isDefined)
              if (pk)
                problem(pkpos.get, "a second primary key is not allowed")
              else
                pk = true
          }

//        if (!pk)  // todo: check this
//          problem(table.pos, "one of the columns must be declared to be the primary key")

        val header =
          columns map {
            case ColumnDef(Ident(n), tp, typ, pkpos, fk, u, a) =>
              if (a && !typ.isInstanceOf[Auto])
                problem(tp, "a column of this type cannot be declared auto")

              val constraint =
                if (pkpos ne null)
                  Some(PrimaryKey)
                else if (fk isDefined) {
                  val sym = Symbol(fk.get._1.name)

                  baseRelations get sym match {
                    case None => problem(fk.get._1.pos, s"unknown table: $sym")
                    case Some(t) =>
                      t.metadata.columnMap get fk.get._2.get.name match {
                        case None => problem(fk.get._2.get.pos, s"unknown column: ${fk.get._2.get.name}")
                        case Some(c) =>
                          t.metadata.baseRelationHeader(c).constraint match {
                            case Some(PrimaryKey | Unique) =>
                              Some(ForeignKey(sym, c))
                            case _ =>
                              problem(fk.get._2.get.pos, "target column must be a primary key or unique")
                          }
                      }
                  }
                } else
                  None

              BaseRelationColumn(base, n, typ, constraint, u, a)
          }

        createTable(base, header)
        CreateResult(base)
      case DropTableStatement(table @ Ident(name)) =>
        if (baseRelations contains Symbol(name))
          baseRelations remove Symbol(name)
        else
          problem(table.pos, "no base relation by that name exists")

        DropResult(name)
      case AssignRelationStatement(table @ Ident(name), relation) =>
        if (baseRelations contains Symbol(name))
          problem(table.pos, "a base relation by that name already exists")

        val rel = evalRelation(relation, Nil)
        val res = AssignResult(name, variables contains name, rel size)

        variables(name) = rel
        res
      case InsertTupleStatement(base, tuple) =>
        baseRelations get Symbol(base.name) match {
          case None => problem(base.pos, "unknown base relation")
          case Some(b) =>
            val types = b.metadata.baseRelationHeader map (_.typ) toArray
            val t = evalTuple(types, tuple)

            (b.metadata.baseRelationHeader zip t) zip tuple.t find {
              case ((c, v), _) =>
                (c.unmarkable || c.constraint.contains(PrimaryKey)) && v
                  .isInstanceOf[Mark]
            } match {
              case None =>
              case Some(((BaseRelationColumn(table, column, _, _, _, _), _), e)) =>
                problem(e.pos, s"column '$column' of table '$table' is unmarkable")
            }

            b.insertRow(t) match {
              case None    => InsertResult(Nil, 0)
              case Some(a) => InsertResult(List(a), 1)
            }
        }
      case InsertTupleseqStatement(base, columns, tupleseq) =>
        baseRelations get Symbol(base.name) match {
          case None => problem(base.pos, "unknown base relation")
          case Some(b) =>
            val types =
              columns map (n => {
                b.metadata.columnMap get n.name match {
                  case None      => problem(n.pos, s"column '$n' does not exist")
                  case Some(idx) => b.metadata.header(idx).typ
                }
              }) toArray
            val seq = evalTupleseq(types, tupleseq, Nil)
            val (l, c) = b.insertTupleseq(columns, seq)

            InsertResult(l, c)
        }
      case InsertRelationStatement(table @ Ident(name), relation) =>
        val src = evalRelation(relation, Nil)

        baseRelations get Symbol(name) match {
          case None => problem(table.pos, "unknown base relation")
          case Some(b) =>
            if (!src.metadata.attributes.subsetOf(b.metadata.attributes))
              problem(relation.pos, "attributes must be a subset of base")

            val (l, c) = b.insertRelation(src)

            InsertResult(l, c)
        }
      case DeleteStatement(base, condition) =>
        baseRelations get Symbol(base.name) match {
          case None => problem(base.pos, "unknown base relation")
          case Some(b) =>
            val afuse = AFUseOrField(NoFieldOrAFUsed)
            val cond = evalLogical(afuse, List(b.metadata), condition)

            aggregateCondition(b, cond, afuse.state)
            DeleteResult(b.delete(this, cond))
        }
      case UpdateStatement(base, condition, updates) =>
        baseRelations get Symbol(base.name) match {
          case None => problem(base.pos, "unknown base relation")
          case Some(b) =>
            val afuse = AFUseOrField(NoFieldOrAFUsed)
            val cond = evalLogical(afuse, List(b.metadata), condition)
            val upds =
              for ((col, expr) <- updates)
                yield {
                  b.metadata.columnMap get col.name match {
                    case None => problem(col.pos, "unknown column")
                    case Some(ind) =>
                      (ind, evalExpression(AFUseNotAllowed, List(b.metadata), expr))
                  }
                }

            aggregateCondition(b, cond, afuse.state)
            UpdateResult(b.update(this, cond, upds))
        }
      case r: RelationExpression => RelationResult(evalRelation(r, Nil))
      case t: TupleseqExpression => TupleseqResult(evalTupleseq(null, t, Nil))
    }

  def evalTupleseq(types: Array[Type], tupleseq: TupleseqExpression, context: List[Metadata]): Tupleseq = {
    tupleseq match {
      case TupleseqLit(data) =>
        val types1 =
          if (types eq null)
            new Array[Type](data.length)
          else
            types

        new ConcreteTupleseq(types1.toIndexedSeq, evalTupleList(types1, data))
    }
  }

  def evalTuple(types: Array[Type], tuple: TupleExpression) = {
    val row = new ArrayBuffer[Any]
    val r = tuple.t

    if (r.length < types.length)
      problem(tuple.pos, "not enough values")

    for ((v, i) <- r zipWithIndex) {
      if (i == types.length)
        problem(v.pos, "too many values")

      var x = evalValue(null, evalExpression(AFUseNotAllowed, null, v))

      x match {
        case _: Mark =>
        case a: java.lang.Integer =>
          types(i) match {
            case null        => types(i) = IntegerType
            case IntegerType =>
            case FloatType =>
              x = a.toDouble.asInstanceOf[Number]
            case BigintType =>
              x = a.toLong.asInstanceOf[Number]
            case typ => problem(v.pos, s"expected $typ, not integer")
          }
        case s: String =>
          types(i) match {
            case null          => types(i) = TextType
            case TextType      =>
            case TimestampType => x = Instant.parse(s)
            case typ           => problem(v.pos, s"expected $typ, not string")
          }
        case TRUE  => x = true
        case FALSE => x = false
      }

      row += x
    }

    row toVector
  }

  def evalTupleList(types: Array[Type], data: List[TupleExpression]): List[Tuple] = {
    val body = new ArrayBuffer[Tuple]

    for (t @ TupleExpression(r) <- data)
      body += evalTuple(types, t)

    body.toList
  }

  def evalRelation(ast: RelationExpression, context: List[Metadata]): Relation = {
    ast match {
      case LimitOffsetRelationExpression(relation, limit, offset) =>
        new LimitOffsetRelation(evalRelation(relation, context), limit, offset)
      case SortedRelationExpression(relation, exprs) =>
        val rel = evalRelation(relation, context)
        val afuse = AFUseOrField(NoFieldOrAFUsed)
        val es =
          exprs map {
            case (e, d, n) =>
              (evalExpression(afuse, rel.metadata :: context, e), d, n)
          }

        rel.sort(this, es)
      case GroupingRelationExpression(relation, discriminator, filter, cpos, columns) =>
        val rel = evalRelation(relation, context) //todo (nested): don't know if this is right
        val disafuse = AFUseOrField(NoFieldOrAFUsed)
        val dis = discriminator map (evalExpression(disafuse, rel.metadata :: context, _)) toVector
        val dismetadata = new Metadata(dis map (c => SimpleColumn(c.table, c.heading, c.typ))) :: context
        val filtafuse = AFUseOrField(NoFieldOrAFUsed)
        val filt = filter map (evalLogical(filtafuse, dismetadata, rel.metadata, _))
        val colafuse = AFUseOrField(NoFieldOrAFUsed)
        val cols = columns map (evalExpression(colafuse, dismetadata, rel.metadata, _)) toVector

        if (cols isEmpty)
          problem(cpos, "at least one expression must be given for grouping")

        new GroupingRelation(this, rel, disafuse.state, dis, filtafuse.state, filt, colafuse.state, cols)
      case ProjectionRelationExpression(relation, columns) =>
        val rel = evalRelation(relation, context)
        val afuse = AFUseOrField(NoFieldOrAFUsed)

        new ProjectionRelation(this,
                               rel,
                               columns map (evalExpression(afuse, rel.metadata :: context, _)) toVector,
                               afuse.state)
      case InnerJoinRelationExpression(left, condition, right) =>
        val lrel = evalRelation(left, context)
        val rrel = evalRelation(right, context)
        val metadata = new Metadata(lrel.metadata.header ++ rrel.metadata.header)

        new InnerJoinRelation(this, metadata, lrel, evalLogical(AFUseNotAllowed, metadata :: context, condition), rrel)
      case LeftJoinRelationExpression(left, condition, right) =>
        val lrel = evalRelation(left, context)
        val rrel = evalRelation(right, context)
        val metadata = new Metadata(lrel.metadata.header ++ rrel.metadata.header)

        new LeftJoinRelation(this, metadata, lrel, evalLogical(AFUseNotAllowed, metadata :: context, condition), rrel)
      case SelectionRelationExpression(relation, condition) =>
        val rel = evalRelation(relation, context)
        val afuse = AFUseOrField(NoFieldOrAFUsed)
        val cond = evalLogical(afuse, rel.metadata :: context, condition)

        new SelectionRelation(this, rel, cond, afuse.state)
      case AliasVariableExpression(rel, alias) =>
        val r = evalRelation(rel, context)

        if (r.metadata.tableSet.size > 1)
          problem(alias.pos, "the relation being aliased is a product of more than one table")

        new AliasRelation(r, alias.name)
      case RelationVariableExpression(v @ Ident(n)) =>
        baseRelations get Symbol(n) match {
          case None =>
            variables get n match {
              case Some(r: Relation) => r
              case _                 => problem(v.pos, "unknown base or variable relation")
            }

          case Some(r) => r
        }
      case ListRelationExpression(columns, data) =>
        var hset = Set[String]()

        for (ColumnSpec(c @ Ident(n), _, _) <- columns)
          if (hset(n))
            problem(c.pos, "duplicate column")
          else {
            hset += n
          }

        val types: Array[Type] =
          columns map {
            case ColumnSpec(_, _, None) => null
            case ColumnSpec(_, p, Some(t)) =>
              Type.names.getOrElse(t, problem(p, s"unrecognized type name '$t'"))
          } toArray
        val body =
          if (data isEmpty)
            types indexOf null match {
              case -1 => Nil
              case ind =>
                problem(columns(ind).typepos, "missing type specification")
            } else
            evalTupleList(types, data)

        val tab = anonymous
        val header =
          columns zip types map {
            case (ColumnSpec(_, p, _), null) =>
              problem(p, "missing type specification in relation with missing values")
            case (ColumnSpec(Ident(n), _, _), t) => SimpleColumn(tab, n, t)
          }

        new ConcreteRelation(header toIndexedSeq, body)
    }
  }

  private val oporder =
    List(List("b^"), List("u-"), List("b*", "b/"), List("b+", "b-"))

  private def brackets(p: ValueExpression, c: ValueExpression, right: Boolean): Boolean = {
    def s(e: ValueExpression) =
      e match {
        case BinaryValueExpression(_, _, operation, _) =>
          Some(s"b$operation")
        case UnaryValueExpression(_, operation, _) => Some(s"u$operation")
        case _                                     => None
      }

    val p1 = s(p)
    val c1 = s(c)

    if (c1 isEmpty)
      return false
    else
      for (l <- oporder)
        if (l contains p1.get)
          if (l contains c1.get)
            if (right)
              return true
            else
              return false
          else
            return true
        else if (l contains c1.get)
          return false

    sys.error(s"not found: $p1, $c1")
  }

  def evalExpression(afuse: AggregateFunctionUse, metadata: List[Metadata], ast: ValueExpression): ValueResult =
    evalExpression(afuse, metadata, if (metadata eq null) null else metadata.head, ast) //todo (nested): aggregates can't see outer scopes

  def evalExpression(afuse: AggregateFunctionUse,
                     fmetadata: List[Metadata],
                     ametadata: Metadata,
                     ast: ValueExpression): ValueResult =
    ast match {
      case AliasValueExpression(expr, alias) =>
        val v = evalExpression(afuse, fmetadata, ametadata, expr)

        AliasValue(v.pos, v.table, alias.name, v.typ, alias.pos, v)
      case StarExpression => LiteralValue(ast.pos, null, "*", IntegerType, 0)
      case FloatLit(n) =>
        LiteralValue(ast.pos, null, n, FloatType, java.lang.Double.valueOf(n))
      case IntegerLit(n) =>
        LiteralValue(ast.pos, null, n, IntegerType, Integer.valueOf(n))
      case StringLit(s) => LiteralValue(ast.pos, null, s"'$s'", TextType, unescape(s))
      case MarkLit(m)   => MarkedValue(ast.pos, null, m.toString, null, m)
      case ValueVariableExpression(n) =>
        search(fmetadata)(_.columnMap get n.name) match {
          case None =>
            variables get n.name match {
              case None =>
                problem(n.pos, "no such column or variable")
              case Some(v) =>
                VariableValue(n.pos, null, n.name, Type.fromValue(v).orNull, v) //todo: handle function types correctly
            }
          case Some((ind, depth)) =>
            afuse match {
              case use @ AFUseOrField(OnlyAFUsed) => use.state = FieldAndAFUsed
              case use @ AFUseOrField(NoFieldOrAFUsed) =>
                use.state = OnlyFieldUsed
              case AFUseNotAllowed | AFUseOrField(OnlyFieldUsed | FieldAndAFUsed) =>
            }

            FieldValue(ast.pos,
                       fmetadata(depth).header(ind).table,
                       n.name,
                       fmetadata(depth).header(ind).typ,
                       ind,
                       depth)
        }
      case ValueColumnExpression(t, c) =>
        if (!fmetadata.exists(_.tableSet(t.name)))
          problem(t.pos, s"unknown table: ${t.name}")
        else
          search(fmetadata)(_.tableColumnMap get (t.name, c.name)) match {
            case None => problem(c.pos, s"no such column: ${(t.name, c.name)}")
            case Some((ind, depth)) =>
              afuse match {
                case use @ AFUseOrField(OnlyAFUsed) =>
                  use.state = FieldAndAFUsed
                case use @ AFUseOrField(NoFieldOrAFUsed) =>
                  use.state = OnlyFieldUsed
                case AFUseNotAllowed | AFUseOrField(OnlyFieldUsed | FieldAndAFUsed) =>
              }

              FieldValue(ast.pos, t.name, c.name, fmetadata(depth).header(ind).typ, ind, depth)
          }
      case e @ BinaryValueExpression(left, oppos, operation, right) =>
        val l = evalExpression(afuse, fmetadata, ametadata, left)
        val r = evalExpression(afuse, fmetadata, ametadata, right)

        (l, r) match {
          case (LiteralValue(p, _, _, _, x), LiteralValue(_, _, _, _, y)) =>
            val res = binaryOperation(x, oppos, operation, y)

            LiteralValue(p, null, res.toString, Type.fromValue(res).get, res)
          case _ =>
            val space = if (Set("+", "-")(operation)) " " else ""
            val lh =
              if (brackets(e, left, false))
                s"(${l.heading})"
              else
                l.heading
            val rh =
              if (brackets(e, right, true))
                s"(${r.heading})"
              else
                r.heading

            BinaryValue(oppos, null, s"$lh$space$operation$space$rh", l.typ, l, operation, r) //todo: handle type promotion correctly
        }
      case expr @ CaseValueExpression(whens, els) =>
        val ws = whens map {
          case (c, r) => (evalLogical(afuse, fmetadata, ametadata, c), evalExpression(afuse, fmetadata, ametadata, r))
        }
        val optels = els map (evalExpression(afuse, fmetadata, ametadata, _))
        val buf = new StringBuilder

        buf ++= "CASE"

        for ((c, r) <- ws) {
          buf ++= " WHEN "
          buf ++= c.heading
          buf ++= " THEN "
          buf ++= r.heading
        }

        optels foreach (e => {
          buf ++= " ELSE "
          buf ++= e.heading
        })

        buf ++= " END"
        CaseValue(expr.pos, null, buf.toString, ws.head._2.typ, ws, optels)
      case UnaryValueExpression(oppos, operation, expr) =>
        val e = evalExpression(afuse, fmetadata, ametadata, expr)

        e match {
          case LiteralValue(p, _, _, op, x) =>
            val res = unaryOperation(oppos, operation, x)

            LiteralValue(p, null, res.toString, Type.fromValue(res).get, res)
          case _ =>
            UnaryValue(oppos, null, s"$operation${e.heading}", e.typ, e, operation) //todo: handle type promotion correctly
        }
      case e @ ApplicativeValueExpression(func, args) =>
        val f = evalExpression(afuse, fmetadata, ametadata, func)

        f match {
          case VariableValue(_, _, _, _, af: AggregateFunction) =>
            afuse match {
              case AFUseNotAllowed =>
                problem(e.pos, "aggregate function not allowed here")
              case use @ AFUseOrField(OnlyFieldUsed) =>
                use.state = FieldAndAFUsed
              case use @ AFUseOrField(NoFieldOrAFUsed)       => use.state = OnlyAFUsed
              case AFUseOrField(OnlyAFUsed | FieldAndAFUsed) =>
            }

            val a = args map (evalExpression(AFUseNotAllowed, List(ametadata), null, _))
            val heading =
              if (a == Nil)
                s"${f.heading}()"
              else
                s"${f.heading}(${a map (_.heading) mkString ", "})"

            AggregateFunctionValue(e.pos, null, heading, af.typ(a map (_.typ)), af, a)
          case VariableValue(_, _, _, _, sf: ScalarFunction) =>
            val a = args map (evalExpression(afuse, fmetadata, ametadata, _))
            val heading =
              if (a == Nil)
                s"${f.heading}()"
              else
                s"${f.heading}(${a map (_.heading) mkString ", "})"

            ScalarFunctionValue(e.pos, null, heading, sf.typ(a map (_.typ)), sf, a)
          case _ => problem(e.pos, s"'$f' is not a function")
        }
      case e @ LogicalValueExpression(logical) =>
        val log = evalLogical(afuse, fmetadata, ametadata, logical) //todo: this might not be right if there are aggregates in a boolean expression

        LogicalValue(e.pos, null, log.heading, LogicalType, log)
    }

  def aggregateCondition(tuples: Iterable[Tuple], condition: LogicalResult, afuse: AggregateFunctionUseState) =
    if (afuse == OnlyAFUsed || afuse == FieldAndAFUsed) {
      initAggregation(condition)

      for (t <- tuples.iterator)
        aggregate(t, condition)
    }

  def aggregateColumns(tuples: Iterable[Tuple], columns: Vector[ValueResult], afuse: AggregateFunctionUseState) =
    if (afuse == OnlyAFUsed || afuse == FieldAndAFUsed) {
      for (c <- columns)
        initAggregation(c)

      for (t <- tuples.iterator; c <- columns)
        aggregate(t, c)
    }

  def aggregate(row: Tuple, result: ValueResult): Unit =
    result match {
      case BinaryValue(_, _, _, _, l, _, r) =>
        aggregate(row, l)
        aggregate(row, r)
      case UnaryValue(_, _, _, _, v, _) =>
        aggregate(row, v)
      case a @ AggregateFunctionValue(_, _, _, _, _, args) =>
        a.func.next(args map (evalValue(List(row) /*todo (nested): this may not be right*/, _)))
      case ScalarFunctionValue(_, _, _, _, _, args) =>
        for (a <- args)
          aggregate(row, a)
      case LogicalValue(_, _, _, _, l) =>
        aggregate(row, l)
      case _ =>
    }

  def aggregate(row: Tuple, result: LogicalResult): Unit =
    result match {
      case _: LiteralLogical =>
      case ComparisonLogical(_, left, _, _, right) =>
        aggregate(row, left)
        aggregate(row, right)
    }

  def initAggregation(result: ValueResult): Unit =
    result match {
      case BinaryValue(_, _, _, _, l, _, r) =>
        initAggregation(l)
        initAggregation(r)
      case UnaryValue(_, _, _, _, v, _) =>
        initAggregation(v)
      case a @ AggregateFunctionValue(_, _, _, _, af, _) =>
        a.func = af.instance
      case ScalarFunctionValue(_, _, _, _, _, args) =>
        for (a <- args)
          initAggregation(a)
      case LogicalValue(_, _, _, _, l) =>
        initAggregation(l)
      case _ =>
    }

  def initAggregation(result: LogicalResult): Unit =
    result match {
      case _: LiteralLogical =>
      case ComparisonLogical(_, left, _, _, right) =>
        initAggregation(left)
        initAggregation(right)
    }

  def evalVector(row: List[Tuple], vector: Vector[ValueResult]) =
    vector map (evalValue(row, _))

  def binaryOperation(lv: Any, pos: Position, op: String, rv: Any) =
    (lv, rv) match {
      case (I, _) | (_, I) => I
      case (A, _) | (_, A) => A
      case _ =>
        try {
          (lv, op, rv) match {
            case (_: String, "+", _) | (_, "+", _: String) =>
              lv.toString ++ rv.toString
            case (l: Number, _, r: Number) => compute(l, op, r)
            case _                         => problem(pos, "invalid operation")
          }
        } catch {
          case _: Exception =>
            problem(pos, "error performing binary operation")
        }
    }

  def unaryOperation(pos: Position, op: String, v: Any) =
    try {
      (op, v) match {
        case ("-", v: Number) => negate(v)
        case _                => problem(pos, "invalid operation")
      }
    } catch {
      case _: Exception => problem(pos, "error performing unary operation")
    }

  def evalValue(row: List[Tuple], result: ValueResult): Any =
    result match {
      case AliasValue(_, _, _, _, _, v)         => evalValue(row, v)
      case LiteralValue(_, _, _, _, v)          => v
      case VariableValue(_, _, _, _, v)         => v
      case FieldValue(_, _, _, _, index, depth) => row(depth)(index)
      case MarkedValue(_, _, _, _, m)           => m
      case BinaryValue(p, _, _, _, l, op, r) =>
        val lv = evalValue(row, l)
        val rv = evalValue(row, r)

        binaryOperation(lv, p, op, rv)
      case CaseValue(_, _, _, _, whens, els) =>
        @scala.annotation.tailrec
        def casevalue(whens: List[(LogicalResult, ValueResult)]): Any =
          whens match {
            case Nil =>
              els.fold(null.asInstanceOf[Any])(evalValue(row, _))
            case (c, r) :: tail =>
              if (evalCondition(row, c) == TRUE)
                evalValue(row, r)
              else
                casevalue(tail)
          }

        casevalue(whens)
      case UnaryValue(p, _, _, _, v, op) =>
        unaryOperation(p, op, evalValue(row, v))
      case ScalarFunctionValue(_, _, _, _, func, args) =>
        func(args map (evalValue(row, _))).asInstanceOf[Any]
      case a: AggregateFunctionValue   => a.func.result.asInstanceOf[Any]
      case LogicalValue(_, _, _, _, l) => evalCondition(row, l)
    }

  def comparison(l: Number, comp: String, r: Number): Boolean = relate(l, comp, r)

  def evalCondition(context: List[Tuple], cond: LogicalResult): Logical =
    cond match {
      case ExistsLogical(_, relation) =>
        relation match {
          case r: Relation =>
            Logical.fromBoolean(r.iterator(context).nonEmpty)
          case _ => sys.error("fix this")
        }
      case InLogical(_, expr, negated, list) =>
        Logical.fromBooleanMaybeNegated(list.iterator map (evalValue(context, _)) contains evalValue(context, expr),
                                        negated)
      case InQueryLogical(heading, expr, negated, relation) =>
        relation match {
          case r: Relation =>
            if (r.metadata.header.length != 1)
              sys.error(s"sub-query should yield one column result: $heading")

            Logical.fromBooleanMaybeNegated(r.iterator(context) map (_.head) contains evalValue(context, expr), negated)
          case _ => sys.error("fix this")
        }
      case LiteralLogical(_, lit) => lit
      case LikeLogical(_, pos, left, right, negated, casesensitive) =>
        val lv = evalValue(context, left)
        val rv = evalValue(context, right)

        (lv, rv) match {
          case (_: String, _) | (_, _: String) =>
            Logical.fromBooleanMaybeNegated(like(lv.toString, rv.toString, casesensitive), negated)
          case _ => problem(pos, "invalid 'like' comparison")
        }
      case IsNullLogical(_, expr, negated) =>
        Logical.fromBooleanMaybeNegated(evalValue(context, expr) == null, negated)
      case ComparisonLogical(_, left, pos, comp, right) =>
        val lv = evalValue(context, left)
        val rv = evalValue(context, right)

        (lv, rv) match {
          case (`I`, _) | (_, `I`) => MAYBE_I
          case (`A`, _) | (_, `A`) => MAYBE_A
          case (_: String, _) | (_, _: String) =>
            Logical.fromBoolean(comparison(lv.toString compareTo rv.toString, comp, 0))
          case (l: Number, r: Number) =>
            Logical.fromBoolean(comparison(l, comp, r))
          case (null, _) | (_, null) => FALSE //todo: nulls should be marks and should be handled correctly
          case _                     => problem(pos, "invalid comparison")
        }
      case AndLogical(_, l, r) =>
        evalCondition(context, l) && evalCondition(context, r)
      case OrLogical(_, l, r) =>
        evalCondition(context, l) || evalCondition(context, r)
      case NotLogical(_, l) =>
        !evalCondition(context, l)
    }

  def evalLogical(afuse: AggregateFunctionUse, metadata: List[Metadata], ast: LogicalExpression): LogicalResult =
    evalLogical(afuse, metadata, metadata.head, ast) //todo (nested): aggregates can't see outer scopers

  def evalLogical(afuse: AggregateFunctionUse,
                  fmetadata: List[Metadata],
                  ametadata: Metadata,
                  ast: LogicalExpression): LogicalResult = {
    ast match {
      case ExistsLogicalExpression(tuples) =>
        val rel =
          tuples match {
            case r: RelationExpression => evalRelation(r, fmetadata)
            case s: TupleseqExpression => evalTupleseq(null, s, fmetadata)
          }

        ExistsLogical(s"EXISTS ($rel)", rel)
      case InQueryLogicalExpression(expr, negated, tuples) =>
        val e = evalExpression(afuse, fmetadata, ametadata, expr)
        val rel =
          tuples match {
            case r: RelationExpression => evalRelation(r, fmetadata)
            case s: TupleseqExpression => evalTupleseq(null, s, fmetadata)
          }

        InQueryLogical(s"${e.heading} ${if (negated) "NOT " else ""}IN ($rel)", e, negated, rel)
      case InLogicalExpression(expr, negated, list) =>
        val e = evalExpression(afuse, fmetadata, ametadata, expr)
        val l = list map (evalExpression(afuse, fmetadata, ametadata, _))

        InLogical(s"${e.heading} ${if (negated) "NOT " else ""}IN (${l map (_.heading) mkString ", "})", e, negated, l)
      case LiteralLogicalExpression(lit) => LiteralLogical(lit.toString, lit)
      case LikeLogicalExpression(left, right, lpos, negated, casesensitive) =>
        val l = evalExpression(afuse, fmetadata, ametadata, left)
        val r = evalExpression(afuse, fmetadata, ametadata, right)

        LikeLogical(s"${l.heading} ${if (negated) "NOT LIKE" else "LIKE"} ${r.heading}",
                    lpos,
                    l,
                    r,
                    negated,
                    casesensitive)
      case IsNullLogicalExpression(expr, negated) =>
        val e = evalExpression(afuse, fmetadata, ametadata, expr)

        IsNullLogical(s"${e.heading} IS ${if (negated) "NOT " else ""}NULL", e, negated)
      case ComparisonLogicalExpression(left, List((pos, comp, right))) =>
        val l = evalExpression(afuse, fmetadata, ametadata, left)
        val r = evalExpression(afuse, fmetadata, ametadata, right)

        ComparisonLogical(s"${l.heading} $comp ${r.heading}", l, pos, comp, r)
      case ComparisonLogicalExpression(left, List((posm, compm, middle), (posr, compr, right))) =>
        val l = evalExpression(afuse, fmetadata, ametadata, left)
        val m = evalExpression(afuse, fmetadata, ametadata, middle)
        val r = evalExpression(afuse, fmetadata, ametadata, right)
        val lc =
          ComparisonLogical(s"${l.heading} $compm ${m.heading}", l, posm, compm, m)
        val rc =
          ComparisonLogical(s"${m.heading} $compr ${r.heading}", m, posr, compr, r)

        AndLogical(s"${lc.heading} AND ${rc.heading}", lc, rc)
      case AndLogicalExpression(left, right) =>
        val l = evalLogical(afuse, fmetadata, ametadata, left)
        val r = evalLogical(afuse, fmetadata, ametadata, right)

        AndLogical(s"${l.heading} AND ${r.heading}", l, r)
      case OrLogicalExpression(left, right) =>
        val l = evalLogical(afuse, fmetadata, ametadata, left)
        val r = evalLogical(afuse, fmetadata, ametadata, right)

        OrLogical(s"${l.heading} OR ${r.heading}", l, r)
      case NotLogicalExpression(expr) =>
        val l = evalLogical(afuse, fmetadata, ametadata, expr)

        NotLogical(s"NOT ${l.heading}", l)
    }
  }
}

trait AggregateFunctionUseState
case object NoFieldOrAFUsed extends AggregateFunctionUseState
case object OnlyFieldUsed extends AggregateFunctionUseState
case object OnlyAFUsed extends AggregateFunctionUseState
case object FieldAndAFUsed extends AggregateFunctionUseState

trait AggregateFunctionUse
case object AFUseNotAllowed extends AggregateFunctionUse
case class AFUseOrField(var state: AggregateFunctionUseState) extends AggregateFunctionUse

trait StatementResult
case class CreateResult(name: String) extends StatementResult
case class DropResult(name: String) extends StatementResult
case class AssignResult(name: String, update: Boolean, count: Int) extends StatementResult
case class InsertResult(auto: List[Map[String, Any]], count: Int) extends StatementResult
case class DeleteResult(count: Int) extends StatementResult
case class UpdateResult(count: Int) extends StatementResult
case class RelationResult(relation: Relation) extends StatementResult
case class TupleseqResult(tupleseq: Tupleseq) extends StatementResult
