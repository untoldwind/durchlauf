package ws

import play.api.libs.ws.ResponseHeaders
import java.nio.charset.Charset

case class CompletedResponse(headers: ResponseHeaders, mimeType: String, charset: Charset, body: Array[Byte]) {
  def bodyAsString = new String(body, charset)
}
