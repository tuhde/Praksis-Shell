// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A model server on loopback that answers every POST with a fixed body. */
final class FakeServer implements AutoCloseable {
  record Seen(String path, String body, String apiKey, String authorization, String version) {}

  final List<Seen> seen = new CopyOnWriteArrayList<>();
  private final HttpServer server;

  FakeServer(int status, String answer) throws IOException {
    this(status, answer, "application/json", 0);
  }

  FakeServer(int status, String answer, String contentType) throws IOException {
    this(status, answer, contentType, 0);
  }

  /** Sends {@code first}, then says nothing for {@code stallMillis}: an answer that stops. */
  static FakeServer stalling(String first, long stallMillis) throws IOException {
    return new FakeServer(200, first, "application/x-ndjson", stallMillis);
  }

  private FakeServer(int status, String answer, String contentType, long stallMillis)
      throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          seen.add(
              new Seen(
                  exchange.getRequestURI().getPath(),
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                  exchange.getRequestHeaders().getFirst("x-api-key"),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  exchange.getRequestHeaders().getFirst("anthropic-version")));
          byte[] out = answer.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", contentType);
          if (stallMillis > 0) {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(out);
            exchange.getResponseBody().flush();
            try {
              Thread.sleep(stallMillis);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            exchange.close();
            return;
          }
          exchange.sendResponseHeaders(status, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  URI base() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  Profile profile(String api) {
    return Profile.of("t", api, base().toString(), "m1", "local", 5000, 100, 0.2);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
