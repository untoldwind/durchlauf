package ws

import org.apache.http.nio.conn.ClientAsyncConnectionManager
import org.apache.http.impl.nio.client.DefaultHttpAsyncClient
import org.apache.http.nio.client.HttpAsyncClient
import org.apache.http.impl.nio.conn.{AsyncSchemeRegistryFactory, PoolingClientAsyncConnectionManager}
import org.apache.http.impl.nio.reactor.{IOReactorConfig, DefaultConnectingIOReactor}
import org.apache.http.client.methods._
import scala.concurrent.{ExecutionContext, Future}
import org.apache.http.nio.client.methods.HttpAsyncMethods
import scala.concurrent.stm.Ref
import java.nio.charset.Charset
import org.apache.http.entity.{ContentType, ByteArrayEntity}
import play.api.libs.iteratee.{Done, Iteratee}
import scala.Some

/**
 * Alternative "WS" implementation using the apacha async http client.
 */
object AsyncWS {
  /**
   * Internal holder of the underlying implementation.
   */
  private val httpClientHolder = Ref(Option.empty[HttpAsyncClient])

  /**
   * Obtain the underlying implementation.
   * If necessary create one, but ensure that only one is actively used.
   */
  def httpClient: HttpAsyncClient = {
    httpClientHolder.single.get.getOrElse {
      var newHttpClient = new DefaultHttpAsyncClient(connectionManager)

      if (httpClientHolder.single.compareAndSet(None, Some(newHttpClient))) {
        newHttpClient.start()
        newHttpClient
      } else {
        httpClientHolder.single.get.get
      }
    }
  }

  /**
   * Perform a HTTP GET without streaming.
   *
   * @param url the URL to be invoked
   * @return the http response (containing the entire body.
   */
  def get(url: String): Future[CompletedResponse] = {
    execute(new HttpGet(url), CompletedResponseReceiveAdapter())
  }

  /**
   * Perform a HTTP GET with streamed response.
   *
   * @param url the URL to be invoked
   * @param executor implicit execution context
   * @return the http response supporting streaming of its content.
   */
  def getStream(url: String)(implicit executor: ExecutionContext): Future[StreamedResponse] = {
    execute(new HttpGet(url), StreamedResponseReceiveAdapter())
  }

  /**
   * Perform a HTTP POST without streaming.
   *
   * @param url the URL to be invoked
   * @param mimeType the mime type of the content to be posted
   * @param charset the optional charset of the content to be posted
   * @param data the data to be posted
   * @return the http response (containing the entire body.
   */
  def post(url: String, mimeType: String, charset: Option[Charset], data: Array[Byte]): Future[CompletedResponse] = {
    val method = new HttpPost(url)
    val contentType = charset.map(ContentType.create(mimeType, _)).getOrElse(ContentType.create(mimeType))
    method.setEntity(new ByteArrayEntity(data, contentType))
    execute(method, CompletedResponseReceiveAdapter())
  }

  /**
   * Perform a HTTP POST with streamed response.
   *
   * @param url the URL to be invoked
   * @param mimeType the mime type of the content to be posted
   * @param charset the optional charset of the content to be posted
   * @param data the data to be posted
   * @return the http response (containing the entire body.
   */
  def postStreamDown(url: String, mimeType: String, charset: Option[Charset], data: Array[Byte])(implicit executor: ExecutionContext): Future[StreamedResponse] = {
    val method = new HttpPost(url)
    val contentType = charset.map(ContentType.create(mimeType, _)).getOrElse(ContentType.create(mimeType))
    method.setEntity(new ByteArrayEntity(data, contentType))
    execute(method, StreamedResponseReceiveAdapter())
  }

  /**
   * Perform a HTTP POST with streamed request.
   *
   * @param url the URL to be invoked
   * @param mimeType the mime type of the content to be posted
   * @param charset the optional charset of the content to be posted
   * @param executor implicit execution context
   * @return an iteratee accepting the content to be posted and eventually containing the http response.
   */
  def postStreamUp(url: String, mimeType: String, charset: Option[Charset])(implicit executor: ExecutionContext): Iteratee[Array[Byte], CompletedResponse] = {
    val method = new HttpPost(url)
    val contentType = charset.map(ContentType.create(mimeType, _)).getOrElse(ContentType.create(mimeType))
    val receiveAdapter = CompletedResponseReceiveAdapter()
    val sendAdapter = StreamedSendAdapter(contentType)

    method.setEntity(sendAdapter.httpEntity)

    execute(method, receiveAdapter)

    sendAdapter.iteratee.flatMap {
      _ =>
        Iteratee.flatten(receiveAdapter.resultFuture.map(Done(_)))
    }
  }

  def put(url: String, mimeType: String, charset: Option[Charset], data: Array[Byte]): Future[CompletedResponse] = {
    val method = new HttpPut(url)
    val contentType = charset.map(ContentType.create(mimeType, _)).getOrElse(ContentType.create(mimeType))
    method.setEntity(new ByteArrayEntity(data, contentType))
    execute(method, CompletedResponseReceiveAdapter())
  }

  def putStreamDown(url: String, mimeType: String, charset: Option[Charset], data: Array[Byte])(implicit executor: ExecutionContext): Future[StreamedResponse] = {
    val method = new HttpPost(url)
    val contentType = charset.map(ContentType.create(mimeType, _)).getOrElse(ContentType.create(mimeType))
    method.setEntity(new ByteArrayEntity(data, contentType))
    execute(method, StreamedResponseReceiveAdapter())
  }

  def delete(url: String): Future[CompletedResponse] = {
    execute(new HttpDelete(url), CompletedResponseReceiveAdapter())
  }

  private def execute[T](request: HttpUriRequest, receiveAdapter: ReceiveAdapter[T]): Future[T] = {

    httpClient.execute(HttpAsyncMethods.create(request), receiveAdapter.responseConsumer, receiveAdapter.futureCallback)

    receiveAdapter.resultFuture
  }

  private def connectionManager: ClientAsyncConnectionManager = {
    val ioReactorConfig = new IOReactorConfig()
    val ioReactor = new DefaultConnectingIOReactor(ioReactorConfig)
    val schemeRegistry = AsyncSchemeRegistryFactory.createDefault

    val connectionManager = new PoolingClientAsyncConnectionManager(ioReactor, schemeRegistry) {
      override def shutdown() {
        super.shutdown()
      }
    }

    val playConfig = play.api.Play.maybeApplication.map(_.configuration)

    val maxTotal = playConfig.flatMap(_.getInt("asyncWS.maxTotalConnections")).getOrElse(20)
    connectionManager.setMaxTotal(maxTotal)
    val maxConnectionsPerHost = playConfig.flatMap(_.getInt("asyncWS.maxConnectionsPerHost")).getOrElse(10)
    connectionManager.setDefaultMaxPerRoute(maxConnectionsPerHost)

    connectionManager
  }
}