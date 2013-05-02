package ws

import play.api.libs.ws.ResponseHeaders
import java.nio.charset.Charset
import play.api.libs.iteratee.Enumerator

/**
 * HTTP response supporting streamed processing of the body.
 * Note that the underlying HTTP connection will remain open until the body has been processed somehow.
 * Or other way round: Be sure that you actually process the body ...
 *
 * @param headers the HTTP headers of the response
 * @param mimeType the mime type of the body
 * @param charset the optional charset of the body
 * @param body [[play.api.libs.iteratee.Enumerator]] of the response body
 */
case class StreamedResponse(headers: ResponseHeaders, mimeType: String, charset: Charset, body: Enumerator[Array[Byte]]) {

}
