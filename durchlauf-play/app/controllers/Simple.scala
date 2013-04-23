package controllers

import play.api.mvc.{Action, Controller}
import ws.AsyncWS
import play.api.libs.concurrent.Execution.Implicits._

object Simple extends Controller {

  val baseUrl = "http://localhost:10389"

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
}
