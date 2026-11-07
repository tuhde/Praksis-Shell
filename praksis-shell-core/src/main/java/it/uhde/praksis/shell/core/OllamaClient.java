// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** The Ollama API: {@code POST /api/chat}, with or without streaming. */
public final class OllamaClient extends HttpModel {
  public OllamaClient(Profile profile) {
    super(profile);
  }

  @Override
  public Reply chat(String system, List<Message> history, List<ToolSpec> tools)
      throws ModelException {
    JsonNode tree = post("/api/chat", body(system, history, tools, false));
    JsonNode message = tree.path("message");
    if (!message.isObject()) {
      throw new ModelException("the answer of the model has no message");
    }
    List<ToolCall> calls = new ArrayList<>();
    toolCalls(message, calls);
    return new Reply(
        message.path("content").asText(""),
        calls,
        tree.path("prompt_eval_count").asLong(0),
        tree.path("eval_count").asLong(0));
  }

  @Override
  public Reply chat(
      String system, List<Message> history, List<ToolSpec> tools, Consumer<String> onText)
      throws ModelException {
    StringBuilder text = new StringBuilder();
    List<ToolCall> calls = new ArrayList<>();
    long[] tokens = new long[2];
    postLines(
        "/api/chat",
        body(system, history, tools, true),
        line -> {
          if (line.isBlank()) {
            return;
          }
          JsonNode chunk;
          try {
            chunk = Json.MAPPER.readTree(line);
          } catch (IOException e) {
            throw new ModelException("the model server sent something that is not JSON");
          }
          if (chunk.has("error")) {
            throw new ModelException("the model server reported: " + chunk.get("error").asText());
          }
          JsonNode message = chunk.path("message");
          String piece = message.path("content").asText("");
          if (!piece.isEmpty()) {
            text.append(piece);
            onText.accept(piece);
          }
          toolCalls(message, calls);
          if (chunk.path("done").asBoolean(false)) {
            tokens[0] = chunk.path("prompt_eval_count").asLong(0);
            tokens[1] = chunk.path("eval_count").asLong(0);
          }
        });
    return new Reply(text.toString(), calls, tokens[0], tokens[1]);
  }

  private static void toolCalls(JsonNode message, List<ToolCall> into) throws ModelException {
    for (JsonNode c : message.path("tool_calls")) {
      JsonNode f = c.path("function");
      String name = f.path("name").asText("");
      if (name.isEmpty()) {
        throw new ModelException("the model asked for a tool without a name");
      }
      Map<String, Object> args =
          f.path("arguments").isObject()
              ? Json.map(f.get("arguments").toString())
              : f.path("arguments").isTextual() ? Json.map(f.get("arguments").asText()) : Map.of();
      into.add(new ToolCall("call-" + UUID.randomUUID(), name, args));
    }
  }

  private String body(String system, List<Message> history, List<ToolSpec> tools, boolean stream)
      throws ModelException {
    List<Object> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", system));
    for (Message m : history) {
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("role", m.role());
      o.put("content", m.content());
      if (!m.toolCalls().isEmpty()) {
        o.put(
            "tool_calls",
            m.toolCalls().stream()
                .map(c -> Map.of("function", Map.of("name", c.name(), "arguments", c.arguments())))
                .toList());
      }
      if (m.role().equals("tool")) {
        o.put("tool_name", m.toolName());
      }
      messages.add(o);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", profile.model());
    body.put("messages", messages);
    body.put("stream", stream);
    if (!tools.isEmpty()) {
      body.put("tools", tools.stream().map(OllamaClient::tool).toList());
    }
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("num_predict", profile.maxOutputTokens());
    if (profile.temperature() != null) {
      options.put("temperature", profile.temperature());
    }
    body.put("options", options);
    return Json.write(body);
  }

  private static Map<String, Object> tool(ToolSpec t) {
    return Map.of(
        "type",
        "function",
        "function",
        Map.of("name", t.name(), "description", t.description(), "parameters", t.schema()));
  }
}
