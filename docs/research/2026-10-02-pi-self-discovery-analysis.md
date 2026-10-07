# Pi self-discovery & self-evolution analysis

Date: 2026-10-02
Source: `/Users/samnan/Documents/codes/work/research/pi` (earendil-works/pi monorepo)
Purpose: extract, from primary sources, how pi's documentation/prompt/skill system lets the agent
self-discover its own capabilities and self-evolve (extend itself) without bloating the context
window, and map each mechanism onto MCP server primitives (tools / resources / prompts /
instructions) for the FuncHole MCP redesign.

All citations are `path:line` into the pi repo unless noted. Verbatim quotes are short and marked.

---

## 1. Inventory of pi's doc/skill/extension/prompt surfaces

### 1.1 User/project-facing resource surfaces

| Surface | User location | Project location | Format | Loaded how |
|---|---|---|---|---|
| Context files (AGENTS.md) | `<agent-dir>/AGENTS.md` | `AGENTS.md` in cwd **and every ancestor dir** | plain markdown | **Eager**, inlined into system prompt |
| System prompt override | `<agent-dir>/SYSTEM.md` | `.pi/SYSTEM.md` | markdown | replaces default preamble+tools+rules+docs |
| System prompt addendum | `<agent-dir>/APPEND_SYSTEM.md` | `.pi/APPEND_SYSTEM.md` | markdown | appended as `<addendum>` section |
| Skills | `<agent-dir>/skills/` | `.pi/skills/` (plus `.agents/skills/`, `~/.agents/skills/`) | `SKILL.md` + bundled files | **Two-tier**: name+description+path always; body read on demand |
| Prompt templates | `<agent-dir>/prompts/` | `.pi/prompts/` | `.md` with frontmatter | body expanded only when user runs `/name` |
| Extensions | `<agent-dir>/extensions/` | `.pi/extensions/` | `.ts`/`.js` or dir with `index.ts` | code loaded at startup; registers tools/commands/handlers |
| Themes | `<agent-dir>/themes/` | `.pi/themes/` | JSON | not model-facing |
| MCP servers | `<agent-dir>/mcp.json` | `.pi/mcp.json` | JSON config | tools registered with an `exposure` tier |
| Packages | settings.json `packages` | `.pi/settings.json` | npm/git/local | bundles any of the above |

Default agent dir: `~/.pi/agent` (`packages/coding-agent/src/config.ts:563`, overridable via
`PI_CODING_AGENT_DIR`).
Canonical location table: `packages/coding-agent/docs/configuration.md` (agent-directory and
project-`.pi` tables).

Concrete instances in the pi repo itself (it dogfoods all of these):

- `pi/AGENTS.md` — root context file (125 lines of repo rules)
- `pi/.pi/skills/` — `release.md`, `interactive-testing.md`, `add-llm-provider.md`
- `pi/.pi/prompts/` — `cl.md`, `deslop.md`, `is.md`, `pr.md`, `sa.md`, `wr.md`
- `pi/.pi/extensions/` — `import-repro.ts`, `prompt-url-widget.ts`, `redraws.ts`, `tps.ts`

### 1.2 Harness-side documentation surfaces (what the agent reads to learn about pi)

| Surface | Path | Reachable via |
|---|---|---|
| README | `packages/coding-agent/README.md` | path named in system prompt (`getReadmePath()`) |
| Docs directory (40 `.md` files) | `packages/coding-agent/docs/` | path named in system prompt (`getDocsPath()`) |
| Examples directory | `packages/coding-agent/examples/` | path named in system prompt (`getExamplesPath()`) |
| Docs navigation index | `packages/coding-agent/docs/docs.json` | for the docs website; agent uses the topic→file routing table baked into the prompt |
| Extension API types | `packages/coding-agent/src/core/extensions/types.ts` | pointed at from `docs/extensions.md:88` |
| Codemode reference | `packages/coding-agent/docs/codemode.md` | pointed at from the codemode *tool description itself* (`CODEMODE_DOCS_PATH`) |

Path resolution helpers: `packages/coding-agent/src/config.ts:441-453`
(`getReadmePath`, `getDocsPath`, `getExamplesPath` — work from source checkout, npm install, or
standalone binary).

