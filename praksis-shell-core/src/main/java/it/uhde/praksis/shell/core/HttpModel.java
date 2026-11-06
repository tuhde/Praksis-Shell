// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** What the two model clients share: one POST with a JSON body, and its answer as a tree. */
abstract class HttpModel implements ModelClient {
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

  protected JsonNode post(String path, String body) throws ModelException {
    URI uri = profile.base().resolve(path);
    HttpResponse<String> answer;
    try {
      answer =
          http.send(
              HttpRequest.newBuilder(uri)
                  .timeout(Duration.ofMillis(profile.timeoutMillis()))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelException("interrupted");
    } catch (IOException | RuntimeException e) {
      throw new ModelException(
          "cannot reach the model at " + profile.base() + " (is the server running?)");
    }
    JsonNode tree;
    try {
      tree = Json.MAPPER.readTree(answer.body());
    } catch (IOException e) {
      throw new ModelException(
          "the model server answered " + answer.statusCode() + " without JSON");
    }
    if (answer.statusCode() / 100 != 2) {
      JsonNode error = tree == null ? null : tree.get("error");
      String why =
          error == null
              ? ""
              : ": " + (error.isTextual() ? error.asText() : error.path("message").asText(""));
      throw new ModelException("the model server answered " + answer.statusCode() + why);
    }
    if (tree == null) {
      throw new ModelException("the model server sent an empty answer");
    }
    return tree;
  }
}
