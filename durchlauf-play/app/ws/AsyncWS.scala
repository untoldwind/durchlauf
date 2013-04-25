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

object AsyncWS {
  private val httpClientHolder = Ref(Option.empty[HttpAsyncClient])

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

  def get(url: String): Future[CompletedResponse] = {
    execute(new HttpGet(url), CompletedResponseReceiveAdapter())
  }

  def getStream(url: String)(implicit executor: ExecutionContext): Future[StreamedResponse] = {
    execute(new HttpGet(url), StreamedResponseReceiveAdapter())
  }

  def post(url: String, mimeType: String, charset: Option[Charset], data: Array[Byte]): Future[CompletedResponse] = {
    val method = new HttpPost(url)
    val contentType = charset.map(ContentType.create(mimeType, _)).getOrElse(ContentType.create(mimeType))
    method.setEntity(new ByteArrayEntity(data, contentType))
    execute(method, CompletedResponseReceiveAdapter())
  }

  def postStreamDown(url: String, mimeType: String, charset: Option[Charset], data: Array[Byte])(implicit executor: ExecutionContext): Future[StreamedResponse] = {
    val method = new HttpPost(url)
    val contentType = charset.map(ContentType.create(mimeType, _)).getOrElse(ContentType.create(mimeType))
    method.setEntity(new ByteArrayEntity(data, contentType))
    execute(method, StreamedResponseReceiveAdapter())
  }

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