---

## 2. The system prompt strategy

### 2.1 Where it lives

`packages/coding-agent/src/core/system-prompt.ts` — `buildSystemPromptSections()` (line 121)
builds an **ordered map of independently replaceable sections**, each wrapped in a same-named XML
tag (line 175-179). Sections: `preamble` (untagged), `<tools>`, `<rules>`, `<docs>`, optional
`<addendum>`, optional `<project_context>`, optional `<skills>`, `<cwd>`, plus custom sections.

### 2.2 What's in it (default, no custom prompt)

Reconstructed from source (preamble L146-147, tools L148-151, rules L152 via `buildRules` L81-118,
docs L153-160, cwd L170) with the default tool set `["read","bash","edit","write"]`
(`system-prompt.ts:58`, `agent-session.ts:3604`):

```
You are an expert coding assistant operating inside pi, a coding agent harness. You help users
by reading files, executing commands, editing code, and writing new files.

<tools>
- read: Read file contents
- bash: Execute bash commands (ls, grep, find, etc.)
- edit: Make precise file edits with exact text replacement, including multiple disjoint edits in one call
- write: Create or overwrite files

In addition to the tools above, you may have access to other custom tools depending on the project.
</tools>

<rules>
- Use bash for file operations like ls, rg, find
- Use read to examine files instead of cat or sed.
- ... (one bullet per tool guideline, deduped)
- Be concise in your responses
- Show file paths clearly when working with files
</rules>

<docs>
Pi documentation (read only when the user asks about pi itself, its SDK, extensions, themes, skills, or TUI):
- Main documentation: <abs path>/README.md
- Additional docs: <abs path>/docs
- Examples: <abs path>/examples (extensions, custom tools, SDK)
- When reading pi docs or examples, resolve docs/... under Additional docs and examples/... under Examples, not the current working directory
- When asked about: extensions (docs/extensions.md, examples/extensions/), themes (docs/themes.md), skills (docs/skills.md), prompt templates (docs/prompt-templates.md), TUI components (docs/tui.md), keybindings (docs/keybindings.md), SDK integrations (docs/sdk.md), custom providers (docs/custom-provider.md), adding models (docs/models.md), pi packages (docs/packages.md), environment variables (docs/environment-variables.md), MCP servers (docs/mcp.md), codemode scripts ... (docs/codemode.md)
- When working on pi topics, read the docs and examples, and follow .md cross-references before implementing
- Always read pi .md files completely and follow links to related docs (e.g., tui.md for TUI API details)
</docs>

<cwd>
/current/working/directory
</cwd>
```

**Measured size: ~2.5 KB / ~640 tokens** for the whole default system prompt (reconstruction in
`/private/var/folders/.../pi-default-system-prompt.txt`, `wc -c` = 2561 bytes). This excludes
project context files and the skills block, which vary.

### 2.3 What is deliberately excluded

1. **Tool schemas and full tool descriptions.** The prompt carries only a one-line `promptSnippet`
   per tool (each tool declares its own snippet, e.g. `readToolSystemPromptContribution` at
   `src/core/tools/read.ts:20-23`). Full descriptions and parameter schemas ride in the API tool
   declarations, not the prompt.
2. **Skill bodies.** Only `name`, `description`, `location` per skill (`skills.ts:372-378`).
3. **Pi's own documentation.** Not one byte of docs content is inlined — only *paths* and a
   *topic→file routing table* (`system-prompt.ts:153-160`), plus the rule "read only when the user
   asks about pi itself".
4. **MCP tool declarations.** Default MCP exposure is `codemode`, i.e. *not declared to the model
   at all* (`docs/mcp.md:171-176`); only a bounded `<mcp_servers>` section lists server names with
   a one-line summary (capped at 4096 chars total, 250 chars per server description —
   `src/extensions/mcp/index.ts:151,156`).
5. **Deferred/codemode tools of any kind** are absent from both prompt and tool declarations until
   discovered (`docs/extensions.md:152-164`).
