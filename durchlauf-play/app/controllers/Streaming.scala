package controllers

import play.api.mvc.{Result, Action, Controller}
import ws.AsyncWS
import scala.concurrent.{Future, Promise}
import play.api.libs.iteratee.{Iteratee, Done}
import play.api.libs.concurrent.Execution.Implicits._

object Streaming extends Controller {
  val baseUrl = "http://localhost:10390"

  def getDigest(size: Long) = Action {
    val resultPromise = Promise[Result]()
    AsyncWS.getStream(baseUrl + "/digest?" + size) {
      responseHeaders =>
      // At this point we still might figure out what to do with the response
      // e.g. we do not accept anything but a 200
        if (responseHeaders.status != 200) {
          // Send bad request to client
          println(">>>> 3 " + resultPromise.isCompleted)
          resultPromise.success(BadRequest("Server responded with " + responseHeaders.status))
          // Do not accept any data from webservice (i.e. abort)
          Future.successful(Done[Array[Byte], Unit](Unit))
        } else {
          // We want to stream the result to the client, unluckily we do not have an iteratee to stream to ... yet
          val contentPromise = Promise[Iteratee[Array[Byte], Unit]]
          // ... so we tell Play that we want to stream
          val streamResult = Ok.stream {
            contentTarget: Iteratee[Array[Byte], Unit] =>
              // ... and eventually ge our target
              contentPromise.success(contentTarget)
          }
          resultPromise.success(streamResult)
          contentPromise.future
        }
    }
    Async {
      resultPromise.future
    }
  }
}
