package de.leanovate.durchlauf.target;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.util.encoders.Hex;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.server.*;
import org.glassfish.grizzly.http.util.HttpStatus;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class NastyHttpServer {
    private static HttpServer httpServer;

    private static AtomicInteger startCounter = new AtomicInteger(0);

    private static Map<String, Boolean> postStates = new ConcurrentHashMap<String, Boolean>();

    public static void start() throws Exception {

        if (startCounter.getAndIncrement() == 0) {
            httpServer = new HttpServer();
            httpServer.addListener(new NetworkListener("local", "localhost", 10389));

            httpServer.getServerConfiguration().addHttpHandler(new DigestHttpHandler(), "/digest");
            httpServer.getServerConfiguration().addHttpHandler(new HeadersHttpHanlder(), "/headers");
            httpServer.getServerConfiguration().addHttpHandler(new PostTargetHandler(), "/posttarget");
            httpServer.getServerConfiguration().addHttpHandler(new AbortingPostTargetHandler(), "/abortingposttarget");

            httpServer.start();
        }
    }

    public static void stop() {

        if (startCounter.decrementAndGet() == 0) {
            httpServer.stop();
        }
    }

    public static Boolean getPostState(String uri) {

        return postStates.get(uri);
    }

    static class DigestHttpHandler extends HttpHandler {

        private final static byte[] CHARS =
                "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\n".getBytes();

        @Override
        public void service(final Request request, final Response response) throws Exception {

            Method method = request.getMethod();

            if (method == Method.GET) {
                handleGet(request, response);
            } else if (method == Method.POST) {
                handlePost(request, response);
            } else if (method == Method.PUT) {
                handlePut(request, response);
            } else if (method == Method.DELETE) {
                handleDelete(request, response);
            } else {
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                response.finish();
            }
        }

        private void handleGet(final Request request, final Response response) throws Exception {

            long size = Long.parseLong(request.getQueryString());
            response.setContentType("text/plain");
            response.setStatus(HttpStatus.OK_200);
            OutputStream out = response.getOutputStream();
            for (long i = 0; i < size; i++) {
                out.write(CHARS[(int) (i % CHARS.length)]);
                if (i % 1024 == 1023) {
                    response.flush();
                }
            }
            response.finish();
        }

        private void handlePost(final Request request, final Response response) throws Exception {

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("POST".getBytes());
            md.update(request.getRequestURI().getBytes());
            InputStream in = request.getInputStream();
            byte[] buffer = new byte[8192];
            int readed;

            while ((readed = in.read(buffer)) > 0) {
                md.update(buffer, 0, readed);
            }

            response.setContentType("text/plain");
            response.setStatus(HttpStatus.OK_200);
            response.getOutputStream().write(Hex.encode(md.digest()));
            response.finish();
        }

        private void handlePut(final Request request, final Response response) throws Exception {

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("PUT".getBytes());
            md.update(request.getRequestURI().getBytes());
            InputStream in = request.getInputStream();
            byte[] buffer = new byte[8192];
            int readed;

            while ((readed = in.read(buffer)) > 0) {
                md.update(buffer, 0, readed);
            }

            response.setContentType("text/plain");
            response.setStatus(HttpStatus.OK_200);
            response.getOutputStream().write(Hex.encode(md.digest()));
            response.finish();
        }

        private void handleDelete(final Request request, final Response response) throws Exception {

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("DELETE".getBytes());
            md.update(request.getRequestURI().getBytes());
            response.setContentType("text/plain");
            response.setStatus(HttpStatus.OK_200);
            response.getOutputStream().write(Hex.encode(md.digest()));
            response.finish();
        }

    }

    static class HeadersHttpHanlder extends HttpHandler {

        private ObjectMapper jsonMapper = new ObjectMapper();

        @Override
        public void service(final Request request, final Response response) throws Exception {

            Method method = request.getMethod();

            if (method == Method.GET) {
                handleGet(request, response);
            } else if (method == Method.POST) {
                handlePost(request, response);
            } else {
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                response.finish();
            }
        }

        private void handleGet(final Request request, final Response response) throws Exception {

            response.addHeader("X-Test-Header1", "0123456789");
            response.addHeader("X-Test-Header2", "0, 1, 2, 3, 4");
            response.addHeader("X-Test-Header3", "0");
            response.addHeader("X-Test-Header3", "1");
            response.addHeader("X-Test-Header3", "2");
            response.addHeader("X-Test-Header3", "3");
            response.addHeader("X-Test-Header3", "4");
            response.setStatus(HttpStatus.NO_CONTENT_204);
            response.finish();
        }

        private void handlePost(final Request request, final Response response) throws Exception {

            Map<String, List<String>> allHeaders = new HashMap<String, List<String>>();

            for (String name : request.getHeaderNames()) {
                List<String> values = new ArrayList<String>();
                for (String value : request.getHeaders(name)) {
                    values.add(value);
                }
                allHeaders.put(name, values);
            }

            response.setContentType("application/json");
            response.setStatus(HttpStatus.OK_200);
            response.getOutputStream().write(jsonMapper.writeValueAsBytes(allHeaders));
            response.finish();
        }
    }

    static class PostTargetHandler extends HttpHandler {

        @Override
        public void service(final Request request, final Response response) throws Exception {

            Method method = request.getMethod();

            if (method != Method.POST) {
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                response.finish();
                return;
            }

            postStates.put(request.getRequestURI(), false);

            try {
                InputStream in = request.getInputStream();
                byte[] buffer = new byte[8192];
                while (in.read(buffer) > 0) {
                }
                in.close();

                postStates.put(request.getRequestURI(), true);
                response.setStatus(HttpStatus.NO_CONTENT_204);
                response.finish();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    static class AbortingPostTargetHandler extends HttpHandler {

        @Override
        public void service(final Request request, final Response response) throws Exception {

            Method method = request.getMethod();

            if (method != Method.POST) {
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                response.finish();
                return;
            }

            try {
                InputStream in = request.getInputStream();
                byte[] buffer = new byte[1024];
                int readed, counter = 0;
                while ((readed = in.read(buffer)) > 0) {
                    counter += readed;
                    System.out.println(">>>>>>>>>>>>>>>>>> " + counter);
                    if (counter > 10000) {
                        in.close();
                        request.getContext().getConnection().close();
                        return;
                    }
                }
                in.close();
                postStates.put(request.getRequestURI(), true);
                response.setStatus(HttpStatus.NO_CONTENT_204);
                response.finish();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}
