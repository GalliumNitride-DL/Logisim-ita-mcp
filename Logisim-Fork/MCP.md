# Logisim MCP server

Logisim can be driven by an AI agent through the
[Model Context Protocol](https://modelcontextprotocol.io). The agent can open and
save circuits, place and wire components, drive inputs, read signals, advance the
clock, and verify a design with a truth table — without a human clicking anything.

This makes Logisim usable as a *tool* by a coding agent: "build me a 4-bit ALU",
"why is my CPU's program counter not incrementing?", "check that this adder is
correct".

```bash
# start the server on stdin/stdout (what an MCP client launches)
java -jar Logisim.jar -mcp

# open a design immediately
java -jar Logisim.jar -mcp --file path/to/design.circ

# serve over a local TCP socket instead (see "Transports" below)
java -jar Logisim.jar -mcp --transport tcp --port 7331
```

## What the agent can do

| Group | Tools |
|---|---|
| Session | `logisim_status`, `logisim_undo`, `logisim_circuit_info` |
| Files | `logisim_file_new`, `logisim_file_open`, `logisim_file_recover`, `logisim_file_save`, `logisim_file_save_as`, `logisim_file_close`, `logisim_file_info` |
| Discovery | `logisim_library_list`, `logisim_component_catalog` |
| Documentation | `logisim_help` |
| Circuits | `logisim_circuit_list`, `logisim_circuit_create`, `logisim_circuit_delete`, `logisim_circuit_set_main`, `logisim_circuit_select`, `logisim_circuit_describe`, `logisim_circuit_stats`, `logisim_circuit_nets` |
| Editing | `logisim_component_add`, `logisim_component_remove`, `logisim_component_move`, `logisim_component_set_attributes`, `logisim_wire_add`, `logisim_wire_remove` |
| Simulation | `logisim_sim_reset`, `logisim_sim_set_input`, `logisim_sim_set_inputs`, `logisim_sim_read`, `logisim_sim_read_all`, `logisim_sim_propagate`, `logisim_sim_tick`, `logisim_sim_status` |
| Analysis | `logisim_analyze_truth_table`, `logisim_analyze_expression`, `logisim_circuit_build` |
| Repair | `logisim_circuit_repair_wires` |

Every tool returns JSON. Errors come back as `isError: true` with a message that
says what to do next (for example *"a Pin has no attribute 'nonsense'; its
attributes are: facing, output, width, ..."*), because an agent has to be able to
recover on its own.

### Finding out what a component's pins do

Pins are where an agent gets into trouble: which input of a flip-flop is the clock,
which is the clear, whether an enable is active high. Two tools answer that, and they
answer it from Logisim itself rather than from guesswork:

* **`logisim_component_catalog`** reports, for each component, its ports with the
  library's own name for each one, the direction the value travels, the width and the
  position relative to the component's anchor. A `D Flip-Flop` comes back as six ports
  including `Current flip-flop state`, `Clock: state updates on trigger` and
  `Clear: When 1, pin state to 0 asynchronously`. The same names appear on the `ports`
  of whatever `logisim_component_add` places, so a component can be checked pin by pin
  after placing it.
* **`logisim_help`** reads Logisim's manual, which the program already ships: the user
  guide and the library reference, one page per component, in plain text. With no
  arguments it lists the manual, with `query` it searches every page and returns the
  paragraph each hit matched, and with `topic` it returns one page - a page path
  (`libs/mem/flipflops.html`), a word (`flipflops`) or a component name (`D Flip-Flop`,
  which falls back to a search because the manual's page names do not always match the
  component's). It needs no project open.

### Staying on the canvas

Coordinates are `x >= 0, y >= 0`, in multiples of 10. Logisim's canvas starts at the
origin and grows right and down, so anything at a negative coordinate is valid but
off-screen - a circuit that looks empty to the user. The editing tools therefore
refuse a negative coordinate, from either end: `logisim_component_add` and
`logisim_component_move` check the component's position *and* its body (a Pin's
anchor is its centre, so a Pin at (0,0) has corners at -25) and tell the agent the
coordinates to use instead; `logisim_wire_add` checks every point the agent wrote as
numbers, while leaving symbolic endpoints (`"A"`, `"FA0.Cout"`) alone, since those
name ports that already exist.

### Seeing the wiring through the netlist

An agent cannot look at the schematic, so wiring mistakes used to surface only as an
`x` or an `e` in a read. `logisim_circuit_nets` closes that gap: it groups the wires
and the component ports of a circuit into nets (the sets of things that are the same
electrical node) and reports each net's wires, length, labels, ports, and **how many
of those ports drive it**. The two symptoms then read as follows:

* a net that reads `x` has **no driver** — or has a port listed under
  `unconnected_ports`, which means that port touches no wire at all;
* a net that reads `e` has **more than one driver**, and the port list names them.

Drivers are counted by what a component does electrically, not by the direction shown
in `ports`: inside a circuit an *input* pin is the source of its net, and an *output*
pin only reads it. Ports that can drive when enabled (tristate pins, bus ports) are
counted separately as `bidirectional`, which is also why an unpoked tristate pin reads
`x` rather than a value. Verified against the simulator: two input pins wired to one
net report `drivers: 2` and both read `e` once they are driven to opposite values.

Nets are numbered from the most connected down, so `n1` is usually the busiest node.
Pass `net` (`"n3"` or `"3"`) or `port` (`"S"`, `"c3"`, `"c3.1"`) to look at one node
only, `include_unconnected` to silence the dangling list, and `limit` to cap the
response on a large circuit. Tunnels are reported too: they join nets by *name* with
no wire, so a tunnel label that appears on more than one net is called out in a note.

The server also advertises the rules that keep a circuit and its file usable - never
draw a wire whose ends differ in both coordinates, keep coordinates on the 10 pixel
grid, batch wires into one edit, and how to react to a circuit that is already
damaged - in the `instructions` field of the MCP `initialize` reply, and repeats the
relevant part in the description of the tools that can break a circuit
(`logisim_wire_add`, `logisim_component_add`, `logisim_file_open`,
`logisim_file_save`). Those rules exist because breaking them does not fail loudly:
it hangs, and then dies with `Java heap space` (see below).

### Typical agent session

```text
logisim_file_new
logisim_component_add   {"lib":"Wiring","type":"Pin","x":100,"y":100,"label":"A"}
logisim_component_add   {"lib":"Wiring","type":"Pin","x":100,"y":200,"label":"B"}
logisim_component_add   {"lib":"Gates","type":"XOR Gate","x":260,"y":100}
logisim_component_add   {"lib":"Gates","type":"AND Gate","x":260,"y":200}
logisim_component_add   {"lib":"Wiring","type":"Pin","x":380,"y":100,"label":"S",
                         "facing":"west","attributes":{"output":true}}
logisim_wire_add        {"points":["A","c3.1"]}      # symbolic endpoints, no coordinates
logisim_wire_add        {"points":["B",[150,200],[150,110],"c3.2"]}
logisim_wire_add        {"points":["c3.0","S"]}
logisim_analyze_truth_table                          # -> S = A xor B, ...
```

Wires accept `[x, y]`, a pin/component label (`"A"`), a component id (`"c3"`) or
`"<id>.<port>"` (`"c3.1"`), so an agent rarely has to compute coordinates.

`logisim_wire_add` also takes `paths`, a list of several such paths, which are all
committed as one undoable edit. Prefer it when drawing more than one wire: Logisim
repairs the whole circuit's wiring on every edit, so `n` calls cost `n` repairs.

## Damaged files, hangs and "Java heap space"

A wire whose two ends differ in **both** `x` and `y` (a diagonal wire) makes Logisim
unusable, and the symptoms are a very long pause followed by
`OutOfMemoryError: Java heap space`. The cause is in Logisim itself, not in this
layer:

* `WireIterator` walks from one end to the other in 10 pixel steps and stops only when
  it lands on the far end. For a diagonal wire the two coordinates are never reached
  at the same time, so `hasNext()` is always true and iteration never ends.
* `CircuitTransaction.execute()` ends every edit by running `WireRepair`, whose
  `doOverlaps` iterates every point of every wire and stores each one in a `HashMap`.
  An endless walk therefore allocates without bound until the heap dies.
* Opening a file runs that same wire repair (`XmlCircuitReader` is a transaction), so a
  file containing such a wire can no longer be opened by **any** Logisim process.

A diagonal wire gets created by Logisim's own wire repair: `Wire.contains` accepts a
location up to 2 units off a wire's line, and `WireRepair.doSplits` then keeps that
off-line location as the end of a new sub-wire. This is reproducible: draw a wire from
`(100,100)` to `(200,100)` and add a pin at `(150,102)`, and the file ends up with the
wires `(100,100)->(150,102)` and `(150,102)->(200,100)`. Off-grid ports (from splitters
or from components placed off the 10 pixel grid) are enough to trigger it.

This layer defends against it in four places:1. **Opening** a file scans it as text first (read only) and refuses to load one that
   contains such a wire, naming the line and the exact L shaped replacement. Without
   this, the open would hang and finally fail with `Java heap space`.
2. **Before** every edit that adds or changes something, the target circuit is checked,
   and the edit is refused with the same actionable message instead of hanging.
3. **After** every edit, any wire that Logisim's repair introduced is split into two axis
   aligned segments through a corner, which preserves the connections. The report is
   visible as `last_wire_repair` in `logisim_status`.
4. **Before** every save, the same repair runs, so a file written by this layer can
   always be opened again.

`logisim_circuit_describe` reports `skewed_wire_count` and marks each such wire with
`"broken": true`. Removing such a wire by hand (`logisim_wire_remove`) is always
allowed, since that is already a cure; `logisim_circuit_repair_wires` does both the
removal and the reconnection. `logisim_status` reports heap usage under `memory`, so
memory pressure is visible before it becomes fatal.

A file damaged by an *older* agent session (or by the GUI) still has to be rescued
outside Logisim, because Logisim cannot load it: replace the diagonal `<wire>` line in
a text editor with the two axis aligned segments it stood for. Nothing else in the file
needs to change.

## Transports

| Transport | How | Use it for |
|---|---|---|
| `stdio` (default, headless) | newline delimited JSON-RPC on stdin/stdout | the normal MCP client setup; the client starts the process |
| `http` | `--transport http [--host 127.0.0.1] [--port 7331]`, one JSON-RPC message per POST to `/mcp` | letting a client attach to a Logisim that is already running, which is how GUI mode works |
| `tcp` | `--transport tcp [--host 127.0.0.1] [--port N]` | debugging with any JSON-RPC client, or a raw socket bridge |

For stdio the server takes over `stdout` for the protocol and redirects
Logisim's own diagnostics to `stderr`, because Logisim prints to `stdout` in
about a hundred places and none of that may corrupt the protocol stream.

### Streamable HTTP

`--transport http` serves MCP's HTTP transport: the client `POST`s one JSON-RPC
message to `http://127.0.0.1:7331/mcp`, the server answers with one
`application/json` body, hands out an `Mcp-Session-Id` on `initialize` and echoes
it afterwards. Notifications are answered `202`, `GET` is answered `405` (this
server emits no server initiated messages, so there is no event stream), `DELETE`
ends the session, a request whose `Accept` asks only for an event stream is
answered `406`, and a request that does not come from this machine is answered
`403` (the DNS rebinding check the specification asks for). VS Code configures it
with a URL rather than a command:

```json
{
  "servers": {
    "logisim": { "type": "http", "url": "http://127.0.0.1:7331/mcp" }
  }
}
```

The server stays up until its stdin is closed or the process is stopped; the port
can be chosen with `--port`. A launcher that hands the process **no stdin at all** - a
Windows GUI-subsystem exe, `javaw`, a service, a scheduled task - is recognised (the first
read fails, or reports end-of-file straight away) and the server then serves until the
process is stopped instead of exiting on the spot, which is what a packaged exe needs.

**Why HTTP and not stdio once a GUI is involved.** A window must not be the child
of an MCP client: clients restart, and they kill the processes they started, which
would take the user's unsaved work with it. With HTTP the user owns the lifetime of
Logisim and a client can attach, drop and reattach freely. (There is a second,
practical reason on Windows: an executable built with a Windows GUI header has no
usable stdio, so a client could not talk to it that way at all.) Headless mode keeps
stdio as its default, so nothing about the existing setup changes.

## Running inside the Logisim window (live mode)

```bash
java -cp "build/classes;.;libs/*" com.cburch.logisim.Main -mcp --with-gui [--port 7331]
```

`--with-gui` starts the server **next to the normal Logisim startup**: the window opens
exactly as it always does, and the agent works on the project the user is looking at
instead of on a project of its own. The transport is HTTP (see above); the port defaults
to 7331 and the URL is printed on stderr at startup. Point a client at it with
`{"type": "http", "url": "http://127.0.0.1:7331/mcp"}`. `--with-gui` also opens the MCP
window so the address is visible.

What the user sees, without reading a line of JSON:

* every edit repaints the canvas immediately, in the circuit on screen;
* the components and wires of the last call become the **selection**, so they are visibly
  highlighted (the selection is replaced, not accumulated, so the agent never fights the
  user for it);
* each call is a named, undoable step in the Edit menu;
* simulation values change colour live when the agent pokes inputs or ticks the clock;
* opening a file brings up a new window; the activity log (also shown by
  `logisim_gui_status`) records every call with its outcome and duration.

### The MCP window

**Help | MCP Server…** opens it, and it holds everything MCP related - one window, three
tabs:

| Tab | What is there |
|---|---|
| **Server** | whether the server is stopped or listening, the URL, whether a client is attached, requests and tool calls so far, the address and port, **Start** / **Stop** / **Copy URL**, and an **auto-start** checkbox |
| **Connect** | a ready-made configuration for your client, built from the address the server actually answers on: pick the client, then **Copy** (or **Save…** to write `mcp.json` / `claude_desktop_config.json`) |
| **Control** | the **read-only** and **pause** switches (with the reason the agent is shown while paused) and a line summarising both |
| **Activity** | the tail of every tool call with its outcome and duration, refreshed once a second, with **Clear** |

The **Connect** tab exists because the fiddly part of attaching an agent is the client's own
configuration file, not the server. It offers, for the live address:

* **VS Code** - the `servers` form, for `.vscode/mcp.json` or the user-level config
  (Command Palette ▸ *MCP: Open User Configuration*);
* **Claude Desktop** - the `mcpServers` form with an `npx mcp-remote` bridge, since that client
  speaks stdio;
* **Any MCP client** - the same HTTP configuration under the `mcpServers` key;
* **A client that launches Logisim** - a headless stdio entry (`java -jar ... -mcp`, or the
  classpath form when running from `build/classes`), for clients that start the server themselves;
* **Prove the server answers** - a `curl` one-liner for Git Bash, WSL or a POSIX shell.

Each choice says where the snippet goes and what to do afterwards, and the snippets follow the
address: start or stop the server and they are rebuilt, so the port is never guessed.

The settings - auto-start, port, read-only at start, paused at start - are remembered in
the platform preference store under the MCP package's own node, so they apply to the next
session and need no command line. With **auto-start** on, simply launching Logisim brings
the server up (one line on stderr, no window); a newly created session then starts
read-only or paused if you asked for that, which is a safe way to let an agent look
before it is allowed to touch anything.

A pause or read-only state set **from the window** belongs to the user: the agent is told
to ask them to release it, and `logisim_gui_pause` cannot undo it.

### The tools that only exist with a window

| Tool | What it does |
|---|---|
| `logisim_gui_status` | mode, transport and address, windows, current circuit, selection, read-only, paused, activity tail. Also works headless, where it reports `mode: headless` |
| `logisim_gui_windows` | every open window with its file, unsaved-changes flag, current circuit and selection |
| `logisim_gui_focus` | bring a window (and one of its circuits) to the front, so the user watches the right thing |
| `logisim_gui_select` | highlight components/wires by reference, without changing anything |
| `logisim_gui_deselect` | clear the selection |
| `logisim_gui_read_only` | the user's switch: while it is on, every tool that would change the project is refused |
| `logisim_gui_pause` | the user's brake: while it is on, tool calls are refused — except `logisim_gui_status` and the two switches themselves, so a pause is never a trap. A pause set from the Logisim window can only be released there, not by the agent |

### What live mode refuses

* `logisim_file_new` and `logisim_file_close`: they would discard the user's document.
* `logisim_file_recover`: it is a headless rescue (it loads a separate project in memory).
* Any edit while read-only or paused, with a message saying which of the two it is.

`logisim_file_open` opens a **new window** (and focuses it if the file is already open,
rather than showing Logisim's "lose your changes?" dialog); `logisim_file_save` uses
Logisim's own Save when the project already has a file, and needs an explicit `path`
otherwise, so no file chooser ever appears without the user asking for one.

### Two behaviours worth knowing

* Every call runs on Swing's event dispatch thread, with a 30 second deadline. If the
  user has a dialog open, the call fails with a message saying so instead of hanging the
  agent or freezing Logisim.
* A heavy analysis (a truth table enumerates every row) blocks the window while it runs.
  That is accepted as honest feedback; the tool documents the row limit it enforces.

## VS Code

`.vscode/mcp.json` in this workspace already registers the server:

```json
{
  "servers": {
    "logisim": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", ".../Logisim-Fork/target/Logisim-jar-with-dependencies.jar", "-mcp"]
    }
  }
}
```

Build the jar first with `mvn package -DskipTests`, then start the server from the
MCP view in VS Code (or any MCP client).

## Guarantees

* **The circuit on disk is only touched by Logisim itself.** `logisim_file_open`
  uses `Loader.openLogisimFile`, and `logisim_file_save` uses `Loader.save`. The
  MCP layer contains no XML writing of its own, so a saved file is exactly what
  Logisim would have written.
* **Nothing is written at all unless you ask**: editing tools only change the
  in-memory project.
* **No existing Logisim behaviour was changed.** The MCP layer is a new package
  (`com.cburch.logisim.mcp`) built on public Logisim API only; the edits to existing
  code are the dispatch at the top of `Main.main` (which runs only with `-mcp`), one
  item in the Help menu, and the `doc/` tree being packaged into the jar.
* **All of the agent's instructions live in one file**, `McpInstructions.java`: the text
  returned by `initialize`, every tool description, and the wording of the refusals an
  agent may hit. Nothing an agent reads is written anywhere else, so the briefing can be
  reviewed and reworded in one place.
* **Edits are undoable.** Every edit is committed as one Logisim `Action`, so
  `logisim_undo` works exactly like Edit → Undo in the GUI.
* **Deterministic simulation.** The project's background `Simulator` thread is
  switched off for MCP sessions, so values change only when a tool asks for
  propagation or a clock tick.

## Notes and limitations

* Logisim runs headless in MCP mode (`java.awt.headless=true`). This is
  deliberate: a Swing dialog would block forever with nobody to answer it, so
  instead such failures surface as normal tool errors.
* A newly added input `Pin` is tri-state, so an undriven input reads `x`. Pass
  `attributes: {"tristate": false}` for an undriven input to read `0`.
  `'x'` means unknown/undriven, `'e'` means error (undriven net, conflicting
  drivers, or Logisim's default of erroring on a gate with undefined input).
* `logisim_analyze_truth_table` follows Logisim's own analysis code, including
  its convention of treating every pin as one bit, and it runs `2^inputs`
  simulations, so it refuses to run above `max_inputs` (default 10, hard limit 16).
* `logisim_analyze_expression` reports expressions derived by minimising the
  *truth table* (`OutputExpressions` + `Implicant`), which is exactly what the
  Expression, Minimized and Karnaugh-map tabs of Logisim's Analyze Circuit window
  show. Logisim's separate *structural* expression synthesis
  (`Analyze.computeExpression`) is commented out in this fork; it is not needed for
  this and nothing was changed to get the result.
* `logisim_component_move` does not drag wires, because Logisim does not either;
  the response reports how many wires were left dangling.

## Roadmap

Done since this list was written: **interactive mode** (item 1 — the server now runs inside
the GUI window over HTTP, with the MCP window for the switches), **net level introspection**
(item 2, as `logisim_circuit_nets`, built on a union-find walk rather than the package private
bundle map, so no bridge class was needed), and **documentation** (`logisim_help`, with the
manual packaged into the jar and the library's own port names in `logisim_component_catalog`).

Still open:

1. **Single step propagation** — `Propagator.step()` / `isPending()` are package
   private; a bridge would allow delay accurate stepping, which is how signal
   races are debugged.
2. **Memory and register tools** — `MemContents`, `Ram.loadImage` and
   `InstanceLogger` already provide everything needed to load a program image and
   dump registers, which is the heart of CPU debugging.
3. **Visualisation** — render a circuit to PNG off screen with
   `ComponentDrawContext` + `ImageIO` so a vision capable agent can look at the
   schematic.
4. **Karnaugh map rendering** — the implicants behind `logisim_analyze_expression`
   are already returned as minterm groups, which is what a K-map draws; a `grid`
   shaped view (one cell per row, per output) would make it directly readable.
5. **Expression in, circuit out** — `OutputExpressions.setExpression` accepts a
   boolean expression and recomputes the truth table from it, so a
   `logisim_analyze_from_expression` tool would let an agent check a formula
   without drawing it first.

## Building

```bash
cd Logisim-Fork
mvn package -DskipTests      # produces target/Logisim-jar-with-dependencies.jar
```

On the `agents/mcp-support-2.15.0.1` branch there is no Maven to run (that commit
predates it); see *Building on 2.15.0.1* at the end of this file.

Note for contributors: the VS Code Java extension compiles into `target/classes`
with the Eclipse compiler, which can leave classes that contain unresolved
compilation errors. Maven then considers them up to date and packages them. If a
build behaves strangely, delete `target/classes` (or run `mvn clean`) before
`mvn package`.

## Building on 2.15.0.1

The branch `agents/mcp-support-2.15.0.1` carries the same MCP layer on top of commit
`da78b00` (tag `v2.15.0.1`), so that a 2.15.0.1 installation can be driven by an
agent. The tool set, behaviour and safety rules are identical; only the build differs,
because that commit predates both Maven and any JSON dependency:

* The layout is Eclipse style: sources in `com/`, resources in `resources/`, third
  party jars in `libs/`; there is no `pom.xml` and no `src/main/java`.
* `libs/gson-2.8.9.jar` was added - the same version the Maven build on `master`
  uses - because the MCP layer needs JSON and 2.15.0.1 had no JSON dependency.
* `gui/log/ComponentSelector.java` had one line that a modern `javac` rejects:
  `(Enumeration<? extends TreeNode>) Collections.enumeration(Collections.emptySet())`.
  It now reads `Collections.emptyEnumeration()`, which is the fix upstream itself
  applied in a later commit.
* Build with the script, which is a plain `javac` run, and put the **project root**
  on the runtime classpath rather than `resources/`: the locale bundles are looked up
  as `resources/logisim/...`, and without that the server answers every open with
  *"No locale bundles are available"*.

```powershell
cd Logisim-Fork
.\build-mcp.ps1            # compiles into build/classes
.\build-mcp.ps1 -Clean     # ... after deleting build/
.\build-mcp.ps1 -Run       # ... then serves MCP over stdio
```

What was verified on this branch: the server reports `2.15.0.1` and exposes all 37
tools; a `.circ` file written by 2.16.2.2 opens and analyses identically (the 8 row
full adder truth table); the file it writes opens again; and the diagonal-wire
defence behaves exactly as on `master` - a pin placed 2 units off an existing wire
made Logisim create two damaged wires, which were repaired automatically, leaving
`skewed_wire_count: 0` and a `last_wire_repair` report in `logisim_status`.

Not carried over to the branch on purpose: this fork's Maven build, the editor
config, the sample `.circ` files and the workspace notes; those belong to the
`master` worktree, not to the 2.15.0.1 source line.

### Packaging: jar and exe

```powershell
.\build-mcp.ps1 -Jar                  # the two jars
.\build-mcp.ps1 -Jar -Exe             # ... and the Windows application image
.\build-mcp.ps1 -Jar -Single          # ... and one exe with the jar wrapped inside
.\build-mcp.ps1 -Jar -Single -Console # ... with a console launcher (see below)
```

| Artifact | What it is |
|---|---|
| `build/dist/Logisim.jar` | the compiled classes plus the `resources` tree and the manual (`doc/`); use it with the jars from `libs/` on the classpath (`java -cp "Logisim.jar;libs/*" com.cburch.logisim.Main`) |
| `build/dist/app/Logisim-jar-with-dependencies.jar` | the same with every dependency unpacked and a `Main-Class` manifest, so `java -jar … [-mcp]` works on its own |
| `build/dist/Logisim-ITA.exe` | **one file**, the fat jar wrapped inside it by launch4j - the same shape as the released `Compiled/Logisim-ITA.exe` - with the application icon; it uses the Java runtime installed on the machine (1.8 or newer) |
| `build/dist/Logisim/Logisim.exe` | a `jpackage` application image instead: the fat jar next to a bundled runtime, roughly 60 MB in the folder, nothing else needed on the machine |

All of them were verified by driving the MCP handshake through them (`initialize` and
`logisim_status`): each reported `2.15.0.1` and all 46 tools. The single exe was
checked deliberately from a directory that does **not** contain the jar, which is what
proves the jar really is inside it.

The **manual is packaged with the application** (`doc/`, all five languages, about
4.5 MB): the GUI's Help menu resolves `doc/doc_en.hs` from the classpath, and
`logisim_help` reads the same tree, so both work from a jar or an exe with no source
tree anywhere near it. Verified by running the fat jar from an empty directory and
reading `libs/mem/flipflops.html` through `logisim_help`, which reported its source as
the jar itself. A build made with an older script has no manual in it, and
`logisim_help` says so instead of failing obscurely.

Two things to know. A Windows launcher built without `-Console` is a GUI subsystem
executable and therefore has no stdin/stdout. That is fine for the server: started as
`Logisim-ITA.exe -mcp --transport http --port 7331` it notes there is no stdin to watch and
serves until the process is stopped (verified with the plain, non-console exe - also from a
foreign working directory). Only `-mcp` over **stdio** needs a console, so there either use
`-Console`, run the fat jar with `java -jar`, or point the client at `--transport tcp`. And
`-Exe` needs JDK 14 or newer for `jpackage`, while `-Single` needs the launch4j tooling: it
is not part of this tree (the 2.16.2.2 branch gets it through Maven), so the script stages it
out of the local Maven cache - `net.sf.launch4j:launch4j` plus its win32 workdir and
`com.thoughtworks.xstream:xstream`. Nothing is downloaded.

Upstream releases are built differently: `master` and the 2.16.2.2 branch use Maven
(`maven-jar-plugin`, `maven-assembly-plugin` and `launch4j-maven-plugin`, main class
`com.cburch.logisim.Main`) and produce the committed `Compiled/Logisim-ITA.jar` and
`Compiled/Logisim-ITA.exe`. Those are upstream's release files and were left alone.
