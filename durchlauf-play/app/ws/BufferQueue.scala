package ws

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.annotation.tailrec
import scala.concurrent.stm.Ref
import scala.collection.immutable.Queue
import play.api.libs.iteratee.{Enumerator, Input, Step, Iteratee}
import scala.util.{Failure, Success}

case class BufferQueue(threshold: Int = 64 * 1024)(implicit executor: ExecutionContext) {

  import BufferQueue._

  case class QueueState(items: Queue[QueueItem],
                        total: Long,
                        waitingForBuffer: Option[Promise[Unit]],
                        waitingForInput: Option[Promise[Unit]]) {
    def enqueue(item: QueueItem): QueueState = {
      waitingForInput.foreach(_.trySuccess())
      if (total + item.length < threshold) {
        waitingForBuffer.foreach(_.trySuccess())
        QueueState(items :+ item, total + item.length, None, None)
      } else {
        QueueState(items :+ item, total + item.length, Some(waitingForBuffer.getOrElse(Promise[Unit]())), None)
      }
    }

    def dropBytes(bytes: Int): QueueState = {
      if (bytes > total) {
        throw new RuntimeException("Wrote more than available, this should not happen")
      }
      val nextInWait = if (!waitingForBuffer.isEmpty && total - bytes < threshold) {
        waitingForBuffer.foreach(_.trySuccess())
        None
      } else {
        waitingForBuffer
      }
      val nextItems = drop(items, bytes)
      QueueState(nextItems, total - bytes, nextInWait, waitingForInput)
    }

    def markWaitingForInput(): QueueState = {
      if (items.isEmpty) {
        QueueState(items, total, waitingForBuffer, Some(Promise[Unit]()))
      } else {
        QueueState(items, total, waitingForBuffer, None)
      }
    }

    @tailrec
    private def drop(items: Queue[QueueItem], count: Int): Queue[QueueItem] = {
      items.headOption match {
        case Some(chunk: Chunk) if (chunk.length > count) =>
          chunk.drop(count) +: items.drop(1)
        case Some(chunk: Chunk) =>
          drop(items.drop(1), count - chunk.length)
        case Some(EOF) =>
          items
        case None =>
          items
      }
    }
  }

  val queueState: Ref[QueueState] = Ref(QueueState(Queue(), 0, None, None))

  def enqueueChunk(chunk: Array[Byte]): Future[Unit] = {
    queueState.single.transformAndGet(_.enqueue(Chunk(chunk, 0))).waitingForBuffer.map(_.future).getOrElse(Future.successful())
  }

  def enqueueEOF() {
    queueState.single.transform(_.enqueue(EOF))
  }

  def headOption: Option[QueueItem] = queueState.single.get.items.headOption

  def headFuture: Future[QueueItem] = queueState.single.get.items.headOption.map(Future.successful(_)).getOrElse {
    inputAvailable.flatMap {
      _ => headFuture
    }
  }

  def dropBytes(bytes: Int) {
    queueState.single.transform(_.dropBytes(bytes))
  }

  def inputAvailable: Future[Unit] = {
    queueState.single.transformAndGet(_.markWaitingForInput()).waitingForInput.map(_.future).getOrElse(Future.successful())
  }

  def outputEnumerator = new Enumerator[Array[Byte]] {
    def apply[A](iterator: Iteratee[Array[Byte], A]): Future[Iteratee[Array[Byte], A]] = {
      val resultPromise = Promise[Iteratee[Array[Byte], A]]()

      def step(current: Iteratee[Array[Byte], A]) {
        headFuture.onComplete {
          case Success(chunk: BufferQueue.Chunk) =>
            current.fold {
              case Step.Cont(k) => Future {
                dropBytes(chunk.length)
                k(Input.El(chunk.data.drop(chunk.offset)))
              }
              case _ => Future.successful(current)
            }.onComplete {
              case Success(next) =>
                step(next)
              case Failure(e) =>
                resultPromise.failure(e)
            }
          case Success(BufferQueue.EOF) =>
            current.fold {
              case Step.Cont(k) => Future(k(Input.EOF))
              case _ => Future.successful(current)
            }.onComplete(resultPromise.complete)
          case Failure(e) =>
            resultPromise.failure(e)
        }
      }

      step(iterator)

      resultPromise.future
    }
  }
}

object BufferQueue {

  sealed trait QueueItem {
    def length: Int

    def drop(bytes: Int): QueueItem
  }

  case class Chunk(data: Array[Byte], offset: Int) extends QueueItem {
    def length = data.length - offset

    def drop(bytes: Int): QueueItem = {
      if (bytes + offset > data.length) {
        throw new RuntimeException("Try to drop " + bytes + " but there is only " + length + " left in chunk")
      }
      Chunk(data, offset + bytes)
    }
  }

  object EOF extends QueueItem {
    def length = 0

    def drop(bytes: Int): QueueItem = {
      throw new RuntimeException("Cannot drop from EOF")
    }
  }

}