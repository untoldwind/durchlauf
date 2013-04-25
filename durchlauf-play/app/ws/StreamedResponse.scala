package ws

import play.api.libs.ws.ResponseHeaders
import java.nio.charset.Charset
import play.api.libs.iteratee.Enumerator

case class StreamedResponse(headers: ResponseHeaders, mimeType: String, charset: Charset, body: Enumerator[Array[Byte]]) {

}
