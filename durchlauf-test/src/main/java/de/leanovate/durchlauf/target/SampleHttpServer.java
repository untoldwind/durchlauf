package de.leanovate.durchlauf.target;

import org.bouncycastle.util.encoders.Hex;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicInteger;

public class SampleHttpServer {
    private static AtomicInteger startCounter = new AtomicInteger(0);

    public static void start() throws Exception {

        if (startCounter.getAndIncrement() == 0) {
            Server server = new Server(10390);

            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            context.addServlet(new ServletHolder(new DigestServlet()), "/digest");
            context.setClassLoader(Thread.currentThread().getContextClassLoader());
            server.setHandler(context);

            server.start();
            server.join();
        }
    }

    static class DigestServlet extends HttpServlet {
        private final static byte[] CHARS =
                "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\n".getBytes();

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            long size = Long.parseLong(req.getQueryString());
            resp.setContentType("text/plain");
            resp.setStatus(HttpServletResponse.SC_OK);
            OutputStream out = resp.getOutputStream();
            for (long i = 0; i < size; i++) {
                out.write(CHARS[(int) (i % CHARS.length)]);
                if (i % 1024 == 1023) {
                    out.flush();
                }
            }
            out.close();
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update("POST".getBytes());
                md.update(req.getRequestURI().getBytes());
                InputStream in = req.getInputStream();
                byte[] buffer = new byte[8192];
                int readed;

                while ((readed = in.read(buffer)) > 0) {
                    md.update(buffer, 0, readed);
                }

                resp.setContentType("text/plain");
                resp.setStatus(HttpServletResponse.SC_OK);
                OutputStream out = resp.getOutputStream();
                out.write(Hex.encode(md.digest()));
                out.close();
            } catch (NoSuchAlgorithmException e) {
                throw new ServletException(e);
            }
        }
    }
}