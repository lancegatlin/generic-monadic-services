package effectful.examples.effects.db

import java.time.Instant

import query.Query

import scala.language.higherKinds

/**
  * A simple document store
  *
  * @tparam ID type of the identifier
  * @tparam A type of the record
  * @tparam E effect system
  */
trait Db[ID,A,E[_]] {
  import Db._

  /** @return if id exists, some value otherwise none */
  def findById(id: ID) : E[Option[(ID,A,RecordMetadata)]]

  def find(query: Query[A]) : E[Seq[(ID,A,RecordMetadata)]]

  /** @return a batch of all records starting with specified index and batch size (records are sorted by creation time) */
  def findAll(start: Int, batchSize: Int) : E[Seq[(ID,A,RecordMetadata)]]

  /** @return TRUE if document was inserted FALSE if id already exists */
  def insert(id: ID, a : A) : E[Boolean]
  /** @return count of documents successfully inserted */
  def insert(records: Seq[(ID,A)]) : E[Int]
  /** @return TRUE if document was updated FALSE if id doesn't exist */
  def update(id: ID, value: A) : E[Boolean]

  def update(records: TraversableOnce[(ID,A)]) : E[Int]

  def upsert(id: ID, a : A) : E[(Boolean,Boolean)]
  def upsert(records: TraversableOnce[(ID,A)]) : E[(Int,Int)]

  /** @return TRUE if document was marked as removed FALSE if id doesn't exist */
  def remove(id: ID) : E[Boolean]
  def remove(ids: TraversableOnce[ID]) : E[Int]
}

object Db {
  case class RecordMetadata(
    created: Instant,
    lastUpdated: Instant,
    removed: Option[Instant]
  )
}