package jbok.persistent.rocksdb

import java.nio.file.Path

import cats.effect.{ContextShift, Resource, Sync, Timer}
import cats.implicits._
import fs2._
import jbok.codec.rlp.RlpCodec
import jbok.common.log.Logger
import jbok.common.thread.ThreadUtil
import jbok.persistent.KeyValueDB
import org.rocksdb.{Logger => _, RocksDB => Underlying, _}
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext

final class RocksDB[F[_]](
    db: Underlying,
    readOptions: ReadOptions,
    writeOptions: WriteOptions,
    ec: ExecutionContext
)(implicit F: Sync[F], cs: ContextShift[F], T: Timer[F])
    extends KeyValueDB[F] {

  override protected[jbok] def getRaw(key: ByteVector): F[Option[ByteVector]] =
    cs.evalOn(ec)(F.delay {
      Option(db.get(readOptions, key.toArray)).map(ByteVector.apply)
    })

  override protected[jbok] def putRaw(key: ByteVector, newVal: ByteVector): F[Unit] =
    cs.evalOn(ec)(
      F.delay {
        db.put(writeOptions, key.toArray, newVal.toArray)
      }
    )

  override protected[jbok] def delRaw(key: ByteVector): F[Unit] =
    cs.evalOn(ec)(F.delay {
      db.delete(writeOptions, key.toArray)
    })

  override protected[jbok] def hasRaw(key: ByteVector): F[Boolean] =
    getRaw(key).map(_.isDefined)

  override protected[jbok] def keysRaw: F[List[ByteVector]] =
    stream(None).map(_._1).compile.toList

  override protected[jbok] def size: F[Int] =
    keysRaw.map(_.length)

  override protected[jbok] def toMapRaw: F[Map[ByteVector, ByteVector]] =
    stream(None).compile.toList.map(_.toMap)

  override protected[jbok] def writeBatchRaw(put: List[(ByteVector, ByteVector)], del: List[ByteVector]): F[Unit] =
    cs.evalOn(ec)(
      F.delay {
        val batch = new WriteBatch()
        put.foreach { case (k, v) => batch.put(k.toArray, v.toArray) }
        del.foreach { k =>
          batch.delete(k.toArray)
        }
        db.write(writeOptions, batch)
      }
    )

  override def keys[Key: RlpCodec](namespace: ByteVector): F[List[Key]] =
    stream(Some(namespace.toArray))
      .map(_._1)
      .compile
      .toList
      .flatMap(_.traverse(k => decode[Key](k, namespace)))

  override def toMap[Key: RlpCodec, Val: RlpCodec](namespace: ByteVector): F[Map[Key, Val]] =
    for {
      mapRaw <- stream(Some(namespace.toArray)).compile.toList.map(_.toMap)
      xs     <- mapRaw.toList.traverse { case (k, v) => (decode[Key](k, namespace), decode[Val](v)).tupled }
    } yield xs.toMap

  ////////////////////////////
  ////////////////////////////

  private def stream(start: Option[Array[Byte]]): Stream[F, (ByteVector, ByteVector)] =
    Stream.resource(iterator(start)).flatMap { iter =>
      Stream.unfoldEval[F, RocksIterator, (ByteVector, ByteVector)](iter)(
        iter =>
          for {
            hn <- F.delay(iter.isValid)
            opt <- if (hn) {
              F.delay(iter.next()).map { _ =>
                Some((ByteVector(iter.key()), ByteVector(iter.value())) -> iter)
              }
            } else none.pure[F]
          } yield opt
      )
    }

  private def iterator(start: Option[Array[Byte]] = None): Resource[F, RocksIterator] =
    Resource {
      for {
        it <- F.delay(db.newIterator())
        _ <- start match {
          case Some(b) => cs.evalOn(ec)(F.delay(it.seek(b)))
          case None    => cs.evalOn(ec)(F.delay(it.seekToFirst()))
        }
      } yield it -> F.delay(it.close())
    }
}

object RocksDB {
  val defaultOptions: Options = new Options().setCreateIfMissing(true)

  val defaultReadOptions = new ReadOptions()

  val defaultWriteOptions = new WriteOptions()

  def resource[F[_]](
      path: Path,
      options: Options = defaultOptions,
      readOptions: ReadOptions = defaultReadOptions,
      writeOptions: WriteOptions = defaultWriteOptions
  )(implicit F: Sync[F], cs: ContextShift[F], T: Timer[F]): Resource[F, KeyValueDB[F]] =
    ThreadUtil.blockingThreadPool[F]("jbok-rocksdb").flatMap { ec =>
      Resource {
        for {
          underlying <- F.delay(Underlying.open(options, path.toString))
          _          <- Logger[F].i(s"open rocksdb at path=${path}")
          db = new RocksDB[F](underlying, readOptions, writeOptions, ec)
        } yield db -> (F.delay(underlying.closeE()) >> Logger[F].i(s"closed rocksdb at path=${path}"))
      }
    }

  def destroy[F[_]](path: Path, options: Options = defaultOptions)(implicit F: Sync[F]): F[Unit] =
    F.delay(Underlying.destroyDB(path.toString, options))
}