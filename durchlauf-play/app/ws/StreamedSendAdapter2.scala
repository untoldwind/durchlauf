package ws

import scala.concurrent.ExecutionContext
import org.apache.http.entity.{ContentType, AbstractHttpEntity}
import org.apache.http.nio.entity.HttpAsyncContentProducer
import org.apache.http.nio.{IOControl, ContentEncoder}
import java.io.OutputStream
import java.nio.ByteBuffer
import play.api.libs.iteratee.Input

case class StreamedSendAdapter2(contentType: ContentType)(implicit executor: ExecutionContext) {

  val transferBuffer = TransferBuffer()

  val iteratee = transferBuffer.inputIteratee

  val httpEntity = new AbstractHttpEntity with HttpAsyncContentProducer {
    setContentType(contentType)

    override def produceContent(encoder: ContentEncoder, ioctrl: IOControl) {
      // the http client demands that we produce some content, so peek at the head of the buffer queue
      transferBuffer.processNextChunk({
        case Input.El(data) =>
          // We have some data, so send as much as possible
          encoder.write(ByteBuffer.wrap(data, 0, data.length))
        case Input.EOF =>
          // We have reached the EOF, tell the http client so
          encoder.complete()
          0
      }, {
        ioctrl.suspendOutput()
      }, {
        ioctrl.requestOutput()
      })
    }

    override def getContentLength = -1

    override def getContent = {
      throw new RuntimeException("Not available")
    }

    override def writeTo(outstream: OutputStream) {
      throw new RuntimeException("Not available")
    }

    override def isStreaming = true

    override def isRepeatable = false

    def close() {}
  }
}
