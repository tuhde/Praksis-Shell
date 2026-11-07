// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings of the shell, from {@code shell.toml}:
 *
 * <pre>
 * default = "local"
 *
 * [profile.local]
 * api   = "ollama"                 # or "openai"
 * url   = "http://127.0.0.1:11434"
 * model = "llama3.1"
 * data  = "local"
 *
 * [mcp]
 * command   = ["java", "-jar", "/path/to/praksis-mcp.jar"]
 * url       = "http://127.0.0.1:8484"   # the web door of the daemon
 * mode      = "propose"                 # read-only, propose or commands
 * token_env = "PRAKSIS_TOKEN"           # the variable that holds the token
 *
 * [daemon]                            # where the person's own commands go (the Unix socket)
 * home     = "/home/me/.praksis"     # or identity = "private" for ~/.praksis-private
 *
 * [session]
 * max_tool_calls         = 30
 * max_turns              = 12
 * confirm_tools          = true
 * max_tokens_per_session = 200000    # default for a cloud model; none for a local one
 * </pre>
 *
 * <p>A model in the cloud is {@code api = "anthropic"}, {@code data = "cloud"} and either {@code
 * key_env = "ANTHROPIC_API_KEY"} or {@code key_command = ["kwallet-query", ...]}.
 *
 * <p>The token is never in this file: only the name of the environment variable that holds it.
 *
 * @param profiles the profiles by name
 * @param defaultProfile the name of the one to use
 * @param mcpCommand the command that starts the MCP server
 * @param mcpUrl the address of the web door
 * @param mcpMode the mode of the MCP server
 * @param tokenEnv the name of the environment variable with the token
 * @param limits the limits of a session
 * @param daemonHome the home directory of the daemon for the person's own commands, or null
 * @param daemonIdentity the identity (home {@code ~/.praksis-NAME}), or null
 */
public record ShellConfig(
    Map<String, Profile> profiles,
    String defaultProfile,
    List<String> mcpCommand,
    String mcpUrl,
    String mcpMode,
    String tokenEnv,
    Limits limits,
    String daemonHome,
    String daemonIdentity) {

  /**
   * What a session may spend.
   *
   * @param maxToolCalls tool calls in one session
   * @param maxTurns answers of the model for one request of the person
   * @param confirmTools whether the person is asked before a tool that may change something
   * @param maxTokens the tokens read and written in one session, or 0 for no limit
   */
  public record Limits(int maxToolCalls, int maxTurns, boolean confirmTools, long maxTokens) {
    public static final Limits DEFAULT = new Limits(30, 12, true, 0);

    public Limits(int maxToolCalls, int maxTurns, boolean confirmTools) {
      this(maxToolCalls, maxTurns, confirmTools, 0);
    }
  }

  /** The limit of tokens in a session for a model in the cloud, unless the configuration says. */
  public static final long CLOUD_TOKEN_LIMIT = 200_000;

  private static final TomlMapper TOML = new TomlMapper();

  public static ShellConfig load(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException(
          "there is no configuration at " + file + " (see the README for an example)");
    }
    return parse(Files.readString(file));
  }

  public static ShellConfig parse(String text) {
    JsonNode root;
    try {
      root = TOML.readTree(text);
    } catch (IOException e) {
      throw new IllegalArgumentException("the configuration is not valid TOML: " + e.getMessage());
    }
    Map<String, Profile> profiles = new LinkedHashMap<>();
    JsonNode ps = root.path("profile");
    var names = ps.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      JsonNode p = ps.get(name);
      profiles.put(
          name,
          Profile.of(
              name,
              str(p, "api"),
              str(p, "url"),
              str(p, "model"),
              str(p, "data"),
              p.has("timeout_ms") ? p.get("timeout_ms").asInt() : null,
              p.has("max_output_tokens") ? p.get("max_output_tokens").asInt() : null,
              p.has("temperature") ? p.get("temperature").asDouble() : null,
              str(p, "key_env"),
              strings(p, "key_command")));
    }
    if (profiles.isEmpty()) {
      throw new IllegalArgumentException("the configuration needs a [profile.NAME] section");
    }
    String chosen = str(root, "default");
    if (chosen == null) {
      if (profiles.size() != 1) {
        throw new IllegalArgumentException(
            "'default' must name a profile (" + String.join(", ", profiles.keySet()) + ")");
      }
      chosen = profiles.keySet().iterator().next();
    }
    if (!profiles.containsKey(chosen)) {
      throw new IllegalArgumentException(
          "'default' names no profile '"
              + chosen
              + "' ("
              + String.join(", ", profiles.keySet())
              + ")");
    }
    JsonNode mcp = root.path("mcp");
    List<String> command = new ArrayList<>();
    for (JsonNode c : mcp.path("command")) {
      command.add(c.asText());
    }
    if (command.isEmpty()) {
      throw new IllegalArgumentException(
          "[mcp] command is required: the command that starts the MCP server of Praksis");
    }
    String mode = mcp.has("mode") ? mcp.get("mode").asText() : "read-only";
    if (!List.of("read-only", "propose", "commands").contains(mode)) {
      throw new IllegalArgumentException(
          "[mcp] mode must be \"read-only\", \"propose\" or \"commands\"");
    }
    JsonNode s = root.path("session");
    long tokens =
        s.has("max_tokens_per_session")
            ? bounded(s, "max_tokens_per_session", 0, 1000, 100_000_000)
            : profiles.get(chosen).cloud() ? CLOUD_TOKEN_LIMIT : 0;
    Limits limits =
        new Limits(
            bounded(s, "max_tool_calls", 30, 1, 500),
            bounded(s, "max_turns", 12, 1, 100),
            !s.has("confirm_tools") || s.get("confirm_tools").asBoolean(true),
            tokens);
    return new ShellConfig(
        Map.copyOf(profiles),
        chosen,
        List.copyOf(command),
        mcp.has("url") ? mcp.get("url").asText() : "http://127.0.0.1:8484",
        mode,
        mcp.has("token_env") ? mcp.get("token_env").asText() : "PRAKSIS_TOKEN",
        limits,
        str(root.path("daemon"), "home"),
        str(root.path("daemon"), "identity"));
  }

  public Profile profile() {
    return profiles.get(defaultProfile);
  }

  private static List<String> strings(JsonNode n, String field) {
    if (!n.has(field)) {
      return null;
    }
    List<String> out = new ArrayList<>();
    for (JsonNode e : n.get(field)) {
      out.add(e.asText());
    }
    return out;
  }

  private static String str(JsonNode n, String field) {
    return n.has(field) && !n.get(field).isNull() ? n.get(field).asText() : null;
  }

  private static int bounded(JsonNode n, String field, int fallback, int min, int max) {
    if (!n.has(field)) {
      return fallback;
    }
    long v = n.get(field).asLong(-1);
    if (v < min || v > max) {
      throw new IllegalArgumentException(
          "[session] " + field + " must be between " + min + " and " + max);
    }
    return (int) v;
  }
}
