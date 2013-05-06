package ws

import scala.concurrent.{Promise, ExecutionContext}
import org.apache.http.concurrent.FutureCallback
import org.apache.http.nio.protocol.AbstractAsyncResponseConsumer
import java.nio.ByteBuffer
import org.apache.http.{HttpEntity, HttpResponse}
import org.apache.http.entity.ContentType
import org.apache.http.protocol.{HttpContext, HTTP}
import play.api.libs.ws.ResponseHeaders
import org.apache.http.nio.{IOControl, ContentDecoder}
import scala.util.{Failure, Success}

case class StreamedResponseReceiveAdapter2()(implicit executor: ExecutionContext) extends ReceiveAdapter[StreamedResponse] {
  private val transferBuffer = TransferBuffer()

  /**
   * We hand up a promise of an http response.
   */
  val resultPromise = Promise[StreamedResponse]()

  override def resultFuture = resultPromise.future

  override val futureCallback = new FutureCallback[StreamedResponse] {
    def completed(result: StreamedResponse) {
    }

    def failed(ex: Exception) {
      resultPromise.tryFailure(ex)
    }

    def cancelled() {
      failed(new RuntimeException("Canceled"))
    }
  }

  override val responseConsumer = new AbstractAsyncResponseConsumer[StreamedResponse] {

    private val buffer = ByteBuffer.allocate(8 * 1024)

    override def onResponseReceived(response: HttpResponse) {
      // At this point we can keep our promise, just have to convert the http header to a more convenient structure
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
        contentType.getMimeType, charset, transferBuffer.outputEnumerator))
    }

    override def onEntityEnclosed(entity: HttpEntity, contentType: ContentType) {
      // Not need here
    }

    override def onContentReceived(decoder: ContentDecoder, ioctrl: IOControl) {
      val bytesRead = decoder.read(buffer)
      if (bytesRead <= 0)
        return
      buffer.flip()

      transferBuffer.writeChunk(buffer, {
        ioctrl.suspendInput()
      }, {
        ioctrl.requestInput()
      })
    }

    override def buildResult(context: HttpContext): StreamedResponse = {
      transferBuffer.close()
      resultFuture.value.map {
        case Success(result) => result
        case Failure(e) => throw e
      }.getOrElse(throw new RuntimeException("No response header received"))
    }

    override def releaseResources() {
    }
  }

}

