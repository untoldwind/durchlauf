package ws.alt

import scala.concurrent.{Promise, ExecutionContext}
import org.apache.http.entity.AbstractHttpEntity
import org.apache.http.nio.entity.HttpAsyncContentProducer
import org.apache.http.nio.{IOControl, ContentEncoder}
import java.nio.ByteBuffer
import java.io.{OutputStream, IOException}
import play.api.libs.iteratee.{Done, Iteratee}
import org.apache.http.concurrent.FutureCallback
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.stm.Ref

case class ProducerAdapter[T]()(implicit executor: ExecutionContext) extends AbstractHttpEntity with HttpAsyncContentProducer {

  sealed trait UpstreamEvent {
    def forward(encoder: ContentEncoder): Boolean
  }

  case class ChunkUpstreamEvent(data: Array[Byte]) extends UpstreamEvent {
    private var offset: Int = 0

    override def forward(encoder: ContentEncoder) = {
      offset += encoder.write(ByteBuffer.wrap(data, offset, data.length - offset))

      offset >= data.length
    }
  }

  case class EOFUpstreamEvent() extends UpstreamEvent {
    override def forward(encoder: ContentEncoder) = {
      encoder.complete()
      true
    }
  }

  case class AbortUpstreamEvent() extends UpstreamEvent {
    override def forward(encoder: ContentEncoder) = {
      throw new IOException("Aborted")
    }
  }

  val resultPromise = Promise[T]()

  val iteratee: Iteratee[Array[Byte], T] = Iteratee.foreach {
    chunk: Array[Byte] =>
      offerEvent(ChunkUpstreamEvent(chunk))
  }.flatMap {
    _ =>
      offerEvent(EOFUpstreamEvent())
      Iteratee.flatten(resultPromise.future.map(Done(_)))
  }

  val futureCallback = new FutureCallback[T] {
    def completed(result: T) {
      resultPromise.trySuccess(result)
    }

    def failed(ex: Exception) {
      upstreamInError.set(true)
      resultPromise.tryFailure(ex)
    }

    def cancelled() {
      upstreamInError.set(true)
      resultPromise.tryFailure(new RuntimeException("Canceled"))
    }
  }

  private val eventQueue = new ConcurrentLinkedQueue[UpstreamEvent]()

  private val eventQueueSize = new AtomicInteger(0)

  private val upstreamInError = new AtomicBoolean(false)

  private val lastIOControl: Ref[Option[IOControl]] = Ref(Option.empty[IOControl])

  private def offerEvent(upstreamEvent: UpstreamEvent) {
    if (!upstreamInError.get) {
      eventQueue.offer(upstreamEvent)
      eventQueueSize.incrementAndGet()
      lastIOControl.single.get.map(_.requestOutput())
    }
  }

  override def produceContent(encoder: ContentEncoder, ioctrl: IOControl) {
    lastIOControl.single.set(Some(ioctrl))
    if (eventQueueSize.get() > 0) {
      synchronized {
        try {
          while (eventQueueSize.get > 0) {
            val event = eventQueue.peek()

            if (event.forward(encoder)) {
              eventQueue.poll()
              eventQueueSize.decrementAndGet()
            } else {
              return
            }
          }
          if (!encoder.isCompleted) {
            ioctrl.suspendOutput()
            if (eventQueueSize.get > 0) {
              ioctrl.requestOutput()
            }
          }
        } catch {
          case e: IOException =>
            upstreamInError.set(true)
            while (eventQueueSize.get() > 0) {
              eventQueue.poll()
              eventQueueSize.decrementAndGet()
            }
            throw e
        }
      }
    }
  }

  override def isRepeatable = false

  override def isStreaming = true

  override def getContentLength = -1

  override def close() {
  }

  override def getContent = {
    throw new RuntimeException("Not available")
  }

  override def writeTo(outstream: OutputStream) {
    throw new RuntimeException("Not available")
  }
}
