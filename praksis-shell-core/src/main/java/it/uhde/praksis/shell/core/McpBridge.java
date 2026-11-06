// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The MCP server of Praksis as a source of tools: the shell starts it as a child process and speaks
 * MCP with it over standard input and output. The shell has no other way to the daemon.
 */
public final class McpBridge implements ToolBridge {
  private final McpSyncClient client;
  private final List<ToolSpec> tools;

  private McpBridge(McpSyncClient client, List<ToolSpec> tools) {
    this.client = client;
    this.tools = tools;
  }

  /** Starts the server of the configuration; the token comes from the environment, not the file. */
  public static McpBridge start(ShellConfig config, Function<String, String> environment) {
    String token = environment.apply(config.tokenEnv());
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException(
          "the environment variable "
              + config.tokenEnv()
              + " is not set: it holds the web token (praksis web token create NAME)");
    }
    return start(
        config.mcpCommand(),
        Map.of(
            "PRAKSIS_TOKEN", token.strip(),
            "PRAKSIS_URL", config.mcpUrl(),
            "PRAKSIS_MODE", config.mcpMode()));
  }

  /** Starts a server with this command and environment. */
  public static McpBridge start(List<String> command, Map<String, String> env) {
    if (!runnable(command.get(0))) {
      // the SDK would wait twenty seconds for a process that never started
      throw new IllegalStateException(
          "cannot start the MCP server of Praksis ("
              + String.join(" ", command)
              + "): "
              + command.get(0)
              + " is not an executable file or a program on the PATH");
    }
    ServerParameters.Builder params =
        ServerParameters.builder(command.get(0)).args(command.subList(1, command.size()));
    env.forEach(params::addEnvVar);
    McpSyncClient client =
        McpClient.sync(
                new StdioClientTransport(
                    params.build(), new JacksonMcpJsonMapper(new ObjectMapper())))
            .requestTimeout(Duration.ofSeconds(90))
            .build();
    try {
      client.initialize();
      List<ToolSpec> specs = new ArrayList<>();
      for (McpSchema.Tool t : client.listTools().tools()) {
        boolean readOnly =
            t.annotations() != null && Boolean.TRUE.equals(t.annotations().readOnlyHint());
        Map<String, Object> schema =
            Json.MAPPER.convertValue(
                t.inputSchema(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        specs.add(
            new ToolSpec(
                t.name(), t.description() == null ? "" : t.description(), schema, !readOnly));
      }
      return new McpBridge(client, List.copyOf(specs));
    } catch (RuntimeException e) {
      client.close();
      throw new IllegalStateException(
          "cannot start the MCP server of Praksis ("
              + String.join(" ", command)
              + "): "
              + e.getMessage(),
          e);
    }
  }

  private static boolean runnable(String program) {
    if (program.contains("/") || program.contains(java.io.File.separator)) {
      return java.nio.file.Files.isExecutable(java.nio.file.Path.of(program));
    }
    String path = System.getenv("PATH");
    if (path == null) {
      return true;
    }
    for (String dir : path.split(java.io.File.pathSeparator)) {
      if (!dir.isEmpty() && java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, program))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public List<ToolSpec> tools() {
    return tools;
  }

  @Override
  public Result call(String name, Map<String, Object> arguments) {
    if (tools.stream().noneMatch(t -> t.name().equals(name))) {
      return new Result("there is no tool '" + name + "'", true);
    }
    try {
      McpSchema.CallToolResult r =
          client.callTool(McpSchema.CallToolRequest.builder(name).arguments(arguments).build());
      StringBuilder text = new StringBuilder();
      for (McpSchema.Content c : r.content()) {
        if (c instanceof McpSchema.TextContent t) {
          text.append(t.text());
        }
      }
      return new Result(text.toString(), Boolean.TRUE.equals(r.isError()));
    } catch (RuntimeException e) {
      return new Result("the tool call failed: " + e.getMessage(), true);
    }
  }

  @Override
  public void close() {
    client.close();
  }
}
