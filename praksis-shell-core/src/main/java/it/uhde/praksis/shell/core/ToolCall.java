// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import java.util.Map;

/**
 * A request of the model to call a tool.
 *
 * @param id an id that the answer refers to (made up when the model API gives none)
 * @param name the tool
 * @param arguments its arguments
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {
  public ToolCall {
    arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
  }
}
