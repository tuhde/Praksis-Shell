// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import java.util.List;

/**
 * One message of the conversation.
 *
 * @param role {@code user}, {@code assistant} or {@code tool}
 * @param content the text
 * @param toolCalls what an assistant message asks for (else empty)
 * @param toolCallId for a {@code tool} message, the id of the call it answers
 * @param toolName for a {@code tool} message, the name of the tool
 * @param error for a {@code tool} message, whether the result is an error
 * @param raw what the model client wants back unchanged when it sends the conversation again (for
 *     the Anthropic API the whole answer, with its thinking blocks, which must not be edited); null
 *     for a message that came from elsewhere
 */
public record Message(
    String role,
    String content,
    List<ToolCall> toolCalls,
    String toolCallId,
    String toolName,
    boolean error,
    Object raw) {
  public Message {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public static Message user(String text) {
    return new Message("user", text, List.of(), null, null, false, null);
  }

  public static Message assistant(String text, List<ToolCall> calls) {
    return assistant(text, calls, null);
  }

  public static Message assistant(String text, List<ToolCall> calls, Object raw) {
    return new Message("assistant", text, calls, null, null, false, raw);
  }

  public static Message tool(ToolCall call, String text) {
    return tool(call, text, false);
  }

  public static Message tool(ToolCall call, String text, boolean error) {
    return new Message("tool", text, List.of(), call.id(), call.name(), error, null);
  }
}
