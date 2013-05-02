package ws

import scala.concurrent.{Promise, ExecutionContext}
import play.api.libs.iteratee.{Done, Iteratee}
import scala.concurrent.stm.Ref
import org.apache.http.entity.{ContentType, AbstractHttpEntity}
import org.apache.http.nio.entity.HttpAsyncContentProducer
import org.apache.http.nio.{IOControl, ContentEncoder}
import java.io.OutputStream
import scala.util.{Success, Failure}
import java.nio.ByteBuffer
import scala.annotation.tailrec
import org.apache.http.concurrent.FutureCallback

/**
 * Adapt an [[play.api.libs.iteratee.Iteratee]] to a [[org.apache.http.nio.entity.HttpAsyncContentProducer]].
 */
case class StreamedSendAdapter[T](contentType: ContentType)(implicit executor: ExecutionContext) {

  val bufferQueue = BufferQueue()

  val iteratee = bufferQueue.inputIteratee

  val httpEntity = new AbstractHttpEntity with HttpAsyncContentProducer {
    setContentType(contentType)

    override def produceContent(encoder: ContentEncoder, ioctrl: IOControl) {
      bufferQueue.headOption match {
        case Some(BufferQueue.Chunk(data, offset)) =>
          val written = encoder.write(ByteBuffer.wrap(data, offset, data.length - offset))
          bufferQueue.dropBytes(written)
        case Some(BufferQueue.EOF) =>
          encoder.complete()
        case None =>
          ioctrl.suspendOutput()
          bufferQueue.inputAvailable.onSuccess {
            case _ =>
              ioctrl.requestOutput()
          }
      }
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
