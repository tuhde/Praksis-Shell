// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.api;

/**
 * The engine could not be started: a missing setting, a model or an MCP server that is not there.
 */
public class EngineException extends Exception {
  private static final long serialVersionUID = 1L;

  public EngineException(String message) {
    super(message);
  }

  public EngineException(String message, Throwable cause) {
    super(message, cause);
  }
}
