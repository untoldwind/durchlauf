package ws

import scala.concurrent.{Promise, ExecutionContext, Future}
import play.api.libs.iteratee.{Step, Input, Iteratee}
import org.apache.http.nio.client.methods.AsyncByteConsumer
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.stm.Ref
import org.apache.http.nio.{ContentDecoder, IOControl}
import org.apache.http.concurrent.FutureCallback
import org.apache.http.{HttpEntity, HttpResponse}
import java.nio.ByteBuffer
import org.apache.http.protocol.HttpContext
import scala.util.{Failure, Success}
import play.api.libs.ws.ResponseHeaders
import org.apache.http.nio.protocol.{AbstractAsyncResponseConsumer, HttpAsyncResponseConsumer}
import org.apache.http.entity.ContentType

case class ConsumerReceiveAdapter1(consumer: ResponseHeaders => Future[Iteratee[Array[Byte], Unit]])
                                  (implicit executor: ExecutionContext) extends ReceiveAdapter[Unit] {
  private val queueCount = new AtomicInteger(0)
  private val targetPromise = Promise[Iteratee[Array[Byte], Unit]]()

  private val lastIOControl: Ref[Option[IOControl]] = Ref(Option.empty[IOControl])

  private val lastPush: Ref[Future[Option[Input[Array[Byte]] => Iteratee[Array[Byte], Unit]]]] =
    Ref(Iteratee.flatten(targetPromise.future).pureFold {
      case Step.Cont(k) => Some(k)
      case other => None
    })

  val resultPromise = Promise[Unit]()

  def resultFuture = resultPromise.future

  val futureCallback = new FutureCallback[Unit] {
    def completed(result: Unit) {
      resultPromise.success(result)
    }

    def failed(ex: Exception) {
      println(">>> A " + ex)
      push(Input.EOF)
      lastPush.single.swap(Future.successful(None))
      resultPromise.failure(ex)
    }

    def cancelled() {
      println(">>> D")
      failed(new RuntimeException("Canceled"))
    }
  }

  val responseConsumer = new AbstractAsyncResponseConsumer[Unit] {
    private var bbuf: ByteBuffer = null

    override def onResponseReceived(response: HttpResponse) {
      val headers = response.getAllHeaders.map(_.getName).toSet.map {
        name: String =>
          name -> response.getHeaders(name).map(_.getValue).toSeq
      }.toMap
      val target = consumer(ResponseHeaders(response.getStatusLine.getStatusCode, headers))
      targetPromise.success(Iteratee.flatten(target))
    }

    override def onEntityEnclosed(entity: HttpEntity, contentType: ContentType) {
      bbuf = ByteBuffer.allocate(8 * 1024)
    }

    override def onContentReceived(decoder: ContentDecoder, ioctrl: IOControl) {
      val bytesRead = decoder.read(bbuf)
      if (bytesRead <= 0)
        return
      bbuf.flip()

      lastIOControl.single.set(Some(ioctrl))
      val bodyPart = new Array[Byte](bbuf.remaining())
      bbuf.get(bodyPart)
      bbuf.clear()
      push(Input.El(bodyPart))
    }

    override def buildResult(context: HttpContext) {
      push(Input.EOF)
      lastPush.single.swap(Future.successful(None))
    }

    override def releaseResources() {
      bbuf = null
    }
  }

  private def push(chunk: Input[Array[Byte]]) {
    if (queueCount.incrementAndGet() > 5) {
      lastIOControl.single.get.foreach(_.suspendInput())
    }
    val eventuallyNext = Promise[Option[Input[Array[Byte]] => Iteratee[Array[Byte], Unit]]]()
    lastPush.single.swap(eventuallyNext.future).onComplete {
      case Success(None) => eventuallyNext.success(None)
      case Success(Some(k)) =>
        val n = {
          val next = k(chunk)
          next.pureFold {
            case Step.Cont(nextK) =>
              if (queueCount.decrementAndGet() < 5) {
                lastIOControl.single.get.foreach(_.requestInput())
              }

              Some(nextK)
            case _ =>
              None
          }
        }
        eventuallyNext.completeWith(n)
      case Failure(e) =>
        lastIOControl.single.get.foreach(_.shutdown())
        eventuallyNext.success(None)
    }
  }
}
