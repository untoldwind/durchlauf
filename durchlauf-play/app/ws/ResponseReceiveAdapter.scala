package ws

import org.apache.http.nio.client.methods.AsyncCharConsumer
import org.apache.http.HttpResponse
import java.nio.CharBuffer
import org.apache.http.nio.IOControl
import org.apache.http.protocol.HttpContext
import play.api.libs.ws.ResponseHeaders
import scala.concurrent.stm.Ref
import scala.concurrent.{Future, Promise}
import org.apache.http.concurrent.FutureCallback
import play.api.libs.iteratee.Input

case class ResponseReceiveAdapter() extends ReceiveAdapter[Response] {

  private val body = new StringBuilder()

  private val responseRef = Ref(Option.empty[HttpResponse])

  private val resultPromise = Promise[Response]()

  def resultFuture = resultPromise.future

  val futureCallback = new FutureCallback[Response] {
    def completed(result: Response) {
      resultPromise.success(result)
    }

    def failed(ex: Exception) {
      resultPromise.failure(ex)
    }

    def cancelled() {
      failed(new RuntimeException("Canceled"))
    }
  }

  val responseConsumer = new AsyncCharConsumer[Response] {

    override def onCharReceived(buf: CharBuffer, ioctrl: IOControl) {
      body.append(buf.toString())
      buf.clear()
    }

    override def onResponseReceived(response: HttpResponse) {
      responseRef.single.set(Some(response))
    }

    override def buildResult(context: HttpContext): Response = {
      responseRef.single.get.map {
        response: HttpResponse =>
          val headers = response.getAllHeaders.map(_.getName).toSet.map {
            name: String =>
              name -> response.getHeaders(name).map(_.getValue).toSeq
          }.toMap
          Response(ResponseHeaders(response.getStatusLine.getStatusCode, headers), body.toString())
      }.getOrElse {
        throw new RuntimeException("No http response received")
      }
    }
  }
}
