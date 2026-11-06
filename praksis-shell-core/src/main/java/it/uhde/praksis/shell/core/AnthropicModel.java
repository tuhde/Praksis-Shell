// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.OverloadedException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Anthropic API, through the official SDK. The model runs in the cloud: the person has said so
 * ({@code data = "cloud"}) and says yes once for each session before anything is sent.
 *
 * <p>The answer of the model is kept whole with its message ({@link Message#raw()}) and handed back
 * as it was when the conversation is sent again, because thinking blocks must not be edited.
 */
public final class AnthropicModel implements ModelClient {
  private final AnthropicClient client;
  private final Profile profile;

  AnthropicModel(AnthropicClient client, Profile profile) {
    this.client = client;
    this.profile = profile;
  }

  /** A client for this profile and key. */
  public static AnthropicModel of(Profile profile, String key) {
    return new AnthropicModel(
        AnthropicOkHttpClient.builder()
            .apiKey(key)
            .baseUrl(profile.base().toString())
            .timeout(Duration.ofMillis(profile.timeoutMillis()))
            .build(),
        profile);
  }

  @Override
  public String where() {
    return "cloud";
  }

  @Override
  public String name() {
    return profile.model();
  }

  @Override
  public String destination() {
    return profile.base().getHost();
  }

  @Override
  public Reply chat(String system, List<Message> history, List<ToolSpec> tools)
      throws ModelException {
    MessageCreateParams.Builder request =
        MessageCreateParams.builder()
            .model(profile.model())
            .maxTokens(profile.maxOutputTokens())
            .system(system);
    for (ToolSpec t : tools) {
      request.addTool(tool(t));
    }
    for (int i = 0; i < history.size(); i++) {
      Message m = history.get(i);
      switch (m.role()) {
        case "user" -> request.addUserMessage(m.content());
        case "assistant" -> {
          if (m.raw() instanceof com.anthropic.models.messages.Message answer) {
            request.addMessage(answer);
          } else {
            request.addMessage(assistant(m));
          }
        }
        case "tool" -> {
          // all the results of one answer go back together in one user message
          List<ContentBlockParam> results = new ArrayList<>();
          while (i < history.size() && history.get(i).role().equals("tool")) {
            Message r = history.get(i);
            results.add(
                ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                        .toolUseId(r.toolCallId())
                        .content(r.content())
                        .isError(r.error())
                        .build()));
            i++;
          }
          i--;
          request.addMessage(MessageParam.ofUserOfContentBlockParams(results));
        }
        default -> throw new ModelException("unknown role " + m.role());
      }
    }
    com.anthropic.models.messages.Message answer;
    try {
      answer = client.messages().create(request.build());
    } catch (RateLimitException e) {
      throw new ModelException("the API is limiting requests for this key; try again in a moment");
    } catch (OverloadedException e) {
      throw new ModelException("the API is overloaded; try again in a moment");
    } catch (UnauthorizedException e) {
      throw new ModelException("the API refused the key (is it valid for this organization?)");
    } catch (PermissionDeniedException e) {
      throw new ModelException("the key may not use this model or this feature");
    } catch (AnthropicServiceException e) {
      throw new ModelException("the API answered " + e.statusCode() + errorMessage(e.body()));
    } catch (AnthropicIoException e) {
      throw new ModelException("cannot reach the API at " + destination());
    } catch (AnthropicException e) {
      throw new ModelException("the API call failed: " + first(e.getMessage()));
    }
    return reply(answer);
  }

  private Reply reply(com.anthropic.models.messages.Message answer) {
    StringBuilder text = new StringBuilder();
    List<ToolCall> calls = new ArrayList<>();
    for (ContentBlock block : answer.content()) {
      block.text().ifPresent(t -> text.append(t.text()));
      block
          .toolUse()
          .ifPresent(
              u -> {
                Map<String, Object> input =
                    u._input().convert(new TypeReference<Map<String, Object>>() {});
                calls.add(new ToolCall(u.id(), u.name(), input));
              });
    }
    String note = null;
    StopReason stop = answer.stopReason().orElse(null);
    if (StopReason.REFUSAL.equals(stop)) {
      String category =
          answer.stopDetails().flatMap(d -> d.category()).map(Object::toString).orElse("unknown");
      note = "the model declined this request (category: " + category + ")";
    } else if (StopReason.MAX_TOKENS.equals(stop)) {
      note = "the answer was cut at max_output_tokens (" + profile.maxOutputTokens() + ")";
    }
    return new Reply(
        text.toString(),
        calls,
        answer.usage().inputTokens(),
        answer.usage().outputTokens(),
        answer,
        note);
  }

  /** An assistant message that did not come from this client, as text and tool calls. */
  private static MessageParam assistant(Message m) {
    List<ContentBlockParam> blocks = new ArrayList<>();
    if (!m.content().isBlank()) {
      blocks.add(
          ContentBlockParam.ofText(
              com.anthropic.models.messages.TextBlockParam.builder().text(m.content()).build()));
    }
    for (ToolCall c : m.toolCalls()) {
      Map<String, JsonValue> input = new LinkedHashMap<>();
      c.arguments().forEach((k, v) -> input.put(k, JsonValue.from(v)));
      blocks.add(
          ContentBlockParam.ofToolUse(
              ToolUseBlockParam.builder()
                  .id(c.id())
                  .name(c.name())
                  .input(ToolUseBlockParam.Input.builder().additionalProperties(input).build())
                  .build()));
    }
    return MessageParam.ofAssistantOfContentBlockParams(blocks);
  }

  /** The tool as the API wants it: name, description and the JSON schema of the arguments. */
  static Tool tool(ToolSpec spec) {
    Tool.InputSchema.Builder schema = Tool.InputSchema.builder();
    Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
    if (spec.schema().get("properties") instanceof Map<?, ?> given) {
      given.forEach((k, v) -> properties.putAdditionalProperty((String) k, JsonValue.from(v)));
    }
    schema.properties(properties.build());
    if (spec.schema().get("required") instanceof List<?> required) {
      schema.required(required.stream().map(String::valueOf).toList());
    }
    spec.schema()
        .forEach(
            (k, v) -> {
              if (!k.equals("type") && !k.equals("properties") && !k.equals("required")) {
                schema.putAdditionalProperty(k, JsonValue.from(v));
              }
            });
    return Tool.builder()
        .name(spec.name())
        .description(spec.description())
        .inputSchema(schema.build())
        .build();
  }

  private static String errorMessage(JsonValue body) {
    try {
      Map<String, Object> m = body.convert(new TypeReference<Map<String, Object>>() {});
      if (m != null
          && m.get("error") instanceof Map<?, ?> e
          && e.get("message") instanceof String s) {
        return ": " + first(s);
      }
    } catch (RuntimeException e) {
      // not the shape we know: say only the status
    }
    return "";
  }

  private static String first(String s) {
    String line = s == null ? "" : s.lines().findFirst().orElse("");
    return line.length() > 300 ? line.substring(0, 300) + "…" : line;
  }
}
