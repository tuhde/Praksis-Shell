# Praksis Shell

Talk to a model in a terminal; it works with your [Praksis](https://github.com/tuhde/Praksis)
daemon **only through its MCP server**
([Praksis-MCP](https://github.com/tuhde/Praksis-MCP)) and has no rights of its own. It looks at
rules and history, checks branches of a layer and hands you proposals. Approvals, grants and plans
stay with you, in the desktop program or with the `praksis` command; the model has no tool for them.

**Status:** first version. A model on this machine (the Ollama API or an OpenAI-compatible server),
one request at a time, no streaming yet. Models in the cloud (the Anthropic API) come next, then
streaming, the budget and the display, and the tab in Praksis Desktop. The core is its own module,
`praksis-shell-core` (the loop, the model clients, the MCP client; no terminal and no JavaFX), so
that the desktop program can use it.

## Try it

1. Turn on the web door of the daemon and make a token (see "Letting an agent use Praksis" in the
   documentation of Praksis):

       praksis web token create shell --read-only

2. Build, with the core installed first (`./mvnw install` in `../Praksis`), and
   Praksis-MCP built (`./mvnw package` there):

       ./mvnw package

3. Write `~/.config/praksis-shell/shell.toml` (or point `PRAKSIS_SHELL_CONFIG` at a file):

       default = "local"

       [profile.local]
       api   = "ollama"                  # or "openai" for llama.cpp, vLLM, LM Studio
       url   = "http://127.0.0.1:11434"
       model = "qwen3.5:latest"
       data  = "local"                   # the model runs on this machine

       [mcp]
       command   = ["java", "-jar", "/path/to/Praksis-MCP/target/praksis-mcp.jar"]
       url       = "http://127.0.0.1:8484"   # the web door of the daemon
       mode      = "read-only"               # or "propose"
       token_env = "PRAKSIS_TOKEN"           # the variable that holds the token

       [session]
       max_tool_calls = 30     # in one session
       max_turns      = 12     # answers of the model to one request
       confirm_tools  = true   # ask you before a tool that may change something

4. Run it. The token is read from the environment, never from the file:

       PRAKSIS_TOKEN=pwt_... java -jar praksis-shell/target/praksis-shell.jar

## What is kept apart

- **Your commands and the model's words never mix.** A line that starts with `/` is yours
  (`/tools`, `/clear`, `/help`, `/quit`), is handled by the shell and is never sent to the model;
  what the model writes is only ever shown. The question "Allow this call?" is shown to you and
  answered by you, and the model does not see it.
- **`data = "local"` is enforced**: the address of the model must be this machine (a literal address
  or `localhost`; a name is not looked up). Profiles for the cloud are refused until the credential
  binding exists.
- **What is printed is cleaned** of escape sequences, so text from a mail that the model repeats
  cannot move the cursor or change the title.
- **A tool result is shown to you as a length**, not as text; the text goes to the model, between
  the markers that the MCP server puts around data from outside.
- **Limits**: tool calls per session, answers per request, and the token is not in any file.

## Tests

`./mvnw verify`: the configuration, the two model clients against a fake server (the request shapes
and the answers), the loop with a scripted model (calls, declined calls, the budget, the turns, an
error of the model), the MCP bridge against a real MCP server in its own process, and the terminal.

## License

Apache-2.0.
