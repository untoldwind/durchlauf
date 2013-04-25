package ws

import scala.concurrent.{Await, Promise, ExecutionContext, Future}
import play.api.libs.iteratee._
import org.apache.http.nio.{ContentDecoder, IOControl}
import org.apache.http.concurrent.FutureCallback
import org.apache.http.nio.client.methods.AsyncByteConsumer
import org.apache.http.{HttpEntity, HttpResponse}
import java.nio.ByteBuffer
import org.apache.http.protocol.{HTTP, HttpContext}
import scala.Some
import play.api.libs.ws.ResponseHeaders
import org.apache.http.nio.protocol.AbstractAsyncResponseConsumer
import org.apache.http.entity.ContentType
import scala.concurrent.stm.Ref
import scala.util.{Failure, Success}

case class StreamedResponseReceiveAdapter()(implicit executor: ExecutionContext) extends ReceiveAdapter[StreamedResponse] {
  private val bufferQueue = BufferQueue()

  val resultPromise = Promise[StreamedResponse]()

  def resultFuture = resultPromise.future

  val futureCallback = new FutureCallback[StreamedResponse] {
    def completed(result: StreamedResponse) {
    }

    def failed(ex: Exception) {
      resultPromise.tryFailure(ex)
    }

    def cancelled() {
      failed(new RuntimeException("Canceled"))
    }
  }

  val responseConsumer = new AbstractAsyncResponseConsumer[StreamedResponse] {

    private val buffer = ByteBuffer.allocate(8 * 1024)

    override def onResponseReceived(response: HttpResponse) {
      val headers = response.getAllHeaders.map(_.getName).toSet.map {
        name: String =>
          name -> response.getHeaders(name).map(_.getValue).toSeq
      }.toMap
      val contentType = if (response.getEntity != null)
        ContentType.getOrDefault(response.getEntity)
      else
        ContentType.DEFAULT_TEXT
      val charset = if (contentType.getCharset != null)
        contentType.getCharset
      else
        HTTP.DEF_CONTENT_CHARSET
      resultPromise.success(StreamedResponse(ResponseHeaders(response.getStatusLine.getStatusCode, headers),
        contentType.getMimeType, charset, bufferQueue.outputEnumerator))
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

    override def buildResult(context: HttpContext): StreamedResponse = {
      bufferQueue.enqueueEOF()
      resultFuture.value.map {
        case Success(result) => result
        case Failure(e) => throw e
      }.getOrElse(throw new RuntimeException("No response header received"))
    }

    override def releaseResources() {
    }
  }

}
