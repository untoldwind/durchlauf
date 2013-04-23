package ws

import play.api.libs.ws.ResponseHeaders

case class Response(headers: ResponseHeaders, body: String) {

}
