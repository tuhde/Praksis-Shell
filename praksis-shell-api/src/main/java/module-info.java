// SPDX-License-Identifier: Apache-2.0
/**
 * The contract between a program that shows a conversation with a model (the desktop program) and
 * the engine that holds it. Only JDK types cross it, so the engine and everything it needs (the MCP
 * and model clients) can be loaded in a class loader of their own.
 */
module it.uhde.praksis.shell.api {
  exports it.uhde.praksis.shell.api;
}
