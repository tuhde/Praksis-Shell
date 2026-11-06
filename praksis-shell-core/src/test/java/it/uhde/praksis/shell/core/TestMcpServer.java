// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** A small MCP server for the tests: one tool that looks, one that changes, one that tells. */
public final class TestMcpServer {
  private TestMcpServer() {}

  private static SyncToolSpecification tool(
      String name,
      boolean readOnly,
      java.util.function.Function<Map<String, Object>, String> body) {
    return SyncToolSpecification.builder()
        .tool(
            McpSchema.Tool.builder(name, Map.of("type", "object", "properties", Map.of()))
                .description("test tool " + name)
                .annotations(new McpSchema.ToolAnnotations(name, readOnly, null, null, null, null))
                .build())
        .callHandler(
            (exchange, request) -> {
              String text =
                  body.apply(request.arguments() == null ? Map.of() : request.arguments());
              return McpSchema.CallToolResult.builder()
                  .addTextContent(text)
                  .isError(text.startsWith("ERR"))
                  .build();
            })
        .build();
  }

  public static void main(String[] args) throws InterruptedException {
    var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()));
    McpServer.sync(transport)
        .serverInfo("test", "0")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(
            tool("look", true, a -> "seen " + a),
            tool("change", false, a -> "ERR this token is read only"),
            tool(
                "env",
                true,
                a ->
                    System.getenv("PRAKSIS_MODE")
                        + ","
                        + System.getenv("PRAKSIS_URL")
                        + ","
                        + (System.getenv("PRAKSIS_TOKEN") == null
                            ? "no token"
                            : "token:" + System.getenv("PRAKSIS_TOKEN"))))
        .build();
    new CountDownLatch(1).await();
  }
}
