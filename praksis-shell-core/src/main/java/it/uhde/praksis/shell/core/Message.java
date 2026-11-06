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
 */
public record Message(
    String role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {
  public Message {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public static Message user(String text) {
    return new Message("user", text, List.of(), null, null);
  }

  public static Message assistant(String text, List<ToolCall> calls) {
    return new Message("assistant", text, calls, null, null);
  }

  public static Message tool(ToolCall call, String text) {
    return new Message("tool", text, List.of(), call.id(), call.name());
  }
}
