package ws

import org.apache.http.nio.client.methods.{AsyncByteConsumer, AsyncCharConsumer}
import org.apache.http.HttpResponse
import java.nio.{ByteBuffer, CharBuffer}
import org.apache.http.nio.IOControl
import org.apache.http.protocol.{HTTP, HttpContext}
import play.api.libs.ws.ResponseHeaders
import scala.concurrent.stm.Ref
import scala.concurrent.{Future, Promise}
import org.apache.http.concurrent.FutureCallback
import java.io.ByteArrayOutputStream
import org.apache.http.entity.ContentType

/**
 * Adapt a [[org.apache.http.nio.protocol.HttpAsyncResponseConsumer]] to a [[ws.CompletedResponse]].
 */
case class CompletedResponseReceiveAdapter() extends ReceiveAdapter[CompletedResponse] {

  private val body = new ByteArrayOutputStream()

  private val responseRef = Ref(Option.empty[HttpResponse])

  private val resultPromise = Promise[CompletedResponse]()

  override def resultFuture = resultPromise.future

  override val futureCallback = new FutureCallback[CompletedResponse] {
    def completed(result: CompletedResponse) {
      resultPromise.success(result)
    }

    def failed(ex: Exception) {
      resultPromise.failure(ex)
    }

    def cancelled() {
      failed(new RuntimeException("Canceled"))
    }
  }

  override val responseConsumer = new AsyncByteConsumer[CompletedResponse] {

    override def onByteReceived(buffer: ByteBuffer, ioctrl: IOControl) {
      val bodyPart = new Array[Byte](buffer.remaining())
      buffer.get(bodyPart)
      buffer.clear()

      body.write(bodyPart)
    }

    override def onResponseReceived(response: HttpResponse) {
      responseRef.single.set(Some(response))
    }

    override def buildResult(context: HttpContext): CompletedResponse = {
      responseRef.single.get.map {
        response: HttpResponse =>
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
          CompletedResponse(ResponseHeaders(response.getStatusLine.getStatusCode, headers),
            contentType.getMimeType, charset, body.toByteArray)
      }.getOrElse {
        throw new RuntimeException("No http response received")
      }
    }
  }
}
