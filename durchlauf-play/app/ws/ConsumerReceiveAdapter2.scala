package ws

import scala.concurrent.{Promise, ExecutionContext, Future}
import play.api.libs.iteratee._
import org.apache.http.nio.{ContentDecoder, IOControl}
import org.apache.http.concurrent.FutureCallback
import org.apache.http.nio.client.methods.AsyncByteConsumer
import org.apache.http.{HttpEntity, HttpResponse}
import java.nio.ByteBuffer
import org.apache.http.protocol.HttpContext
import scala.Some
import play.api.libs.ws.ResponseHeaders
import org.apache.http.nio.protocol.AbstractAsyncResponseConsumer
import org.apache.http.entity.ContentType
import scala.concurrent.stm.Ref

case class ConsumerReceiveAdapter2(consumer: ResponseHeaders => Future[Iteratee[Array[Byte], Unit]])
                                  (implicit executor: ExecutionContext) extends ReceiveAdapter[Unit] {
  private val targetPromise = Promise[Iteratee[Array[Byte], Unit]]()

  private val bufferQueue = BufferQueue()

  targetPromise.future.map {
    iterator =>
      bufferQueue.headFuture.map(feed(iterator, _))
  }

  def feed(iterator: Iteratee[Array[Byte], Unit], item: BufferQueue.QueueItem) {
        item match {
          case chunk: BufferQueue.Chunk =>
            iterator.fold {
              case Step.Cont(k) => Future {
                bufferQueue.dropBytes(chunk.length)
                k(Input.El(chunk.data.drop(chunk.offset)))
              }
              case _ =>  Future.successful(iterator)
            }.map {
              next =>
                bufferQueue.headFuture.map(feed(next, _))
            }
          case BufferQueue.EOF =>
            iterator.fold {
              case Step.Cont(k) => Future(k(Input.EOF))
              case _ =>  Future.successful(iterator)
            }
        }
  }

  val resultPromise = Promise[Unit]()

  def resultFuture = resultPromise.future

  val futureCallback = new FutureCallback[Unit] {
    def completed(result: Unit) {
      resultPromise.success(result)
    }

    def failed(ex: Exception) {
      resultPromise.failure(ex)
    }

    def cancelled() {
      failed(new RuntimeException("Canceled"))
    }
  }

  val responseConsumer = new AbstractAsyncResponseConsumer[Unit] {

    private val buffer = ByteBuffer.allocate(8 * 1024)

    override def onResponseReceived(response: HttpResponse) {
      val headers = response.getAllHeaders.map(_.getName).toSet.map {
        name: String =>
          name -> response.getHeaders(name).map(_.getValue).toSeq
      }.toMap
      val target = consumer(ResponseHeaders(response.getStatusLine.getStatusCode, headers))
      targetPromise.completeWith(target)
    }

    override def onEntityEnclosed(entity: HttpEntity, contentType: ContentType) {
    }

    override def onContentReceived(decoder: ContentDecoder, ioctrl: IOControl) {
      val bytesRead = decoder.read(buffer)
      if (bytesRead <= 0)
        return
      buffer.flip()

      val bodyPart = new Array[Byte](buffer.remaining())
      buffer.get(bodyPart)
      buffer.clear()
      val bufferAvailable = bufferQueue.enqueueChunk(bodyPart)
      if (!bufferAvailable.isCompleted) {
        ioctrl.suspendInput()
        bufferAvailable.onSuccess {
          case _ =>
            ioctrl.requestInput()
        }
      }
    }

    override def buildResult(context: HttpContext) {
      bufferQueue.enqueueEOF()
    }

    override def releaseResources() {
    }
  }

}
