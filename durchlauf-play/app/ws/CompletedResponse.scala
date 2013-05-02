package ws

import play.api.libs.ws.ResponseHeaders
import java.nio.charset.Charset

/**
 * HTTP response containing the already download body.
 * I.e. this response is not streamed.
 *
 * @param headers the HTTP headers of the response
 * @param mimeType the mime type of the body
 * @param charset the optional charset of the body
 * @param body the raw data of the body
 */
case class CompletedResponse(headers: ResponseHeaders, mimeType: String, charset: Charset, body: Array[Byte]) {
  /**
   * Convenient helper to convert the response body to a string.
   */
  def bodyAsString = new String(body, charset)
}
