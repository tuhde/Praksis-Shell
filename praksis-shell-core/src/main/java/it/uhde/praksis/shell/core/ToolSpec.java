// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import java.util.Map;

/**
 * A tool as the model sees it.
 *
 * @param name its name
 * @param description what it does
 * @param schema the JSON schema of its arguments
 * @param writes whether it may change something (not marked read-only by the server)
 */
public record ToolSpec(
    String name, String description, Map<String, Object> schema, boolean writes) {}
