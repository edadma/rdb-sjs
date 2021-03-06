package xyz.hyperreal.rdb_sjs


class ConcreteRelation( header: IndexedSeq[Column], body: List[Tuple] ) extends AbstractRelation {

	val metadata = new Metadata( header )

	def iterator( context: List[Tuple] ) = body.iterator

	override def toString = s"concreteRelation( $header )"

}
