package controllers

import play.api.mvc.{Action, Controller}
import ws.AsyncWS
import play.api.libs.concurrent.Execution.Implicits._
import java.nio.charset.Charset

object Simple extends Controller {

  val baseUrl = "http://localhost:10390"

  def getDigest(size: Long) = Action {
    Async {
      AsyncWS.get(baseUrl + "/digest?" + size).map {
        response =>
          if (response.headers.status != 200) {
            BadRequest("Server responded with " + response.headers.status)
          } else {
            Ok(response.body)
          }
      }
    }
  }

  def postDigest = Action(parse.raw) {
    request =>
      Async {
        AsyncWS.post(baseUrl + "/digest", request.contentType.getOrElse("text/plain"), request.charset.map(Charset.forName(_)), request.body.asBytes(10 * 1024 * 1024).get).map {
          response =>
            if (response.headers.status != 200) {
              BadRequest("Server responded with " + response.headers.status)
            } else {
              Ok(response.body)
            }
        }
      }
  }
}
