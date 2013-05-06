package ws

import org.jboss.netty.buffer.{ChannelBuffers, ChannelBuffer}
import play.api.libs.iteratee.{Input, Step, Iteratee, Enumerator}
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.{Failure, Success}
import java.nio.ByteBuffer
import play.api.Logger.logger

case class TransferBuffer(size: Int = 32 * 1024, threshold: Int = 8 * 1024)(implicit executor: ExecutionContext) {
  val channelBuffer: ChannelBuffer = ChannelBuffers.dynamicBuffer(size)

  var writeAvailableCallback: Option[() => Unit] = Option.empty[() => Unit]

  var readAvailableCallback: Option[() => Unit] = Option.empty[() => Unit]

  var closed = false

  def writeChunk(data: Array[Byte]): Future[Unit] = {
    synchronized {
      channelBuffer.writeBytes(data)
      readAvailableCallback.foreach(_.apply())
      readAvailableCallback = None
      if (channelBuffer.writableBytes() < threshold) {
        val p = Promise[Unit]()

        writeAvailableCallback.foreach {
          callback =>
            logger.warn("Two writeAvailable waits: There should be just one writer on a transfer buffer")
            callback()
        }
        writeAvailableCallback = Some({
          () =>
            p.success()
        })

        p.future
      } else {
        Future.successful()
      }
    }
  }

  def writeChunk(buffer: ByteBuffer, suspendWrite: => Unit, resumeWrite: => Unit) {
    synchronized {
      channelBuffer.writeBytes(buffer)
      buffer.clear()
      readAvailableCallback.foreach(_.apply())
      readAvailableCallback = None
      if (channelBuffer.writableBytes() < threshold) {
        suspendWrite
        writeAvailableCallback.foreach {
          callback =>
            logger.warn("Two writeAvailable waits: There should be just one writer on a transfer buffer")
            callback()
        }
        writeAvailableCallback = Some({
          () =>
            resumeWrite
        })
      }
    }
  }

  def close() {
    synchronized {
      closed = true
      readAvailableCallback.foreach(_.apply())
      readAvailableCallback = None
    }
  }

  def processNextChunk(process:Input[Array[Byte]] => Int, suspendRead: => Unit, resumeRead: => Unit) {
    synchronized {
      if (channelBuffer.readableBytes() > 0) {
        val chunk = new Array[Byte](channelBuffer.readableBytes())

        channelBuffer.getBytes(channelBuffer.readerIndex(), chunk)
        val processed = process(Input.El(chunk))

        channelBuffer.readerIndex(channelBuffer.readerIndex() + processed)
        channelBuffer.discardReadBytes()

        if (channelBuffer.writableBytes() > threshold) {
          writeAvailableCallback.foreach(_.apply())
          writeAvailableCallback = None
        }
      } else if (closed) {
        process(Input.EOF)
      } else {
        suspendRead
        readAvailableCallback.foreach {
          callback =>
            logger.warn("Two readAvailable waits: There should be just one reader on a transfer buffer")
            callback()
        }
        readAvailableCallback = Some({
          () => resumeRead
        })
      }
    }
  }

  def nextChunkFuture: Future[Input[Array[Byte]]] = {

    synchronized {
      if (channelBuffer.readableBytes() > 0) {
        val result = new Array[Byte](channelBuffer.readableBytes())

        channelBuffer.readBytes(result)
        channelBuffer.discardReadBytes()

        if (channelBuffer.writableBytes() > threshold) {
          writeAvailableCallback.foreach(_.apply())
          writeAvailableCallback = None
        }

        Future.successful(Input.El(result))
      } else if (closed) {
        Future.successful(Input.EOF)
      } else {
        val p = Promise[Unit]()
        readAvailableCallback.foreach {
          callback =>
            logger.warn("Two readAvailable waits: There should be just one reader on a transfer buffer")
            callback()
        }
        readAvailableCallback = Some({
          () => p.success()
        })

        p.future.flatMap(_ => nextChunkFuture)
      }
    }
  }

  /**
   * Convenient way to expose the enqueuing part as an [[play.api.libs.iteratee.Iteratee]]
   */
  def inputIteratee: Iteratee[Array[Byte], Unit] = Iteratee.foldM[Array[Byte], Unit]() {
    (_, chunk) =>
      writeChunk(chunk)
  }.map {
    _ =>
      close()
  }


  /**
   * Convenient way to expose the dequeuing part as an [[play.api.libs.iteratee.Enumerator]].
   */
  def outputEnumerator = new Enumerator[Array[Byte]] {
    def apply[A](iterator: Iteratee[Array[Byte], A]): Future[Iteratee[Array[Byte], A]] = {
      val resultPromise = Promise[Iteratee[Array[Byte], A]]()

      def step(current: Iteratee[Array[Byte], A]) {
        nextChunkFuture.onComplete {
          case Success(Input.EOF) =>
            current.fold {
              case Step.Cont(k) => Future(k(Input.EOF))
              case _ => Future.successful(current)
            }.onComplete(resultPromise.complete)
          case Success(input) =>
            current.fold {
              case Step.Cont(k) => Future(k(input))
              case _ => Future.successful(current)
            }.onComplete {
              case Success(next) =>
                step(next)
              case Failure(e) =>
                resultPromise.failure(e)
            }
          case Failure(e) =>
            resultPromise.failure(e)
        }
      }

      step(iterator)

      resultPromise.future
    }
  }
}
