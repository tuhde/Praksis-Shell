// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import java.util.List;
import java.util.Map;

/** The tools that the model may call. In the shell they come from the MCP server. */
public interface ToolBridge extends AutoCloseable {
  /**
   * The answer of a tool.
   *
   * @param text what it said
   * @param error whether it is an error message
   */
  record Result(String text, boolean error) {}

  List<ToolSpec> tools();

  /**
   * Calls a tool; an unknown tool or a refusal is a result with {@code error}, not an exception.
   */
  Result call(String name, Map<String, Object> arguments);

  @Override
  void close();
}
