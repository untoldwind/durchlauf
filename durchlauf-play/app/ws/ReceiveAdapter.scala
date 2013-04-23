package ws

import scala.concurrent.Future
import org.apache.http.concurrent.FutureCallback
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer

trait ReceiveAdapter[T] {
  def resultFuture: Future[T]

  def responseConsumer: HttpAsyncResponseConsumer[T]

  def futureCallback: FutureCallback[T]
}
