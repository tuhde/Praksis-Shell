// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What the two model clients share: one POST with a JSON body, and its answer as a tree or lines.
 */
abstract class HttpModel implements ModelClient {
  /** Takes the lines of a streamed answer one at a time. */
  @FunctionalInterface
  interface LineHandler {
    void accept(String line) throws ModelException;
  }

  private static final Object END = new Object();

  protected final Profile profile;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  HttpModel(Profile profile) {
    this.profile = profile;
  }

  @Override
  public String where() {
    return "local";
  }

  @Override
  public String name() {
    return profile.model();
  }

  private HttpRequest request(String path, String body) {
    return HttpRequest.newBuilder(profile.base().resolve(path))
        .timeout(Duration.ofMillis(profile.timeoutMillis()))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
  }

  private ModelException unreachable() {
    return new ModelException(
        "cannot reach the model at " + profile.base() + " (is the server running?)");
  }

  protected JsonNode post(String path, String body) throws ModelException {
    HttpResponse<String> answer;
    try {
      answer =
          http.send(
              request(path, body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelException("interrupted");
    } catch (IOException | RuntimeException e) {
      throw unreachable();
    }
    if (answer.statusCode() / 100 != 2) {
      throw failure(answer.statusCode(), answer.body());
    }
    JsonNode tree;
    try {
      tree = Json.MAPPER.readTree(answer.body());
    } catch (IOException e) {
      throw new ModelException(
          "the model server answered " + answer.statusCode() + " without JSON");
    }
    if (tree == null) {
      throw new ModelException("the model server sent an empty answer");
    }
    return tree;
  }

  /**
   * Sends the request and hands the lines of the answer to {@code onLine} as they arrive. If the
   * server sends nothing for as long as the timeout of the profile, the answer is given up: the
   * timeout of the request covers only the wait for the first bytes.
   */
  protected void postLines(String path, String body, LineHandler onLine) throws ModelException {
    HttpResponse<Stream<String>> response;
    try {
      response = http.send(request(path, body), HttpResponse.BodyHandlers.ofLines());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelException("interrupted");
    } catch (IOException | RuntimeException e) {
      throw unreachable();
    }
    try (Stream<String> lines = response.body()) {
      if (response.statusCode() / 100 != 2) {
        throw failure(response.statusCode(), lines.limit(200).collect(Collectors.joining("\n")));
      }
      BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
      Thread reader =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      lines.forEach(queue::add);
                      queue.add(END);
                    } catch (RuntimeException e) {
                      queue.add(e);
                    }
                  });
      while (true) {
        Object next;
        try {
          next = queue.poll(profile.timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          reader.interrupt();
          throw new ModelException("interrupted");
        }
        if (next == null) {
          reader.interrupt();
          throw new ModelException(
              "the model stopped answering (nothing for "
                  + profile.timeoutMillis() / 1000
                  + " seconds)");
        }
        if (next == END) {
          return;
        }
        if (next instanceof RuntimeException) {
          throw new ModelException("the connection to the model broke while it was answering");
        }
        onLine.accept((String) next);
      }
    }
  }

  /** What the server said when it refused: its status and its own words, if it had any. */
  protected ModelException failure(int status, String body) {
    String why = "";
    try {
      JsonNode tree = Json.MAPPER.readTree(body);
      JsonNode error = tree == null ? null : tree.get("error");
      if (error != null) {
        why = ": " + (error.isTextual() ? error.asText() : error.path("message").asText(""));
      }
    } catch (IOException e) {
      // not JSON: say only the status
    }
    return new ModelException("the model server answered " + status + why);
  }
}
