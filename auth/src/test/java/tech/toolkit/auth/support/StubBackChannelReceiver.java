package tech.toolkit.auth.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A client's back-channel logout endpoint: records the notices it receives. */
public final class StubBackChannelReceiver implements AutoCloseable {

  private final HttpServer server;
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private volatile int status = 200;
  private volatile long delayMillis = 0;

  public StubBackChannelReceiver() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/logout",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          try {
            if (delayMillis > 0) {
              Thread.sleep(delayMillis);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/logout";
  }

  public StubBackChannelReceiver respondWith(int status) {
    this.status = status;
    return this;
  }

  public StubBackChannelReceiver delayResponsesBy(long millis) {
    this.delayMillis = millis;
    return this;
  }

  /** The {@code logout_token} values received so far. */
  public List<String> logoutTokens() {
    return bodies.stream()
        .map(
            body ->
                body.startsWith("logout_token=") ? body.substring("logout_token=".length()) : body)
        .map(value -> URLDecoder.decode(value, StandardCharsets.UTF_8))
        .toList();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
