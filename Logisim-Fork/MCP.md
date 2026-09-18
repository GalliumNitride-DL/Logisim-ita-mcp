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
| Circuits | `logisim_circuit_list`, `logisim_circuit_create`, `logisim_circuit_delete`, `logisim_circuit_set_main`, `logisim_circuit_select`, `logisim_circuit_describe`, `logisim_circuit_stats` |
| Editing | `logisim_component_add`, `logisim_component_remove`, `logisim_component_move`, `logisim_component_set_attributes`, `logisim_wire_add`, `logisim_wire_remove` |
| Simulation | `logisim_sim_reset`, `logisim_sim_set_input`, `logisim_sim_set_inputs`, `logisim_sim_read`, `logisim_sim_read_all`, `logisim_sim_propagate`, `logisim_sim_tick`, `logisim_sim_status` |
| Analysis | `logisim_analyze_truth_table`, `logisim_analyze_expression`, `logisim_circuit_build` |
| Repair | `logisim_circuit_repair_wires` |

Every tool returns JSON. Errors come back as `isError: true` with a message that
says what to do next (for example *"a Pin has no attribute 'nonsense'; its
attributes are: facing, output, width, ..."*), because an agent has to be able to
recover on its own.

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
| `stdio` (default) | newline delimited JSON-RPC on stdin/stdout | the normal MCP client setup; the client starts the process |
| `tcp` | `--transport tcp [--host 127.0.0.1] [--port N]` | attaching to an already running Logisim (see roadmap), or debugging with any JSON-RPC client |

For stdio the server takes over `stdout` for the protocol and redirects
Logisim's own diagnostics to `stderr`, because Logisim prints to `stdout` in
about a hundred places and none of that may corrupt the protocol stream.

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
  (`com.cburch.logisim.mcp`) built on public Logisim API only; the single edit to
  existing code is a four line dispatch at the top of `Main.main` that runs only
  when the `-mcp` flag is present.
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

1. **Interactive mode** — run the same server inside the normal Logisim GUI so an
   agent can work on the circuit the user is looking at, over TCP. The transport
   abstraction and the tool layer are already transport independent; what is
   missing is starting the server from the GUI startup path and marshalling edits
   onto the Swing event thread.
2. **Net level introspection** — a `net_list` / `net_trace` tool built on
   `CircuitWires`' bundle map (`BundleMap` is package private today, so this needs
   a small bridge class inside `com.cburch.logisim.circuit`).
3. **Single step propagation** — `Propagator.step()` / `isPending()` are package
   private; a bridge would allow delay accurate stepping, which is how signal
   races are debugged.
4. **Memory and register tools** — `MemContents`, `Ram.loadImage` and
   `InstanceLogger` already provide everything needed to load a program image and
   dump registers, which is the heart of CPU debugging.
5. **Visualisation** — render a circuit to PNG off screen with
   `ComponentDrawContext` + `ImageIO` so a vision capable agent can look at the
   schematic.
6. **Karnaugh map rendering** — the implicants behind `logisim_analyze_expression`
   are already returned as minterm groups, which is what a K-map draws; a `grid`
   shaped view (one cell per row, per output) would make it directly readable.
7. **Expression in, circuit out** — `OutputExpressions.setExpression` accepts a
   boolean expression and recomputes the truth table from it, so a
   `logisim_analyze_from_expression` tool would let an agent check a formula
   without drawing it first.

## Building

```bash
cd Logisim-Fork
mvn package -DskipTests      # produces target/Logisim-jar-with-dependencies.jar
```

Note for contributors: the VS Code Java extension compiles into `target/classes`
with the Eclipse compiler, which can leave classes that contain unresolved
compilation errors. Maven then considers them up to date and packages them. If a
build behaves strangely, delete `target/classes` (or run `mvn clean`) before
`mvn package`.
