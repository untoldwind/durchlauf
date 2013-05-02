package controllers

import play.api.mvc.{Controller, Action}
import play.api.libs.ws.WS
import play.api.libs.iteratee.Enumerator
import play.api.libs.concurrent.Execution.Implicits._

/**
 * Simplest solution using play standards.
 */
object Simplest extends Controller {
  def getDigestFull(size: Long) = Action {
    Async {
      WS.url("http://localhost:10390/digest?" + size).get().map {
        response =>
          if (response.status != 200) {
            BadRequest("Server responded with " + response.status)
          } else {
            Ok(response.body)
          }
      }
    }
  }

  def getDigestStream(size: Long) = Action {
    Async {
      WS.url("http://localhost:10390/digest?" + size).get().map {
        response =>
          if (response.status != 200) {
            BadRequest("Server responded with " + response.status)
          } else {
            Ok.stream(Enumerator.fromStream(response.ahcResponse.getResponseBodyAsStream))
          }
      }
    }
  }

  def postDigest = Action(parse.temporaryFile) {
    request =>
      Async {
        WS.url("http://localhost:10390/digest").post(request.body.file).map {
          response =>
            if (response.status != 200) {
              BadRequest("Server responded with " + response.status)
            } else {
              Ok(response.body)
            }
        }
      }
  }
}
