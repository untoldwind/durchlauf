package controllers

import play.api.mvc.{Result, Action, Controller}
import ws.AsyncWS
import scala.concurrent.{Future, Promise}
import play.api.libs.iteratee.{Iteratee, Done}
import play.api.libs.concurrent.Execution.Implicits._

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
}