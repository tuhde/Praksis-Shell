// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelClientsTest {
  private static final ToolSpec TOOL =
      new ToolSpec(
          "praksis_status", "The state", Map.of("type", "object", "properties", Map.of()), false);

  private static final List<Message> HISTORY =
      List.of(
          Message.user("how is it?"),
          Message.assistant("", List.of(new ToolCall("c1", "praksis_status", Map.of("limit", 3)))),
          Message.tool(new ToolCall("c1", "praksis_status", Map.of()), "{\"rules\":7}"));

  private static JsonNode json(String s) throws Exception {
    return Json.MAPPER.readTree(s);
  }

  @Test
  void ollamaGetsMessagesToolsAndOptionsAndAnswersTextAndToolCalls() throws Exception {
    String answer =
        """
        {"message":{"role":"assistant","content":"Let me look.",
         "tool_calls":[{"function":{"name":"praksis_status","arguments":{"limit":3}}}]},
         "prompt_eval_count":11,"eval_count":5,"done":true}
        """;
    try (var server = new FakeServer(200, answer)) {
      ModelClient.Reply r =
          new OllamaClient(server.profile("ollama")).chat("SYS", HISTORY, List.of(TOOL));
      assertThat(r.text()).isEqualTo("Let me look.");
      assertThat(r.toolCalls()).hasSize(1);
      assertThat(r.toolCalls().get(0).name()).isEqualTo("praksis_status");
      assertThat(r.toolCalls().get(0).arguments()).containsEntry("limit", 3);
      assertThat(r.toolCalls().get(0).id()).startsWith("call-");
      assertThat(r.tokensIn()).isEqualTo(11);
      assertThat(r.tokensOut()).isEqualTo(5);

      assertThat(server.seen.get(0).path()).isEqualTo("/api/chat");
      JsonNode body = json(server.seen.get(0).body());
      assertThat(body.get("model").asText()).isEqualTo("m1");
      assertThat(body.get("stream").asBoolean()).isFalse();
      assertThat(body.at("/options/num_predict").asInt()).isEqualTo(100);
      assertThat(body.at("/options/temperature").asDouble()).isEqualTo(0.2);
      assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
      assertThat(body.at("/messages/0/content").asText()).isEqualTo("SYS");
      assertThat(body.at("/messages/2/tool_calls/0/function/name").asText())
          .isEqualTo("praksis_status");
      assertThat(body.at("/messages/3/role").asText()).isEqualTo("tool");
      assertThat(body.at("/messages/3/tool_name").asText()).isEqualTo("praksis_status");
      assertThat(body.at("/tools/0/function/name").asText()).isEqualTo("praksis_status");
    }
  }

  @Test
  void openAiGetsArgumentsAsAStringAndAnswersLikeIt() throws Exception {
    String answer =
        """
        {"choices":[{"message":{"role":"assistant","content":null,
         "tool_calls":[{"id":"abc","type":"function",
           "function":{"name":"praksis_status","arguments":"{\\"limit\\":3}"}}]}}],
         "usage":{"prompt_tokens":20,"completion_tokens":7}}
        """;
    try (var server = new FakeServer(200, answer)) {
      ModelClient.Reply r =
          new OpenAiClient(server.profile("openai")).chat("SYS", HISTORY, List.of(TOOL));
      assertThat(r.text()).isEmpty();
      assertThat(r.toolCalls()).hasSize(1);
      assertThat(r.toolCalls().get(0).id()).isEqualTo("abc");
      assertThat(r.toolCalls().get(0).arguments()).containsEntry("limit", 3);
      assertThat(r.tokensIn()).isEqualTo(20);
      assertThat(r.tokensOut()).isEqualTo(7);

      assertThat(server.seen.get(0).path()).isEqualTo("/v1/chat/completions");
      JsonNode body = json(server.seen.get(0).body());
      assertThat(body.get("max_tokens").asInt()).isEqualTo(100);
      assertThat(body.at("/messages/2/tool_calls/0/id").asText()).isEqualTo("c1");
      assertThat(body.at("/messages/2/tool_calls/0/function/arguments").asText())
          .isEqualTo("{\"limit\":3}");
      assertThat(body.at("/messages/3/tool_call_id").asText()).isEqualTo("c1");
    }
  }

  @Test
  void aPlainAnswerHasNoToolCalls() throws Exception {
    try (var server =
        new FakeServer(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"Done.\"}}")) {
      ModelClient.Reply r =
          new OllamaClient(server.profile("ollama")).chat("S", HISTORY, List.of());
      assertThat(r.text()).isEqualTo("Done.");
      assertThat(r.toolCalls()).isEmpty();
      assertThat(json(server.seen.get(0).body()).has("tools")).isFalse();
    }
  }

  @Test
  void errorsSayWhatWentWrongWithoutDumpingTheBody() throws Exception {
    try (var server = new FakeServer(404, "{\"error\":\"model 'm1' not found\"}")) {
      assertThatThrownBy(
              () -> new OllamaClient(server.profile("ollama")).chat("S", HISTORY, List.of()))
          .isInstanceOf(ModelException.class)
          .hasMessageContaining("404")
          .hasMessageContaining("model 'm1' not found");
    }
    try (var server = new FakeServer(200, "<html>nope</html>")) {
      assertThatThrownBy(
              () -> new OpenAiClient(server.profile("openai")).chat("S", HISTORY, List.of()))
          .isInstanceOf(ModelException.class)
          .hasMessageContaining("without JSON");
    }
    try (var server = new FakeServer(200, "{\"message\":{\"tool_calls\":[{\"function\":{}}]}}")) {
      assertThatThrownBy(
              () -> new OllamaClient(server.profile("ollama")).chat("S", HISTORY, List.of()))
          .hasMessageContaining("without a name");
    }
    Profile down = Profile.of("d", "ollama", "http://127.0.0.1:1", "m", "local", 2000, 100, null);
    assertThatThrownBy(() -> new OllamaClient(down).chat("S", HISTORY, List.of()))
        .hasMessageContaining("cannot reach the model");
  }

  @Test
  void theFactoryMakesTheClientOfTheApi() {
    assertThat(
            Models.of(
                Profile.of("a", "ollama", "http://127.0.0.1:1", "m", "local", null, null, null)))
        .isInstanceOf(OllamaClient.class);
    assertThat(
            Models.of(
                Profile.of("a", "openai", "http://127.0.0.1:1", "m", "local", null, null, null)))
        .isInstanceOf(OpenAiClient.class);
    assertThat(
            Models.of(
                    Profile.of("a", "openai", "http://127.0.0.1:1", "m", "local", null, null, null))
                .where())
        .isEqualTo("local");
  }
}
