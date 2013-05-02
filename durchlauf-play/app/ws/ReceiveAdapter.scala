package ws

import scala.concurrent.Future
import org.apache.http.concurrent.FutureCallback
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer

/**
 * Common trait to adapt an internal [[org.apache.http.nio.protocol.HttpAsyncResponseConsumer]] to one of the more
 * convenient HTTP response objects.
 */
trait ReceiveAdapter[T] {
  /**
   * The future of the http result.
   * I.e. this is the future returned by the [[ws.AsyncWS]] methods.
   */
  def resultFuture: Future[T]

  /**
   * The [[org.apache.http.nio.protocol.HttpAsyncResponseConsumer]] receiving the NIO events of the async http client.
   */
  def responseConsumer: HttpAsyncResponseConsumer[T]

  def futureCallback: FutureCallback[T]
}
