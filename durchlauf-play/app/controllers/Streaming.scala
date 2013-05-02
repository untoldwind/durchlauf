package controllers

import play.api.mvc.{EssentialAction, Result, Action, Controller}
import ws.AsyncWS
import scala.concurrent.{Future, Promise}
import play.api.libs.iteratee.{Iteratee, Done}
import play.api.libs.concurrent.Execution.Implicits._
import java.nio.charset.Charset

/**
 * True NIO streaming solution.
 */
object Streaming extends Controller {
  val baseUrl = "http://localhost:10390"

  def getDigest(size: Long) = Action {
    Async {
      AsyncWS.getStream(baseUrl + "/digest?" + size).map {
        response =>
          if (response.headers.status != 200) {
            // Drain the body
            response.body |>> Iteratee.ignore
            BadRequest("Server responded with " + response.headers.status)
          } else {
            Ok.stream(response.body)
          }
      }
    }
  }

  def postDigest = EssentialAction {
    requestHeader =>
      AsyncWS.postStreamUp(baseUrl + "/digest", requestHeader.contentType.getOrElse("text/plain"), requestHeader.charset.map(Charset.forName(_))).map {
        response =>
          if (response.headers.status != 200) {
            BadRequest("Server responded with " + response.headers.status)
          } else {
            Ok(response.body)
          }
      }
  }
}