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
  record Seen(String path, String body) {}

  final List<Seen> seen = new CopyOnWriteArrayList<>();
  private final HttpServer server;

  FakeServer(int status, String answer) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          seen.add(
              new Seen(
                  exchange.getRequestURI().getPath(),
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          byte[] out = answer.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
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
