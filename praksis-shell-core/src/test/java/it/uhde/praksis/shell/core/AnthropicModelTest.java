// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicModelTest {
  private static final String TOOL_ANSWER =
      """
      {"id":"msg_1","type":"message","role":"assistant","model":"claude-test",
       "content":[
         {"type":"thinking","thinking":"","signature":"sig123"},
         {"type":"text","text":"Let me look."},
         {"type":"tool_use","id":"toolu_1","name":"praksis_status","input":{"limit":3}},
         {"type":"tool_use","id":"toolu_2","name":"praksis_effective","input":{}}],
       "stop_reason":"tool_use","stop_sequence":null,
       "usage":{"input_tokens":11,"output_tokens":5}}
      """;

  private static final ToolSpec TOOL =
      new ToolSpec(
          "praksis_status",
          "The state",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of("limit", Map.of("type", "integer")),
              "required",
              List.of("limit"),
              "additionalProperties",
              false),
          false);

  private static AnthropicModel model(FakeServer server) {
    Profile profile =
        Profile.of(
            "c",
            "anthropic",
            "https://api.anthropic.com",
            "claude-test",
            "cloud",
            5000,
            300,
            null,
            "K",
            null);
    return new AnthropicModel(
        AnthropicOkHttpClient.builder()
            .apiKey("sk-ant-test")
            .baseUrl(server.base().toString())
            .maxRetries(0)
            .build(),
        profile);
  }

  private static JsonNode json(String s) throws Exception {
    return Json.MAPPER.readTree(s);
  }

  @Test
  void theRequestHasTheKeyTheVersionTheSystemTheToolsAndTheModel() throws Exception {
    try (var server = new FakeServer(200, TOOL_ANSWER)) {
      model(server).chat("SYS", List.of(Message.user("how is it?")), List.of(TOOL));
      FakeServer.Seen seen = server.seen.get(0);
      assertThat(seen.path()).isEqualTo("/v1/messages");
      // the SDK decides how the key travels; it must reach the server in one of the two ways
      assertThat(seen.apiKey() != null ? seen.apiKey() : seen.authorization())
          .isIn("sk-ant-test", "Bearer sk-ant-test");
      assertThat(seen.version()).isNotBlank();
      JsonNode body = json(seen.body());
      assertThat(body.get("model").asText()).isEqualTo("claude-test");
      assertThat(body.get("max_tokens").asInt()).isEqualTo(300);
      assertThat(body.get("system").asText()).isEqualTo("SYS");
      assertThat(body.at("/messages/0/role").asText()).isEqualTo("user");
      assertThat(body.at("/tools/0/name").asText()).isEqualTo("praksis_status");
      assertThat(body.at("/tools/0/input_schema/type").asText()).isEqualTo("object");
      assertThat(body.at("/tools/0/input_schema/properties/limit/type").asText())
          .isEqualTo("integer");
      assertThat(body.at("/tools/0/input_schema/required/0").asText()).isEqualTo("limit");
      assertThat(body.at("/tools/0/input_schema/additionalProperties").asBoolean(true)).isFalse();
      // no sampling parameters: the API does not accept them any more
      assertThat(body.has("temperature")).isFalse();
      assertThat(body.has("top_p")).isFalse();
    }
  }

  @Test
  void theAnswerGivesTextToolCallsAndTokens() throws Exception {
    try (var server = new FakeServer(200, TOOL_ANSWER)) {
      ModelClient.Reply r = model(server).chat("S", List.of(Message.user("x")), List.of(TOOL));
      assertThat(r.text()).isEqualTo("Let me look.");
      assertThat(r.toolCalls()).extracting(ToolCall::id).containsExactly("toolu_1", "toolu_2");
      assertThat(r.toolCalls().get(0).name()).isEqualTo("praksis_status");
      assertThat(r.toolCalls().get(0).arguments()).containsEntry("limit", 3);
      assertThat(r.tokensIn()).isEqualTo(11);
      assertThat(r.tokensOut()).isEqualTo(5);
      assertThat(r.raw()).isNotNull();
      assertThat(r.note()).isNull();
    }
  }

  @Test
  void theWholeAnswerWithItsThinkingBlockGoesBackUnchangedAndTheResultsGoBackTogether()
      throws Exception {
    try (var server = new FakeServer(200, TOOL_ANSWER)) {
      AnthropicModel m = model(server);
      ModelClient.Reply first = m.chat("S", List.of(Message.user("x")), List.of(TOOL));
      List<Message> history =
          List.of(
              Message.user("x"),
              Message.assistant(first.text(), first.toolCalls(), first.raw()),
              Message.tool(first.toolCalls().get(0), "{\"rules\":7}", false),
              Message.tool(first.toolCalls().get(1), "error: refused", true));
      m.chat("S", history, List.of(TOOL));
      JsonNode body = json(server.seen.get(1).body());
      assertThat(body.get("messages")).hasSize(3);
      JsonNode assistant = body.at("/messages/1/content");
      assertThat(assistant.get(0).get("type").asText()).isEqualTo("thinking");
      assertThat(assistant.get(0).get("signature").asText()).isEqualTo("sig123");
      assertThat(assistant.get(2).get("type").asText()).isEqualTo("tool_use");
      assertThat(assistant.get(2).get("id").asText()).isEqualTo("toolu_1");
      // both results in one user message, in the order of the calls
      JsonNode results = body.at("/messages/2");
      assertThat(results.get("role").asText()).isEqualTo("user");
      assertThat(results.get("content")).hasSize(2);
      assertThat(results.at("/content/0/type").asText()).isEqualTo("tool_result");
      assertThat(results.at("/content/0/tool_use_id").asText()).isEqualTo("toolu_1");
      assertThat(results.at("/content/0/content").asText()).isEqualTo("{\"rules\":7}");
      assertThat(results.at("/content/1/tool_use_id").asText()).isEqualTo("toolu_2");
      assertThat(results.at("/content/1/is_error").asBoolean()).isTrue();
    }
  }

  @Test
  void anAssistantMessageFromElsewhereIsSentAsTextAndToolCalls() throws Exception {
    try (var server = new FakeServer(200, TOOL_ANSWER)) {
      ToolCall call = new ToolCall("t9", "praksis_status", Map.of("limit", 2));
      model(server)
          .chat(
              "S",
              List.of(
                  Message.user("x"),
                  Message.assistant("Looking.", List.of(call)),
                  Message.tool(call, "ok", false)),
              List.of(TOOL));
      JsonNode body = json(server.seen.get(0).body());
      assertThat(body.at("/messages/1/content/0/text").asText()).isEqualTo("Looking.");
      assertThat(body.at("/messages/1/content/1/type").asText()).isEqualTo("tool_use");
      assertThat(body.at("/messages/1/content/1/input/limit").asInt()).isEqualTo(2);
      assertThat(body.at("/messages/2/content/0/tool_use_id").asText()).isEqualTo("t9");
    }
  }

  @Test
  void aRefusalAndACutAnswerAreNotesForThePerson() throws Exception {
    String refusal =
        """
        {"id":"m","type":"message","role":"assistant","model":"c","content":[],
         "stop_reason":"refusal","stop_sequence":null,
         "stop_details":{"type":"refusal","category":"cyber","explanation":"x"},
         "usage":{"input_tokens":1,"output_tokens":0}}
        """;
    try (var server = new FakeServer(200, refusal)) {
      ModelClient.Reply r = model(server).chat("S", List.of(Message.user("x")), List.of());
      assertThat(r.note()).contains("declined").contains("cyber");
    }
    String cut =
        """
        {"id":"m","type":"message","role":"assistant","model":"c",
         "content":[{"type":"text","text":"partial"}],"stop_reason":"max_tokens",
         "stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":300}}
        """;
    try (var server = new FakeServer(200, cut)) {
      ModelClient.Reply r = model(server).chat("S", List.of(Message.user("x")), List.of());
      assertThat(r.text()).isEqualTo("partial");
      assertThat(r.note()).contains("cut").contains("300");
    }
  }

  @Test
  void errorsSayWhatWentWrongWithoutTheKey() throws Exception {
    record Case(int status, String body, String expected) {}
    for (Case c :
        List.of(
            new Case(
                401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}",
                "refused the key"),
            new Case(
                429,
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}",
                "limiting requests"),
            new Case(
                529,
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}",
                "overloaded"),
            new Case(
                400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"model: not found\"}}",
                "400: model: not found"))) {
      try (var server = new FakeServer(c.status(), c.body())) {
        assertThatThrownBy(() -> model(server).chat("S", List.of(Message.user("x")), List.of()))
            .as("status " + c.status())
            .isInstanceOf(ModelException.class)
            .hasMessageContaining(c.expected())
            .message()
            .doesNotContain("sk-ant-test");
      }
    }
  }

  @Test
  void aServerThatIsNotThereIsCannotReach() {
    Profile profile =
        Profile.of(
            "c",
            "anthropic",
            "https://api.anthropic.com",
            "claude-test",
            "cloud",
            2000,
            300,
            null,
            "K",
            null);
    var model =
        new AnthropicModel(
            AnthropicOkHttpClient.builder()
                .apiKey("sk-ant-test")
                .baseUrl("http://127.0.0.1:1")
                .maxRetries(0)
                .build(),
            profile);
    assertThatThrownBy(() -> model.chat("S", List.of(Message.user("x")), List.of()))
        .isInstanceOf(ModelException.class)
        .hasMessageContaining("cannot reach the API at api.anthropic.com");
  }

  @Test
  void itRunsInTheCloudAndSaysWhere() throws Exception {
    try (var server = new FakeServer(200, TOOL_ANSWER)) {
      assertThat(model(server).where()).isEqualTo("cloud");
      assertThat(model(server).destination()).isEqualTo("api.anthropic.com");
      assertThat(model(server).name()).isEqualTo("claude-test");
    }
  }

  private static String event(String type, String data) {
    return "event: " + type + "\ndata: " + data + "\n\n";
  }

  private static final String STREAM =
      event(
              "message_start",
              "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":11,\"output_tokens\":1}}}")
          + event(
              "content_block_start",
              "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}")
          + event(
              "content_block_delta",
              "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig123\"}}")
          + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
          + event(
              "content_block_start",
              "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
          + event(
              "content_block_delta",
              "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me \"}}")
          + event(
              "content_block_delta",
              "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"look.\"}}")
          + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
          + event(
              "content_block_start",
              "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"praksis_status\",\"input\":{}}}")
          + event(
              "content_block_delta",
              "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"limit\\\"\"}}")
          + event(
              "content_block_delta",
              "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\":3}\"}}")
          + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}")
          + event(
              "message_delta",
              "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}")
          + event("message_stop", "{\"type\":\"message_stop\"}");

  @Test
  void aStreamedAnswerGivesTextInPiecesAToolCallAndTheWholeMessageWithItsThinkingBlock()
      throws Exception {
    try (var server = new FakeServer(200, STREAM, "text/event-stream")) {
      List<String> pieces = new java.util.ArrayList<>();
      ModelClient.Reply r =
          model(server).chat("S", List.of(Message.user("x")), List.of(TOOL), pieces::add);
      assertThat(pieces).containsExactly("Let me ", "look.");
      assertThat(r.text()).isEqualTo("Let me look.");
      assertThat(r.toolCalls()).hasSize(1);
      assertThat(r.toolCalls().get(0).id()).isEqualTo("toolu_1");
      assertThat(r.toolCalls().get(0).arguments()).containsEntry("limit", 3);
      assertThat(r.tokensIn()).isEqualTo(11);
      assertThat(r.tokensOut()).isEqualTo(5);
      assertThat(json(server.seen.get(0).body()).get("stream").asBoolean()).isTrue();

      // the message that is kept has the thinking block with its signature, to be sent back as it
      // is
      var message = (com.anthropic.models.messages.Message) r.raw();
      assertThat(message.content().get(0).thinking().orElseThrow().signature()).isEqualTo("sig123");
    }
  }

  @Test
  void aStreamThatTheServerRefusesIsTheSameErrorAsWithoutStreaming() throws Exception {
    try (var server =
        new FakeServer(
            401,
            "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")) {
      assertThatThrownBy(
              () -> model(server).chat("S", List.of(Message.user("x")), List.of(), p -> {}))
          .isInstanceOf(ModelException.class)
          .hasMessageContaining("refused the key")
          .message()
          .doesNotContain("sk-ant-test");
    }
  }
}