6. **Prompt templates.** Zero presence in the system prompt; they are user-invoked slash commands,
   expanded into user messages (`docs/prompt-templates.md:3-5`).
7. **Extension count/identity.** Extensions are never enumerated in the prompt. They surface only
   through what they register (tools get snippets, commands appear in completion). The tools
   section even hedges: *"In addition to the tools above, you may have access to other custom
   tools depending on the project."* (`system-prompt.ts:151`)

### 2.4 The sections are diff-patchable mid-conversation (cache-friendly evolution)

`diffSystemPromptSections()` (`system-prompt.ts:204-216`) diffs the sections the model currently
has (replayed from the transcript) against the desired ones and emits a **patch** system message
containing only changed/removed sections. Used by `_preparePromptAndToolLoadout()`
(`agent-session.ts:1689-1703`). Rationale, stated for the MCP case at `docs/mcp.md:180`:

> "Pi updates the section when a prompt starts... When it changed... Pi appends the new section to
> the conversation instead of changing tool declarations, so earlier messages stay cached."

Test proving the prompt is declared once and patched: `test/system-prompt-updates.test.ts:27-51`
(transcript gets exactly one system entry with sections
`["preamble","tools","rules","docs","cwd"]` and `toolsAdded` for two consecutive prompts).

---

## 3. The self-discovery mechanisms, one by one

### 3.1 Skills: two-tier loading (name+description eager, body on demand)

**Discovery & validation** — `packages/coding-agent/src/core/skills.ts`:

- Discovery rules (doc comment, L160-167): if a directory contains `SKILL.md`, treat it as a skill
  root and stop recursing; otherwise load direct `.md` children in the root; recurse into
  subdirectories to find `SKILL.md`. Skips dotfiles and `node_modules` (L224-231), honors
  `.gitignore`/`.ignore`/`.fdignore` (L16, L47-65).
- Frontmatter contract (L67-72): `name`, `description`, `disable-model-invocation`. Validation
  per the Agent Skills spec: name ≤64 chars, `^[a-z0-9-]+$` (L10-11, L92-112), description
  required and ≤1024 chars (L117-127). A skill **without a description is silently not loaded**
  (L330-332) — the description is the routing signal, so it is mandatory.
- Default locations (L452-455): `<agentDir>/skills` (source `user`) and `<cwd>/.pi/skills`
  (source `project`), plus explicit `skillPaths`. Name collisions: first discovered wins, warning
  emitted (L432-449).

**Tier 1 — what the prompt gets** — `formatSkillsForPrompt()` (`skills.ts:355-383`):

```
The following skills provide specialized instructions for specific tasks.
Use the read tool to load a skill's file when the task matches its description.
When a skill file references a relative path, resolve it against the skill directory ...

<available_skills>
  <skill>
    <name>release</name>
    <description>Prepare, publish, verify, and recover pi releases. ...</description>
    <location>/abs/path/.pi/skills/release.md</location>
  </skill>
  ...
</available_skills>
```

Skills with `disable-model-invocation: true` are excluded from the prompt entirely; they are
reachable only via explicit `/skill:name` (L356, doc at `docs/skills.md:53`).

**Tier 2 — body loading** is done by the model itself with its ordinary `read` tool (the prompt
tells it which tool to use, `skills.ts:364-366`). There is also a user-forced path:
`/skill:name args` expands inline by reading the file and wrapping it
(`agent-session.ts:2100-2124`):

```ts
const skillBlock = `<skill name="${skill.name}" location="${skill.filePath}">\nReferences are relative to ${skill.baseDir}.\n\n${body}\n</skill>`;
return args ? `${skillBlock}\n\n${args}` : skillBlock;
```

**The doc states the design intent explicitly** (`docs/skills.md:43-45`):

> "At startup, Pi scans configured skill locations and adds each skill's name, description, and
> path to the system prompt. It does not add the full instructions. When a task matches, the model
> reads `SKILL.md` and follows its instructions. This keeps detailed guidance out of context until
> it is needed."

And the routing-description guidance (`docs/skills.md:37`):

> "The description determines when the model considers loading the skill. State both what the
> skill does and when it applies. Avoid descriptions such as 'Helps with PDFs,' which do not
> provide enough routing information."

