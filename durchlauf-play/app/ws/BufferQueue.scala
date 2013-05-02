package ws

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.annotation.tailrec
import scala.concurrent.stm.Ref
import scala.collection.immutable.Queue
import play.api.libs.iteratee._
import scala.util.Failure
import scala.Some
import scala.util.Success

/**
 * Internal magic to fit a herd of camels through the eye of a needle.
 *
 * More precisely, all adapters depend on a buffering mechanism offering the following features:
 * $ - Only a given number of bytes should be buffered (so that there is no memory leak if one side is sending faster
 * than the other is able to process).
 * $ - Nevertheless the processing should not fail just because the limit of the buffer is reached (i.e. the buffer
 * threshold is soft)
 * $ - On queuing up a received chunk of data a future is returned when the next chunk of data can be queued (so that
 * the receiver can suspend its input if the buffer is full)
 * $ - For dequeuing a chunk of data to be send, a future is provided when the next chunk of data is ready (so that
 * the sender can suspend its output if the buffer is empfy)
 *
 * @param threshold soft threshold of the buffer (number of bytes to be queued).
 * @param executor implicit execution context
 */
case class BufferQueue(threshold: Int = 64 * 1024)(implicit executor: ExecutionContext) {

  import BufferQueue._

  /**
   * Represents the internal state of the queue.
   * This is an immutable object that will be replaced by the next state via STM transformations.
   *
   * @param items the currently queued items (usually chunks of data)
   * @param total the current number of bytes queued (i.e. sum of all items)
   * @param waitingForFreeBuffer optional promise that can be used by receivers to wait once the buffer is free again
   *                             (if None, no one is waiting)
   * @param waitingForDataAvailable optional promise that can be used by sender to wait for data available in the buffer
   *                                (if None, no one is waiting)
   */
  case class QueueState(items: Queue[QueueItem],
                        total: Long,
                        waitingForFreeBuffer: Option[Promise[Unit]],
                        waitingForDataAvailable: Option[Promise[Unit]]) {
    /**
     * Enqueue an item.
     * Note: This is a state transition that should by only performed with a STM context.
     *
     * @param item the item to queue.
     * @return the followup queue state containing the item.
     */
    def enqueue(item: QueueItem): QueueState = {
      // Notify everyone who is currently waiting for data (since we are in STM this might happen more than once)
      waitingForDataAvailable.foreach(_.trySuccess())
      if (total + item.length < threshold) {
        // We are still below the threshold, so we can notify everyone who is waiting to add some more data
        // (there should be none, but its a bad idea to drop just forget a promise without keeping it)
        waitingForFreeBuffer.foreach(_.trySuccess())
        QueueState(items :+ item, total + item.length, None, None)
      } else {
        // We reached the threshold so ensure that there is a waitForFreeBuffer
        QueueState(items :+ item, total + item.length, Some(waitingForFreeBuffer.getOrElse(Promise[Unit]())), None)
      }
    }

    /**
     * Drop a number of bytes from the head of the queue.
     * Since the chunk size of the receiver does not need to match the chunksize of the sender, this may only drop part
     * of an item or multiple items at once.
     * Note: This is a state transition that should by only performed with a STM context.
     *
     * @param bytes the number of bytes to drop
     * @return the followup queue state with dropped bytes
     */
    def dropBytes(bytes: Int): QueueState = {
      if (bytes > total) {
        // This really should not happen (but pigs might fly)
        throw new RuntimeException("Wrote more than available, this should not happen")
      }
      val nextWaitingForFreeBuffer = if (!waitingForFreeBuffer.isEmpty && total - bytes < threshold) {
        // We eventually have dropped below the threshold, so we can notify everyone who is waiting to add some data
        waitingForFreeBuffer.foreach(_.trySuccess())
        None
      } else {
        // Nothing has changed
        waitingForFreeBuffer
      }
      val nextItems = drop(items, bytes)
      QueueState(nextItems, total - bytes, nextWaitingForFreeBuffer, waitingForDataAvailable)
    }

    /**
     * Add a wait for data available.
     * Note: This is a state transition that should by only performed with a STM context.
     *
     * @return the followup queue state
     */
    def markWaitingForDataAvailable(): QueueState = {
      if (items.isEmpty) {
        // The queue is indeed empty at the moment, so we add the wait
        QueueState(items, total, waitingForFreeBuffer, Some(Promise[Unit]()))
      } else {
        // The queue is not empty ... what are you waiting for?
        // Actually this might happen if some data arrived just the moment we decided to wait for some
        QueueState(items, total, waitingForFreeBuffer, None)
      }
    }

    /**
     * Internal helper to drop a number of bytes from the head of the queue.
     * This actually removes all obsolete queue items or drops some bytes from the first ttem in the queue.
     */
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

  /**
   * STM holder of the current queue state.
   */
  val queueState: Ref[QueueState] = Ref(QueueState(Queue(), 0, None, None))

  /**
   * Enqueue a chunk of data.
   * Usually this is used by a receiver.
   *
   * @param chunk the chunk of data
   * @return future when the next chunk may be queued
   */
  def enqueueChunk(chunk: Array[Byte]): Future[Unit] = {
    // if the current state of the buffer has a watingforFreeBuffer we return that
    // otherwise there is still room in the buffer and the next chunk may be queued immediately
    queueState.single.transformAndGet(_.enqueue(Chunk(chunk, 0))).waitingForFreeBuffer.map(_.future).getOrElse(Future.successful())
  }

  /**
   * Enqueue an EOF.
   * Thus as no return as it usually is not good style to send anything else after an EOF.
   */
  def enqueueEOF() {
    queueState.single.transform(_.enqueue(EOF))
  }

  /**
   * Get the current head of the queue (or nothing).
   *
   * This is a `peek` at current state of the queue.
   */
  def headOption: Option[QueueItem] = queueState.single.get.items.headOption

  /**
   * Get a future once the head of the queue is available.
   */
  def headFuture: Future[QueueItem] = queueState.single.get.items.headOption.map(Future.successful(_)).getOrElse {
    inputAvailable.flatMap {
      _ =>
        // this is a bit tricky: There might be some rare misfires, so we just try again
        headFuture
    }
  }

  /**
   * Drop a number of bytes from the head of the queue.
   * Usually this is used be a sender or processor that a certain number of bytes have be send/processed successfully.
   *
   * @param bytes number of bytes to drop
   */
  def dropBytes(bytes: Int) {
    queueState.single.transform(_.dropBytes(bytes))
  }

  /**
   * Get a future when some data is available in the queue.
   * Note: Right now the implementation does not guarantee, that there actually is some data if this fires. To be
   * sure use `headFuture` instead
   */
  def inputAvailable: Future[Unit] = {
    queueState.single.transformAndGet(_.markWaitingForDataAvailable()).waitingForDataAvailable.map(_.future).getOrElse(Future.successful())
  }

  /**
   * Convenient way to expose the enqueuing part as an [[play.api.libs.iteratee.Iteratee]]
   */
  def inputIteratee: Iteratee[Array[Byte], Unit] = Iteratee.foldM[Array[Byte], Unit]() {
    (_, chunk) =>
      enqueueChunk(chunk)
  }.map {
    _ =>
      enqueueEOF()
  }

  /**
   * Convenient way to expose the dequeuing part as an [[play.api.libs.iteratee.Enumerator]].
   */
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

/**
 * Companion object.
 */
object BufferQueue {

  /**
   * Common trait of all items that can be queued in a [[ws.BufferQueue]].
   * This is quite similar to an [[play.api.libs.iteratee.Input]], though with some twists.
   */
  sealed trait QueueItem {
    /**
     * Byte length of the item
     */
    def length: Int

    /**
     * Drop a number of byte from the item.
     *
     * @param bytes number of bytes to drop, whereas it has be be ensured that `bytes` <= `length`
     * @return queue item with dropped bytes
     */
    def drop(bytes: Int): QueueItem
  }

  /**
   * A chunk of data that can be queued.
   */
  case class Chunk(data: Array[Byte], offset: Int) extends QueueItem {
    override def length = data.length - offset

    override def drop(bytes: Int): QueueItem = {
      if (bytes + offset > data.length) {
        throw new RuntimeException("Try to drop " + bytes + " but there is only " + length + " left in chunk")
      }
      Chunk(data, offset + bytes)
    }
  }

  /**
   * EOF marker withing the queue.
   */
  object EOF extends QueueItem {
    override def length = 0

    override def drop(bytes: Int): QueueItem = {
      throw new RuntimeException("Cannot drop from EOF")
    }
  }

}