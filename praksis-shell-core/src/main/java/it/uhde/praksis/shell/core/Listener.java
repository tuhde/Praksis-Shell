// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

/** What a front end shows and asks while a session runs. */
public interface Listener {
  /** The model said something to the person. */
  void text(String text);

  /** A tool is about to be called (shown to the person before it runs). */
  void toolCall(ToolCall call, boolean writes);

  /** A tool answered. */
  void toolResult(ToolCall call, ToolBridge.Result result);

  /** Something the person should know: a limit was reached, a call was declined. */
  void note(String text);

  /**
   * Asks the person whether a tool that may change something may be called. The model does not see
   * this question and cannot answer it.
   */
  boolean confirm(ToolCall call);
}