**Real skill anatomy** — `pi/.pi/skills/release.md` (48 lines): frontmatter `name` + `description`
("Use for release preparation, local release smoke tests, publishing, and failed release CI"),
then numbered runbook steps. It *chains* another skill by relative link: "Load and follow
[interactive-testing.md](interactive-testing.md) for the tmux workflow" (L35) — skills compose
without both being in context up front. `.pi/skills/interactive-testing.md` (19 lines) is the
tmux runbook.

**Skills can bundle supporting files** (`docs/skills.md:11-22`): `scripts/`, `references/`,
`assets/` live next to `SKILL.md`; the prompt section teaches path resolution against the skill
directory (`skills.ts:367`). This is a *third* tier: the skill body itself points at further
files the model reads only if the runbook says to.

### 3.2 Context files (AGENTS.md): the one eagerly-loaded tier — used as an index, not a dump

`loadProjectContextFiles()` (`src/core/resource-loader.ts:232-270`) collects, in order: the agent
dir context file, then cwd and **every ancestor directory's** context file (candidate names at
L185: `AGENTS.override.md`, `AGENTS.md`, `AGENTS.MD`, `CLAUDE.md`, `CLAUDE.MD`), with
worktree-shadowing dedup (L214-230). All of them are inlined into the system prompt under
`<project_context>` (`system-prompt.ts:72-79,164`).

This is the *only* fully-eager knowledge tier. Pi's own repo shows the intended discipline: the
root `AGENTS.md` keeps operational rules inline but **defers bulky procedures to skills by
pointer**:

- `pi/AGENTS.md:100` — "For testing pi's interactive mode, load and follow
  [.pi/skills/interactive-testing.md](.pi/skills/interactive-testing.md)."
- `pi/AGENTS.md:121` — "For release preparation, publishing, verification, or recovery, load and
  follow [.pi/skills/release.md](.pi/skills/release.md)."

So even the eager tier is written as an *index*: stable, short, decision-relevant rules inline;
procedures behind links the model already knows how to follow (it has `read`).

### 3.3 Docs-as-pointers: the `<docs>` section is a routing table, not documentation

`system-prompt.ts:153-160`. Three mechanisms in 8 lines:

1. **Scoping rule** — docs are read "only when the user asks about pi itself, its SDK, extensions,
   themes, skills, or TUI". Keeps doc-reading out of ordinary coding work.
2. **Topic→file routing table** — "When asked about: extensions (docs/extensions.md,
   examples/extensions/), themes (docs/themes.md), skills (docs/skills.md), …" so the first
   `read` lands on the right file with no search step.
