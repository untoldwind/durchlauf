package ws

import play.api.libs.ws.ResponseHeaders
import java.nio.charset.Charset

case class FullResponse(headers: ResponseHeaders, mimeType: String, charset: Charset, body: Array[Byte]) {
  def bodyAsString = new String(body, charset)
}
