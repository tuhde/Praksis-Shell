// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import java.util.List;

/** A model that can be asked for the next message of a conversation. */
public interface ModelClient {
  /**
   * What the model said.
   *
   * @param text its text (may be empty when it only calls tools)
   * @param toolCalls the tools it wants called
   * @param tokensIn the tokens it read, or 0 if the server did not say
   * @param tokensOut the tokens it wrote, or 0
   */
  record Reply(String text, List<ToolCall> toolCalls, long tokensIn, long tokensOut) {
    public Reply {
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
      text = text == null ? "" : text;
    }
  }

  /** Asks for the next message. */
  Reply chat(String system, List<Message> history, List<ToolSpec> tools) throws ModelException;

  /** Where the model runs, for the person to see: {@code local} or {@code cloud}. */
  String where();

  /** The name of the model, for the person to see. */
  String name();
}