3. **Resolution and completeness rules** — resolve relative doc paths against the docs/examples
   base dirs, not cwd; "read pi .md files completely and follow links to related docs" (i.e. the
   docs' internal cross-references are part of the discovery graph).

The docs themselves are written for agent traversal: `docs/extensions.md` and `docs/mcp.md` are
dense contract documents; `docs/extensions.md:88` points into source
(`extensions/types.ts`) as the ultimate type reference.

### 3.4 Tool-deferral architecture: exposure tiers + `tool_search` (BM25) + codemode

This is pi's most aggressive context-saving mechanism, applied both to extension tools and MCP
tools. Every registered tool has an `exposure` (`docs/extensions.md:152-164`):

| Exposure | Declared to model | Callable | Reachable via |
|---|---|---|---|
| `direct` (default) | yes, while active | while active | normal tool call |
| `model-only` | yes | never | orchestration tools |
| `codemode` | **no** | always, from codemode scripts | `searchTools()` inside scripts |
| `deferred` | **no** | after activation | `tool_search` loads it into the active set |
| `hidden` | no | no | withdrawn |

**`tool_search`** (`src/extensions/tool-search/tool.ts`): a single always-cheap tool whose
description (L216-220) teaches the model the whole pattern:

> "Some of the tools, such as tools of MCP servers, may not have been provided to you upfront,
> and you should use this tool (`tool_search`) to search for the required tools. For MCP tool
> discovery, always use `tool_search`."

Mechanics:

- Index documents are built from **metadata only**: tool name, name with `_`→space, description,
  schema descriptions and property names, namespace name/description/instructions
  (`createToolSearchDocument`, L108-116). No tool bodies or schemas are in the model's context.
- BM25 ranking with tokenization that splits camelCase and stems plurals (L39-157).
- On a match, `searchAndLoad` **activates** the tools: `tools.setActiveTools([...active,
  ...matches])` (L200-214). Activation is recorded in the transcript like any tool change (the
  section/tool-diff machinery of §2.4), so it "survives `/tree`, resume, and fork on that branch"
  (L6-8).
- The tool's own description is *stable by design*: "It does not list the searchable tools or
  their namespaces, so it stays the same while tools are registered, for example when MCP servers
  connect." (L216-219 comment) — a changing description would invalidate the prompt cache.

**MCP default is deferral**: `exposure: "codemode"` is the default for every MCP server
(`src/extensions/mcp/index.ts:128-130`, `docs/mcp.md:173`). Consequences:

- MCP tool declarations cost **zero** prompt tokens by default, regardless of server size.
- The model learns that servers *exist* from the `<mcp_servers>` prompt section
  (`renderServersSection`, `mcp/index.ts:188-214`): bounded at 4096 chars total / 250 chars per
  server summary, one line each: `- mcp__<server> (codemode|tool_search): <first line of
  description or server instructions>`. Omitted servers collapse into a closing
  "… N more servers; find their tools with searchTools()" line (L198-199).
- The section intro tells the model the *mechanism*, not the tools
  (`serversSectionIntro`, L158-164): "MCP servers whose tools are not declared to you. Call the
  tools of `codemode` servers from codemode scripts. Load the tools of `tool_search` servers with
  `tool_search`."

**MCP server instructions are also deferred** (`docs/mcp.md:210`):

> "Server instructions are not part of any tool description; scripts read them with
> `describeNamespace("mcp__<server>")`, which also returns the server's tool names."

The server `instructions` string is stored on the tool namespace at registration
(`mcp/index.ts:357-361`) and returned on demand by `describeNamespace()`
(`src/extensions/codemode/execute.ts:493-515`, returns `{name, description, instructions, tools}`).

**Codemode catalog budgeting** (`src/extensions/codemode/tool.ts`): even inside the codemode
tool's own description (the one place tools *are* listed for scripts), listings are budgeted:
`DEFAULT_CODEMODE_INLINE_BUDGET = 3000` estimated tokens (L153-156), with a round-robin selection
(`selectCatalog`, L205-211) that represents every namespace before completing any. Tools that
don't fit are simply left out — scripts rediscover them via `searchTools()`. The codemode
description also embeds a doc pointer instead of docs: "`models`: classifiers and image
generation. Read ${CODEMODE_DOCS_PATH} first." (L148, L133).

**Tool-result truncation as disclosure** (`docs/mcp.md:208`, `docs/extensions.md:144`):

> "Text results over 20 KB reach the model with their middle removed around a `…N chars
> truncated…` marker. The full text is saved to a temporary file named in the result."

> "Truncate large model-facing results and tell the model where to read the complete output."

Same pattern in the built-in `read` tool: truncated output ends with an actionable pointer —
`[Showing lines 1-2000 of 5400. Use offset=2001 to continue.]`
(`src/core/tools/read.ts:163-178`). The pointer teaches the *next* tool call.

### 3.5 Prompt templates: user-triggered, zero prompt footprint

`src/core/prompt-templates.ts`. A `.md` file with optional frontmatter (`description`,
`argument-hint`) becomes a `/name` slash command. The model never sees the template list in the
system prompt; the *user* picks the command, and the expanded body enters as a user message
(`agent-session.ts:2144-2145`, `expandPromptTemplate`). Substitution supports `$1`, `$@`,
`${1:-default}`, `${@:N:L}` (`docs/prompt-templates.md:38-49`). Example: `pi/.pi/prompts/wr.md`
(41 lines of release-finishing procedure) — costs nothing until typed.

### 3.6 Extensions: executable evolution, registered capabilities surface through existing channels

Extensions are TypeScript modules loaded via jiti (no build step) from
`<agent-dir>/extensions/`, `.pi/extensions/`, CLI flags, or packages
(`src/core/extensions/loader.ts`; `docs/extensions.md:44-48`). The factory receives `ExtensionAPI`
and registers: lifecycle handlers (`pi.on`), tools (`pi.registerTool` — with `promptSnippet` /
`promptGuidelines` / `exposure` / `namespace`), commands, shortcuts, flags, providers, MCP
servers, renderers (`docs/extensions.md:71-88`).

Key disclosure-relevant facts:

- Extensions do not get a prompt section. Their tools join the same tiered system: a `direct`
  tool gets a one-line snippet in `<tools>`; a `codemode`/`deferred` tool stays invisible until
  discovered. Runtime registration is first-class: "Register every tool first, keep optional
  tools inactive, and use `pi.setActiveTools()` from a loader tool to select the desired active
  tools." (`docs/extensions.md:182-186`; example `examples/extensions/dynamic-tools.ts` registers
  new tools at runtime from a command).
- `prepareLoadout(loadout)` lets an orchestrator tool rewrite *declared* descriptions and hide
  declarations per request (`docs/extensions.md:180`).
- Extensions can inject new *resources*: the `resources_discover` event returns extra
  `skillPaths`/`promptPaths`/`themePaths` (example `examples/extensions/dynamic-resources/index.ts`
  contributes a SKILL.md at runtime).
- `before_agent_start` handlers receive the structured `systemPromptOptions` and are told to
  "prefer changing prompt sections, selected tools, or guidelines so Pi can append a transcript
  delta" (`docs/extensions.md:103`) — i.e. even prompt mutation is designed around the
  cache-preserving section-diff.

### 3.7 The chooser: documented escalation ladder

`docs/quickstart.md:94-106` — "Start with the least powerful mechanism that meets your need":

| Need | Start with |
|---|---|
| Persistent instructions for a folder | `AGENTS.md` |
| Reuse a prompt from the `/` menu | Prompt template |
| Task-specific instructions + supporting files | Skill |
| Executable tools, commands, event handlers | Extension |
| Custom terminal component | Terminal UI |
| Unsupported model service | Custom provider |
| Distribute several resources | Pi package |

This ladder is the authoring-side counterpart of progressive disclosure: knowledge lives at the
*weakest* (cheapest, most lazily-loaded) tier that can express it.

---

## 4. The self-evolution loop as pi designs it

Pi has no automated "self-improvement" subsystem. The loop is **intended usage**, enabled by three
design decisions, and stated plainly in the docs:

- `packages/coding-agent/README.md:17` — "**Ask Pi to create the prompt templates, skills,
  extensions, and themes you need**, or install a Pi package."
- `docs/index.md:5` — "You can use Pi as is, **prompt it to adapt itself to your workflow**, or
  build other applications powered by Pi using the SDK."

The loop, reconstructed from the mechanisms:

1. **Trigger is conversational.** The user asks for a workflow change ("when I say X, do Y",
   "make releasing easier"). The `<docs>` scoping rule (`system-prompt.ts:153`) routes pi-topics
   questions into the docs.
2. **The agent reads the authoring contract on demand.** The routing table sends it to
   `docs/skills.md` / `docs/prompt-templates.md` / `docs/extensions.md`, which contain exact file
   formats, locations, and frontmatter rules; `docs/extensions.md:267-268` sends it to the
   smallest matching example in `examples/extensions/`. Because the contract is *files*, the agent
   authors with its normal `write`/`edit` tools — no special "create skill" API exists or is
   needed.
3. **The artifact lands in a conventional location** (`~/.pi/agent/skills/`, `.pi/skills/`, …) and
   is picked up on next start or `/reload` (`docs/skills.md:89`, `docs/prompt-templates.md:23`).
   From then on it participates in the same two-tier discovery as built-in knowledge: its
   description routes future invocations.
4. **AGENTS.md is the human-facing memory; skills are the machine-scalable memory.** The repo's
   own AGENTS.md shows the pattern of record: keep global rules in AGENTS.md; when a procedure
   grows, extract it into a skill and leave a pointer (AGENTS.md:100,121). Skills even cross-link
   each other (`release.md:35` → `interactive-testing.md`), forming a graph the agent traverses
   just-in-time.
5. **Runtime self-extension exists but is session-scoped.** An extension (authored once, possibly
   by the agent) can register tools at runtime (`dynamic-tools.ts`), contribute skills
   (`resources_discover`), or mutate prompt sections (`before_agent_start`). Persistence is
   achieved by the agent *writing the extension file*, not by an in-memory mutation API.

So: self-evolution = *the agent edits its own resource files*, and discovery is cheap enough that
the new capability costs ~2 lines of prompt (name+description) forever after.

---

## 5. First-principles distillation → MCP server (FuncHole) mapping

### The rules pi follows

| # | Rule (as pi implements it) | Evidence |
|---|---|---|
| R1 | **Advertise, don't inline.** Always-on context carries identity + routing description + *pointer*, never the body. | skills (`skills.ts:355-383`), docs (`system-prompt.ts:153-160`), MCP servers (`mcp/index.ts:188-214`) |
| R2 | **The description is the router; make it mandatory and bounded.** No description → not loaded; ≤1024 chars; guidance says state *what* and *when*. | `skills.ts:117-127,330-332`; `docs/skills.md:37` |
| R3 | **Bodies load through the same primitive the agent already uses to read anything.** Skills load via the ordinary `read` tool; docs via `read`; server instructions via a lookup call. No special fetch protocol per knowledge type. | `skills.ts:364-366`; `docs/mcp.md:210` |
| R4 | **Bound every always-on listing.** 4096-char server section, 250-char summaries, 3000-token codemode catalog budget, "…N more" collapse lines. | `mcp/index.ts:151,156,198-199`; `codemode/tool.ts:153-156,205-211` |
| R5 | **When listings don't fit, substitute search.** BM25 over metadata (name, description, schema text, namespace); the search tool's own description stays constant as the catalog changes. | `tool-search/tool.ts:108-116,216-220` |
| R6 | **Keep indexes stable; append deltas.** Structured prompt sections diffed per request; changed sections appended, never rewritten, so prefix caching survives capability changes. | `system-prompt.ts:204-216`; `agent-session.ts:1689-1703`; `docs/mcp.md:180` |
| R7 | **Defer activation, not just text.** Tools are registered but undeclared until a discovery event activates them; activation is recorded so it persists on the branch. | `docs/extensions.md:152-164`; `tool-search/tool.ts:200-214` |
| R8 | **Truncate big results with a pointer to the rest.** Middle-out truncation + temp file path; read-tool continuation offsets; "tell the model where to read the complete output". | `docs/mcp.md:208`; `docs/extensions.md:144`; `read.ts:163-178` |
| R9 | **One eager tier, disciplined.** AGENTS.md is fully inlined — so it is written as an index of pointers to lazily-loaded runbooks. | `resource-loader.ts:232-270`; `AGENTS.md:100,121` |
| R10 | **Authoring = writing files in conventional formats in conventional locations.** The agent extends itself with the same tools it uses for any task; the authoring contract is itself a lazily-loaded doc. | `docs/skills.md:9-39`; README.md:17 |
| R11 | **Escalation ladder: use the weakest mechanism that fits.** Instructions < prompt template < skill < extension < provider. | `docs/quickstart.md:94-106` |
| R12 | **Docs are written for agent traversal**: topic→file routing tables, "read completely and follow cross-references", smallest-example pointers. | `system-prompt.ts:153-160`; `docs/extensions.md:267-268` |

### Mapping to MCP primitives

An MCP server can speak through exactly four channels: **tools** (name + description + schema +
result), **resources** (listed via `resources/list`, read via `resources/read`), **prompts**
(listed, fetched via `prompts/get`), and **instructions** (the server `instructions` string sent
at initialize). Pi itself, acting as an MCP *client*, already demonstrates where each pi mechanism
lands (`docs/mcp.md:61,180,210`).

| Pi mechanism | MCP primitive for FuncHole | How |
|---|---|---|
| `<docs>` routing table (R1, R12) | **`instructions`** | Put the platform's "when asked about X, read resource Y" table + scoping rule in the server instructions string. Pi (the client) surfaces the first line of instructions in its bounded `<mcp_servers>` section and the full string via `describeNamespace()` — so keep line 1 to ≤250 chars as the summary, and front-load the routing table. |
| Skill name+description tier (R1, R2) | **Tool descriptions / resource list entries / prompt list entries** | Every tool description should state *what + when*; every resource `name`/`description` is a routing entry. Descriptions are the only always-on real estate — spend them on routing, not documentation. |
| Skill bodies (R1, R3) | **Resources** (`funchole://docs/...`, `funchole://runbooks/...`) | Bodies live as resources; the agent reads them with `read_mcp_resource` (which pi's client adds automatically for servers with resources — `docs/mcp.md:214-220`). One read = one body, exactly like pi's `read SKILL.md`. |
| Skill bundles (scripts/references/assets) | **Resource URI hierarchy** | `funchole://docs/<topic>/INDEX.md` links to sibling URIs; the doc text carries relative references and the server resolves them, mirroring "references are relative to the skill directory". |
| Prompt templates | **Prompts** (`prompts/list`, `prompts/get`) | User-invoked, argument-taking, zero tool-list cost — MCP prompts are the exact analog of `.pi/prompts/*.md`. |
| `tool_search` + deferred exposure (R5, R7) | **Server-side search tool + many fine-grained tools** | Expose one small `search`/`describe` tool (or rely on the client's `tool_search`, which indexes all tool metadata — so write keyword-rich tool names/descriptions/schemas). Pi already defaults MCP tools to `codemode` exposure and ranks them with BM25 over name/description/schema (`tool-search/tool.ts:108-116`): a FuncHole server with 200 tools costs ~0 prompt tokens in pi today. |
| Bounded `<mcp_servers>` section (R4) | **`instructions` + tool `description`s, budgeted** | Assume only ~250 chars of your instructions' first line and your tools' metadata are ever seen eagerly. Hard-cap any listing you emit yourself; collapse the tail into "…N more; search with X". |
| Section diffs / stable declarations (R6) | **Stable instructions and tool list; `notifications/tools/list_changed` sparingly** | Keep instructions static per deployment; don't churn tool names/descriptions per session — clients cache declarations, and pi appends deltas rather than rewriting. |
| Truncation with pointer (R8) | **Tool results + resources** | Cap tool results (~20 KB like pi), write the full payload to a resource (or temp URI), and end the result with the pointer: "[truncated; full output at funchole://...]". Pi's client already does middle-out truncation to a temp file for MCP results (`docs/mcp.md:208`), but server-side discipline makes results useful to *all* clients. |
| AGENTS.md discipline (R9) | **Instructions as index** | Whatever goes in `instructions` should be rules + pointers, never prose docs. |
| Authoring-by-files (R10) | **Tools that mutate server-side config/docs** | If FuncHole wants agents to extend the platform, expose "write" tools (e.g. `register_function`, `update_doc`) whose contract is itself a resource. The agent learns the contract on demand, then uses normal tools — no pre-loaded API knowledge. |
| Escalation ladder (R11) | **Instructions' chooser table** | Ship a small "need → which FuncHole primitive" table in instructions or a top-level `funchole://docs/START.md` resource, mirroring `quickstart.md:94-106`. |

### The transferable core, in one paragraph

Pi's whole design reduces to: **the model always knows (a) that knowledge exists, (b) one line
about when it applies, and (c) a pointer it can dereference with a tool it already has.** Nothing
else is allowed into always-on context, and everything that must be always-on is size-capped.
Self-evolution then falls out for free: extending the system means writing a new file whose
*description* joins the always-on index (~2 lines of prompt), while its *body* stays behind the
pointer until a future task matches. For an MCP server, the same loop is: instructions = the
index; tool/resource/prompt descriptions = the routing entries; resources and prompts = the
bodies; a search tool = the overflow valve; and every large result = truncated text plus a
dereferenceable pointer.
