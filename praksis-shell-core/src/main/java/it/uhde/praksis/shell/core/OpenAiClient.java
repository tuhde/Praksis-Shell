// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The OpenAI interface that llama.cpp, vLLM, LM Studio and others offer: chat completions. */
public final class OpenAiClient extends HttpModel {
  public OpenAiClient(Profile profile) {
    super(profile);
  }

  @Override
  public Reply chat(String system, List<Message> history, List<ToolSpec> tools)
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
    if (!tools.isEmpty()) {
      body.put("tools", tools.stream().map(OpenAiClient::tool).toList());
    }
    JsonNode tree = post("/v1/chat/completions", Json.write(body));
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

  private static Map<String, Object> tool(ToolSpec t) {
    return Map.of(
        "type",
        "function",
        "function",
        Map.of("name", t.name(), "description", t.description(), "parameters", t.schema()));
  }
}
