package effectful.examples.pure.dao.sql


import java.time.Instant

import effectful._
import effectful.examples.effects.sql._
import effectful.examples.pure.dao.DocDao
import effectful.examples.pure.dao.DocDao.RecordMetadata
import effectful.examples.pure.dao.query.Query
import SqlDriver._
import effectful.aspects.{Exceptions, Par}
import effectful.cats.Monad

object SqlDocDao {
  case class FieldColumnMapping(
    fieldName: String,
    columnIndex: Int,
    columnName: String
  )
  // todo: simple DDL generator
  case class RecordMapping[ID,A](
    tableName: String,
    recordFields: Seq[FieldColumnMapping],
    idField: FieldColumnMapping
  ) {
    def recordFieldCount = recordFields.size
    val recordFieldsOrdered = recordFields.sortBy(_.columnIndex)
    val allFields = idField +: recordFields
    val allFieldsOrdered = allFields.sortBy(_.columnIndex)
  }

  implicit class StringPML(val self: String) extends AnyVal {
    def esc = s"`$self`"
  }
  implicit class StringContextPML(val self: StringContext) extends AnyVal {
    def esc(args: String*) = self.s(args.map(_.esc):_*)
  }
}

class SqlDocDao[ID,A,E[_]](
  sql: SqlDriver[E],
  recordMapping: SqlDocDao.RecordMapping[ID,A],
  metadataMapping: SqlDocDao.RecordMapping[ID,RecordMetadata]
)(implicit
  E:Monad[E],
  P:Par[E],
  X:Exceptions[E],
  recordFormat: SqlRecordFormat[ID,A],
  metadataFormat: SqlRecordFormat[ID,RecordMetadata]
) extends DocDao[ID,A,E] {
  import P._
  import Monad.ops._
  
  import SqlDocDao._
  import recordMapping._

  val metadataTableName = metadataMapping.tableName
  val metadataTableSameAsRecord = tableName == metadataTableName

//  val recordFieldNames = recordFields.map(_.fieldName)
  val recordColNames = recordFields.map(_.columnName)

  val meta_recordFieldNames =
    metadataMapping.recordFields.map(_.fieldName)

  val recordFieldNameToColName =
    recordFields.map(m => (m.fieldName,m.columnName)).toMap
  val meta_recordFieldNameToColName =
    metadataMapping.recordFields.map(m => (m.fieldName,m.columnName)).toMap

  val idColName = idField.columnName

  val sqlMaybeJoinMetadata =
    if(metadataTableSameAsRecord) {
      ""
    }  else {
      esc"LEFT JOIN $metadataTableName ON $tableName.$idColName=$metadataTableName.$idColName"
    }

//  val allFieldNames =
//    Seq(idFieldName) ++
//    dataFieldNames.filterNot(_ == idFieldName) ++
//    meta_dataFieldNames

  // both record & metadata
  val selectAllRecordCols = {
    recordFieldsOrdered.map(f => esc"$tableName.${f.columnName}") ++
    // leave out metadata table id column
    metadataMapping.recordFieldsOrdered.map(f => esc"$metadataTableName.${f.columnName}")
  }.mkString(",")

  val qSelectFullRecord =
    s"SELECT ${tableName.esc}.${idColName.esc},$selectAllRecordCols FROM ${tableName.esc} $sqlMaybeJoinMetadata"

  val removedColName = meta_recordFieldNameToColName("removed")
  val lastUpdatedColName = meta_recordFieldNameToColName("lastUpdated")

  def parseFullRecord : SqlRow => (ID,A,RecordMetadata) = { row =>
    val idCol = row.head
    val (recordRow, metadataRow) = row.tail.splitAt(recordFieldCount)
    (recordFormat.fromSqlVal(idCol), recordFormat.fromSqlRow(recordRow), metadataFormat.fromSqlRow(metadataRow))
  }

  def prepFindById(implicit context: SqlDriver.Context): E[ID => E[Option[(ID,A,RecordMetadata)]]]  =
      sql.prepare(
        s"$qSelectFullRecord WHERE ${idColName.esc}=?"
      ).map { ps =>
        { (id:ID) =>
            sql.iteratePreparedQuery(ps)(IndexedSeq(recordFormat.toSqlVal(id)))
              .map(parseFullRecord)
              .headOption()
        }
      }

  override def findById(id: ID): E[Option[(ID, A, RecordMetadata)]] =
    sql.autoCommit { implicit autoCommit =>
      prepFindById.flatMap(_(id))
    }

  // todo: translate Query[A] to SQL where text
  def queryToSqlWhere(query: Query[A]) : String = ???

  override def find(query: Query[A]): E[Seq[(ID, A, RecordMetadata)]] =
    sql.autoCommit { implicit autoCommit =>
      sql.iterateQuery(s"$qSelectFullRecord WHERE ${queryToSqlWhere(query)}")
        .map(parseFullRecord)
        .collect[IndexedSeq]()
        .widen
    }

  def prepFindAll(implicit context: Context) : E[() => E[IndexedSeq[(ID,A,RecordMetadata)]]] =
    sql.prepare(qSelectFullRecord).map { ps =>
      { () =>
        sql.iteratePreparedQuery(ps)()
          .map(parseFullRecord)
          .collect[IndexedSeq]
      }
    }

  override def findAll(start: Int, batchSize: Int): E[Seq[(ID, A, RecordMetadata)]] =
    sql.autoCommit { implicit autoCommit =>
      prepFindAll.flatMap(_()).widen
    }

  def prepExists(implicit context: Context) : E[ID => E[Boolean]] =
    sql.prepare(
      s"SELECT 1 FROM ${tableName.esc} WHERE ${idColName.esc}=?"
    ).map { ps =>
      { (id:ID) =>
        for {
          cursor <- sql.executePreparedQuery(ps)(IndexedSeq(recordFormat.toSqlVal(id)))
        } yield cursor.nonEmpty
      }
    }

  override def exists(id: ID): E[Boolean] =
    sql.autoCommit { implicit autoCommit =>
      prepExists.flatMap(_(id))
    }

  override def batchExists(ids: Traversable[ID]): E[Set[ID]] =
    sql.autoCommit { implicit autoCommit =>
      sql.iterateQuery(
        s"SELECT ${idColName.esc} FROM ${tableName.esc} WHERE ${idColName.esc} IN (${ids.map(id => recordFormat.toSqlVal(id).printSQL).mkString(",")}) "
      )
        .map(row => recordFormat.fromSqlVal(row(0)))
        .collect[Set]
    }


  def prepMarkRemoved(implicit context: Context) : E[ID => E[Boolean]] =
    sql.prepare(
      s"UPDATE ${metadataTableName.esc} SET ${removedColName.esc}=? WHERE ${idColName.esc}=?"
    ).map { ps =>

      { (id:ID) =>
        sql.executePreparedUpdate(ps)(
          IndexedSeq(SqlVal.TIMESTAMP(Instant.now()),recordFormat.toSqlVal(id))
        ).map(_ == 1)
      }
    }

  override def remove(id: ID): E[Boolean] =
    sql.autoCommit { implicit autoCommit =>
      prepMarkRemoved.flatMap(_(id))
    }

  override def batchRemove(ids: Traversable[ID]): E[Int] = {
    sql.inTransaction { implicit transaction =>
      for {
        _remove <- prepMarkRemoved
        updateCounts <- ids.map(_remove).sequence
      } yield updateCounts.count(_ == true)
    }
  }

  val newMetadata = RecordMetadata.forNewRecord

  val allFieldCount = recordMapping.recordFieldCount + 1 + metadataMapping.recordFieldCount
  val allFieldsOrdered = (recordMapping.allFields ++ metadataMapping.recordFields).sortBy(_.columnIndex)

  def prepInsert(implicit context: Context) : E[Seq[(ID,A)] => E[Int]] =
    if(metadataTableSameAsRecord) {
      sql.prepare(
        s"INSERT INTO ${tableName.esc} (${allFieldsOrdered.map(_.columnName.esc).mkString(",")}) VALUES(${("?" * allFieldCount).mkString(",")})"
      ).map { ps =>

        { (values:Seq[(ID,A)]) =>
          sql.executePreparedUpdate(ps)(values.map { case (id,a) =>
            (recordFormat.toSqlVal(id) +: recordFormat.toSqlRow(a)) ++ metadataFormat.toSqlRow(newMetadata)
          }:_*)
        }
      }
    } else {
      // Maybe run in parallel
      for {
        tuple <- par(
          sql.prepare(
            s"INSERT INTO ${tableName.esc} (${recordMapping.allFieldsOrdered.map(_.columnName.esc).mkString(",")}) VALUES(${("?" * (recordFieldCount + 1)).mkString(",")})"
          ),
          sql.prepare(
            s"INSERT INTO ${metadataTableName.esc} (${metadataMapping.allFieldsOrdered.map(_.columnName.esc).mkString(",")}) VALUES(${("?" * (metadataMapping.recordFieldCount + 1)).mkString(",")})"
          )
        )
        (prepMainInsert,prepMetadataInsert) = tuple
      } yield { (values: Seq[(ID, A)]) =>
        // Maybe run in parallel
        for {
          tuple <- par(
              sql.executePreparedUpdate(prepMainInsert)(values.map { case (id,a) =>
              recordFormat.toSqlVal(id) +: recordFormat.toSqlRow(a)
            }:_*),
            sql.executePreparedUpdate(prepMetadataInsert)(values.map { case (id,a) =>
              recordFormat.toSqlVal(id) +: metadataFormat.toSqlRow(newMetadata)
            }:_*)
          )
          (mainInsertCount,metadataInsertCount) = tuple
        } yield mainInsertCount
      }
    }

  override def insert(id: ID, a: A): E[Boolean] =
    sql.inTransaction { implicit transaction =>
      prepInsert.flatMap(_(Seq((id,a)))).map(_ == 1)
    }

  override def batchInsert(records: Traversable[(ID, A)]): E[Int] =
    sql.inTransaction { implicit transaction =>
      prepInsert.flatMap(_(records.toSeq))
    }


  // todo: update metadata table too
  // todo: this should be def that accepts implicit context to allow binding transaction below
  def prepUpdate(implicit context: Context) : E[(ID,A) => E[Boolean]] =
    if(metadataTableSameAsRecord) {
      sql.prepare(
        s"UPDATE ${tableName.esc} SET ${recordColNames.map(name => esc"$name=?").mkString(",")},${lastUpdatedColName.esc}=? WHERE ${idColName.esc}=?"
      ).map { ps =>

        { (id:ID,a:A) =>
          sql.executePreparedUpdate(ps)(
            recordFormat.toSqlRow(a) ++
            IndexedSeq(SqlVal.TIMESTAMP(Instant.now()),recordFormat.toSqlVal(id))
          ).map(_ == 1)
        }
      }
    } else {
      for {
        tuple <- par(
          sql.prepare(
            s"UPDATE ${tableName.esc} SET ${recordColNames.map(name => esc"$name=?").mkString(",")} WHERE ${idColName.esc}=?"
          ),
          sql.prepare(
            s"UPDATE ${metadataTableName.esc} SET ${lastUpdatedColName.esc}=? WHERE ${idColName.esc}=?"
          )
        )
        (prepMainUpdate,prepMetaUpdate) = tuple
      } yield { (id:ID,a:A) =>
        for {
          mainUpdateCount <- sql.executePreparedUpdate(prepMainUpdate)(
            recordFormat.toSqlRow(a) :+ recordFormat.toSqlVal(id)
          )
          metaUpdateCount <- sql.executePreparedUpdate(prepMetaUpdate)(
            IndexedSeq(SqlVal.TIMESTAMP(Instant.now()),recordFormat.toSqlVal(id))
          )
        } yield mainUpdateCount == 1
      }
    }


  override def update(id: ID, value: A): E[Boolean] =
    sql.autoCommit { implicit autoCommit =>
      prepUpdate.flatMap(_(id,value))
    }

  override def batchUpdate(records: Traversable[(ID, A)]): E[Int] =
    // Note: implicit keyword not currently allowed inside for-compre https://issues.scala-lang.org/browse/SI-2823
    sql.inTransaction { implicit transaction =>
      for {
        _update <- prepUpdate
        results <- records.map { case (id,a) => _update(id,a) }.sequence
      } yield results.count(_ == true)
    }

  override def upsert(id: ID, a: A): E[(Boolean, Boolean)] =
    for {
      // todo: maybe faster to just update and see if it fails?
      idExists <- exists(id)
      result <-
        if(idExists) {
          update(id,a).map(result => (false,result))
        } else {
          insert(id,a).map(result => (result,false))
        }
    } yield result

  override def batchUpsert(records: Traversable[(ID, A)]): E[(Int, Int)] =
    for {
      // todo: maybe faster to just update and see if it fails?
      idToExists <- batchExists(records.map(_._1))
      (toUpdate,toInsert) = records.partition { case (id,a) => idToExists(id) }
      result <- sql.inTransaction { implicit transaction =>
        for {
          _update <- prepUpdate
          _insert <- prepInsert
          result <- {
            val eUpdates = toUpdate.map { case (id,a) => _update(id,a) }.sequence.map(_.count(_ == true))
            val eInserts = _insert(toInsert.toSeq)
            for {
              updateCount <- eUpdates
              insertCount <- eInserts
            } yield (insertCount,updateCount)
          }
        } yield result
      }
    } yield result
}
