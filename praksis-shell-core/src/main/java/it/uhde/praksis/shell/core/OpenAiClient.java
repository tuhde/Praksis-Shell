// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The OpenAI interface that llama.cpp, vLLM, LM Studio and others offer: chat completions, with or
 * without streaming (server-sent events).
 */
public final class OpenAiClient extends HttpModel {
  public OpenAiClient(Profile profile) {
    super(profile);
  }

  @Override
  public Reply chat(String system, List<Message> history, List<ToolSpec> tools)
      throws ModelException {
    JsonNode tree = post("/v1/chat/completions", body(system, history, tools, false));
    JsonNode message = tree.path("choices").path(0).path("message");
    if (!message.isObject()) {
      throw new ModelException("the answer of the model has no message");
    }
    List<ToolCall> calls = new ArrayList<>();
    int n = 0;
    for (JsonNode c : message.path("tool_calls")) {
      JsonNode f = c.path("function");
      String name = f.path("name").asText("");
      if (name.isEmpty()) {
        throw new ModelException("the model asked for a tool without a name");
      }
      String id = c.path("id").asText("call-" + (++n));
      // the arguments are JSON in a string; an object is accepted too, as some servers send it
      Map<String, Object> args =
          f.path("arguments").isTextual()
              ? Json.map(f.get("arguments").asText())
              : f.path("arguments").isObject() ? Json.map(f.get("arguments").toString()) : Map.of();
      calls.add(new ToolCall(id, name, args));
    }
    return new Reply(
        message.path("content").asText(""),
        calls,
        tree.path("usage").path("prompt_tokens").asLong(0),
        tree.path("usage").path("completion_tokens").asLong(0));
  }

  /**
   * A tool call that arrives in pieces: the name once, the arguments a few characters at a time.
   */
  private static final class Partial {
    String id;
    String name = "";
    final StringBuilder arguments = new StringBuilder();
  }

  @Override
  public Reply chat(
      String system, List<Message> history, List<ToolSpec> tools, Consumer<String> onText)
      throws ModelException {
    StringBuilder text = new StringBuilder();
    Map<Integer, Partial> partial = new TreeMap<>();
    long[] tokens = new long[2];
    postLines(
        "/v1/chat/completions",
        body(system, history, tools, true),
        line -> {
          String data = line.startsWith("data:") ? line.substring(5).strip() : "";
          if (data.isEmpty() || data.equals("[DONE]")) {
            return;
          }
          JsonNode chunk;
          try {
            chunk = Json.MAPPER.readTree(data);
          } catch (IOException e) {
            throw new ModelException("the model server sent something that is not JSON");
          }
          if (chunk.has("error")) {
            JsonNode e = chunk.get("error");
            throw new ModelException(
                "the model server reported: "
                    + (e.isTextual() ? e.asText() : e.path("message").asText("")));
          }
          if (chunk.path("usage").isObject()) {
            tokens[0] = chunk.path("usage").path("prompt_tokens").asLong(0);
            tokens[1] = chunk.path("usage").path("completion_tokens").asLong(0);
          }
          JsonNode delta = chunk.path("choices").path(0).path("delta");
          String piece = delta.path("content").asText("");
          if (!piece.isEmpty()) {
            text.append(piece);
            onText.accept(piece);
          }
          for (JsonNode c : delta.path("tool_calls")) {
            Partial p = partial.computeIfAbsent(c.path("index").asInt(0), k -> new Partial());
            if (c.has("id")) {
              p.id = c.get("id").asText();
            }
            JsonNode f = c.path("function");
            if (f.has("name")) {
              p.name += f.get("name").asText();
            }
            if (f.has("arguments")) {
              p.arguments.append(f.get("arguments").asText());
            }
          }
        });
    List<ToolCall> calls = new ArrayList<>();
    int n = 0;
    for (Partial p : partial.values()) {
      if (p.name.isEmpty()) {
        throw new ModelException("the model asked for a tool without a name");
      }
      calls.add(
          new ToolCall(
              p.id != null ? p.id : "call-" + (++n),
              p.name,
              p.arguments.length() == 0 ? Map.of() : Json.map(p.arguments.toString())));
    }
    return new Reply(text.toString(), calls, tokens[0], tokens[1]);
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
        List<Object> calls = new ArrayList<>();
        for (ToolCall c : m.toolCalls()) {
          calls.add(
              Map.of(
                  "id",
                  c.id(),
                  "type",
                  "function",
                  "function",
                  Map.of("name", c.name(), "arguments", Json.write(c.arguments()))));
        }
        o.put("tool_calls", calls);
      }
      if (m.role().equals("tool")) {
        o.put("tool_call_id", m.toolCallId());
      }
      messages.add(o);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", profile.model());
    body.put("messages", messages);
    body.put("max_tokens", profile.maxOutputTokens());
    if (profile.temperature() != null) {
      body.put("temperature", profile.temperature());
    }
    if (stream) {
      body.put("stream", true);
      body.put("stream_options", Map.of("include_usage", true));
    }
    if (!tools.isEmpty()) {
      body.put("tools", tools.stream().map(OpenAiClient::tool).toList());
    }
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
