# Praksis Shell

Talk to a model in a terminal; it works with your [Praksis](https://github.com/tuhde/Praksis)
daemon **only through its MCP server**
([Praksis-MCP](https://github.com/tuhde/Praksis-MCP)) and has no rights of its own. It looks at
rules and history, checks branches of a layer and hands you proposals. Approvals, grants and plans
stay with you, in the desktop program or with the `praksis` command; the model has no tool for them.

**Status:** a model on this machine (the Ollama API or an OpenAI-compatible server) or the
Anthropic API (through the official Java SDK), with streaming. The tab in Praksis Desktop comes
next. The core is its own module,
`praksis-shell-core` (the loop, the model clients, the MCP client; no terminal and no JavaFX), so
that the desktop program can use it.

The full guide is the page "Talking to Claude in the terminal (the Claude Shell)" in the
documentation of Praksis, `docs/user/shell.md`.

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

## A model in the cloud (the Anthropic API)

    [profile.claude]
    api         = "anthropic"
    model       = "claude-sonnet-5-5"        # required; there is no default model
    data        = "cloud"                    # you say that the data leaves this machine
    key_env     = "ANTHROPIC_API_KEY"        # the variable that holds the key, or:
    # key_command = ["kwallet-query", "-r", "anthropic-key", "kdewallet"]   # prints the key

    [session]
    max_tokens_per_session = 200000          # the default for a model in the cloud

The key comes from the environment variable or from the first line the command prints, and is
never in the file, never printed and never in an error message. `url` defaults to
`https://api.anthropic.com` and must be `https://`. `temperature` is refused: the API does not
accept it any more.

**Nothing is sent until you type `/yes`**, once for each session. The banner names the host and what
goes there (your requests and the results of tools, which can contain mail text). `/yes` is yours;
a `/yes` that the model writes is only text. When the tokens read and written in a session pass
`max_tokens_per_session`, the session stops with a note.

The whole answer of the model, with its thinking blocks, is kept and sent back as it was when the
conversation goes on, because the API requires thinking blocks to be unedited. Not done yet: the
server-side fallback for a request that the safety classifiers decline (the shell says that the
model declined and why).

## What you see

- The model's text appears as it is written. A tool call is a line before it runs
  (`-> praksis_status`, with `[may change]` if it could change something), and its result is
  shown as a length.
- After each request a line shows the tokens of the session: `[tokens: 5.9k read, 133 written,
  limit 200k]`. `/usage` shows them again with the number of tool calls. Tokens read are summed
  over all answers of the session, because each answer reads the conversation again; that is also
  what is billed.
- If the model stops in the middle of an answer (nothing for `timeout_ms`, or the connection
  breaks), the text so far stays on the screen, a note says why, and the half answer is not part of
  the conversation.
- Commands: `/tools`, `/usage`, `/clear`, `/yes`, `/help`, `/quit`, and your own commands to the
  daemon (next section).

## Your own channel to the daemon

Claude proposes; you decide. These commands go to the **Unix socket of the daemon**, where no token
reaches, as the `praksis` command does:

| Command | What it does |
|---|---|
| `/approvals` | lists the requests that wait (which rule wants which capability, with what) |
| `/allow <id>` | shows the request and allows it **once**, after you answer `y` |
| `/deny <id>` | denies it |
| `/plans`, `/plan <id>` | lists the plans Claude made, or shows one with every step, `NOT COVERED` by the grants, and `CHECK:` for a value that was not in your request |
| `/plan-run <id>` | shows the plan and runs it only when you type its id |
| `/plan-drop <id>` | throws it away |

A grant that lasts (`always`), and every change to the ruleset, stay with the desktop program or
`praksis approvals`, where the text of the grant is in front of you. The commands find the daemon
like the `praksis` command does: `[daemon] home = "~/.praksis"` or `identity = "private"` in
`shell.toml`, else `$PRAKSIS_HOME`, `$PRAKSIS_IDENTITY`, else `~/.praksis`.

**Claude never sees any of it.** What these commands print is shown to you and is not part of the
conversation; the shell handles a line that starts with one of them itself and never forwards it,
and a `/allow 3` that the model writes is only text on the screen. Text that comes from the daemon
(a mail subject in the arguments of a request) is cleaned of escape sequences before it is printed.

## What is kept apart

- **Your commands and the model's words never mix.** A line that starts with `/` is yours
  (`/tools`, `/clear`, `/help`, `/quit`), is handled by the shell and is never sent to the model;
  what the model writes is only ever shown. The question "Allow this call?" is shown to you and
  answered by you, and the model does not see it.
- **`data` is enforced**: a local profile (`ollama`, `openai`) must point at this machine (a literal
  address or `localhost`; a name is not looked up), and a profile with `api = "anthropic"` must say
  `data = "cloud"`. Your Ollama may offer `:cloud` models, which send your prompts to a remote host
  although the address is local: the shell cannot tell, so do not use them for a `data = "local"`
  profile.
- **What is printed is cleaned** of escape sequences, so text from a mail that the model repeats
  cannot move the cursor or change the title.
- **A tool result is shown to you as a length**, not as text; the text goes to the model, between
  the markers that the MCP server puts around data from outside.
- **Limits**: tool calls per session, answers per request, and the token is not in any file.

## Tests

`./mvnw verify`: the configuration, the model clients against a fake server (the request shapes
and the answers, with and without streaming, for the Anthropic API through the SDK; a stream that
stops is given up), the key sources, the loop with a scripted
model (calls, declined calls, the budgets, the turns, the permission for the cloud, an error of the
model), the MCP bridge against a real MCP server in its own process, the terminal, and your
channel (what it sends, that nothing it prints reaches the model, that nothing the model writes
reaches the daemon). No test
calls the real Anthropic API.

`./mvnw verify` then runs `LiveShellIT` with the **real daemon** (the runtime image of the core) and
the **real MCP server** (the jar of Praksis-MCP), with a scripted model as the only fake: a mail with
a hostile subject is delivered, and an agent that does what the mail says finds no tool to act on
it, cannot approve the request, and leaves it pending; the person then allows it with `/allow`, and
what the person saw never reached the model. It needs the image and the jar (`./mvnw package` in
`../Praksis` and in `../Praksis-MCP`, or `-Dpraksis.image=<dir> -Dpraksis.mcp.jar=<file>`); without
them it is skipped and says so.

`./mvnw verify` then runs `LiveShellIT` with the **real daemon** (the runtime image of the core) and
the **real MCP server** (the jar of Praksis-MCP), with a scripted model as the only fake: a mail with
a hostile subject is delivered, and an agent that does what the mail says finds no tool to act on
it, cannot approve the request, and leaves it pending; the person then allows it with `/allow`, and
what the person saw never reached the model. It needs the image and the jar (`./mvnw package` in
`../Praksis` and in `../Praksis-MCP`, or `-Dpraksis.image=<dir> -Dpraksis.mcp.jar=<file>`); without
them it is skipped and says so.

## License

Apache-2.0.
