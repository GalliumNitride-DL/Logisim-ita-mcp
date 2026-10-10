# Logisim MCP — implementation status and plan

Project-level view of the MCP (Model Context Protocol) work on this fork: what exists, what is proven, what
is planned, and what is waiting on a decision. Tool-level reference lives in
[`Logisim-Fork/MCP.md`](Logisim-Fork/MCP.md).

*This file is tracked in git on `agents/mcp-support-2.15.0.1` (it was an untracked file before and was lost
by a workspace clean, which is why it now lives in the repository).*

---

## 1. Where this stands

| | |
|---|---|
| Workspace | `D:\gzr\HUST2024\AiC\Logisim`, branch **`agents/mcp-support-2.15.0.1`**, HEAD `6d13ef0` |
| Base | commit `da78b00` (tag `v2.15.0.1`) — Eclipse layout, no Maven, no JSON dependency |
| Branches | `agents/mcp-support-2.15.0.1` (here, Logisim **2.15.0.1**), `agents/mcp-support-2.16.2.2` (`e47c347`, same layer on **2.16.2.2**), `master` = upstream `ad7c02a`. Nothing is pushed anywhere. |
| Entry point | `com.cburch.logisim.Main -mcp` (headless) or `-mcp --with-gui` (inside the window); options `--transport stdio\|tcp\|http`, `--port`, `--host`, `--file f.circ`, `--help` |
| Code | `Logisim-Fork/com/cburch/logisim/mcp` (21 files) + `.../mcp/tools` (14 files) |
| Existing code touched | `Main.main` (the `-mcp` dispatch and the GUI hook), `gui/menu/MenuHelp.java` (one item, `Help | MCP Server…`), `gui/log/ComponentSelector.java` (one line, upstream's own later fix); plus the new `gui/main/SelectionBridge.java` and `gui/mcp/McpFrame.java` |
| Tools | **54** (see §3.1) |
| Build | `Logisim-Fork/build-mcp.ps1` → `Logisim-Fork/build/classes`; see §7 |
| Packaging | `build-mcp.ps1 -Jar -Exe -Single [-Console]` → jars and two kinds of Windows exe (§3.3) |
| Editor setup | root `.classpath` + `.vscode/settings.json` describe the layout; JDT output is `Logisim-Fork/build/jdt` |

Editing always goes through `CircuitMutation` + `Project.doAction`, so **every agent change is an ordinary
undoable Logisim action**. Reading and writing files goes through `Loader` / `Loader.save`, so the on-disk
format is exactly what Logisim itself produces.

---

## 2. The failure that shaped the design (contained, not cured)

A real agent session (building the 32-bit RISC-V ALU, no longer in the workspace) ended in a minutes-long
hang followed by `OutOfMemoryError: Java heap space`. Root cause, proven from source and reproduced live:

1. A wire whose two ends differ in **both** `x` and `y` (a "diagonal wire") is fatal: `WireIterator` walks
   from one end to the other in 10-pixel steps and stops only when it lands on the far end, which never
   happens on a diagonal, so `hasNext()` is always true.
2. `CircuitTransaction.execute()` ends **every edit** with `WireRepair`, whose `doOverlaps` iterates every
   point of every wire while filling a `HashMap` → unbounded allocation → the pause and the OOM.
3. **Loading a file also runs that repair** (`XmlReader` ends with `new XmlCircuitReader(...).execute(null)`),
   while saving does not — which is why a damaged file gets written successfully and is afterwards
   unopenable by any Logisim process.
4. **Logisim creates such wires itself**: `Wire.contains` accepts a location up to **2 units off** a wire's
   line and `WireRepair.doSplits` keeps that off-line location as a sub-wire end. Reproduced live: a wire
   `(100,100)-(200,100)` plus a pin at `(150,102)` produced `(100,100)->(150,102)` and
   `(150,102)->(200,100)`. The GUI has the same exposure (`AvoidanceMap`, `Connector` also walk wire points).

The MCP layer cannot fix Logisim's internals, so it refuses to let the damage spread (§3.4).

---

## 3. Implemented

### 3.1 Tools (51)

| Group | Tools |
|---|---|
| Session | `logisim_status` (server, file, current circuit, simulation, heap, `last_wire_repair`), `logisim_undo`, `logisim_circuit_info` |
| Files | `logisim_file_new`, `logisim_file_open`, `logisim_file_recover`, `logisim_file_save`, `logisim_file_save_as`, `logisim_file_close`, `logisim_file_info` |
| Discovery | `logisim_library_list`, `logisim_component_catalog` (types, attributes, option lists, and every port's **name, direction, width, offset and `ref`/`ref_by_name`**, for every component in every library - including ones whose pins the library computes itself, such as a splitter; `terse` for names only) |
| Documentation | `logisim_help` (the bundled manual: index, search, page) |
| Circuits | `logisim_circuit_list`, `logisim_circuit_describe` (components, ports, wires, `skewed_wire_count`, `broken` flags), `logisim_circuit_create`, `logisim_circuit_delete`, `logisim_circuit_set_main`, `logisim_circuit_select`, `logisim_circuit_stats`, `logisim_circuit_nets` (nets: wires, ports, labels, drivers, unconnected ports, tunnel notes, `problems_only`, `compact`) |
| Checks | `logisim_circuit_lint` (shorts, net faults, tunnel labels, facing, coordinates, overlaps - each finding with a severity and the tool that fixes it) |
| GUI (live mode) | `logisim_gui_status`, `logisim_gui_windows`, `logisim_gui_focus`, `logisim_gui_select`, `logisim_gui_deselect`, `logisim_gui_read_only`, `logisim_gui_pause` |
| Picture | `logisim_screenshot` (PNG of a circuit or a region, drawn offscreen so it works headless and with a window open; `scale`, `max_pixels`, `print_view`, `transparent`, `path`) |
| Editing | `logisim_component_add` (`on_unknown_attribute`, `dry_run`), `logisim_component_add_many` (a list of the same objects, one undo step, all or nothing), `logisim_component_duplicate` (`dx`/`dy`/`count`, `attributes` for the copies only), `logisim_component_remove`, `logisim_component_move`, `logisim_component_set_attributes`, `logisim_wire_add` (`points` / `paths` / `from`+`to`, auto-routing, `dry_run`, `joins`), `logisim_wire_remove` (`wire` / `from`+`to` / `loc`) |
| Simulation | `logisim_sim_reset`, `logisim_sim_set_input`, `logisim_sim_set_inputs`, `logisim_sim_read`, `logisim_sim_read_all`, `logisim_sim_propagate`, `logisim_sim_tick`, `logisim_sim_trace` (the sequence table of signals over n cycles), `logisim_sim_status`, `logisim_sim_run_vectors` (all with `radix`, and `parsed` echoing how each value was read) |
| Memory | `logisim_component_memory` (read, write and clear the contents of a ROM or a RAM) |
| Analysis | `logisim_analyze_truth_table`, `logisim_analyze_expression`, `logisim_circuit_build` |
| Repair | `logisim_circuit_repair_wires` |
| Self test | `logisim_self_test` (fourteen checks in a session of its own, ~1 s, a report of what failed) |

### 3.2 Behaviour

- **Symbolic references** everywhere: `[x,y]`, a label (`"A"`), a component id (`"c3"`), a port (`"c3.1"`),
  a **port named after the library** (`"c3.Q"`, `"c3.clock"`, `"FA0.Cout"`) — an agent rarely computes
  coordinates, and ever since ports carry `ref`/`ref_by_name` it never has to.
- **Mistakes answered at the moment they happen**: an endpoint that touches no port and no wire is reported
  with the nearest port named (`"the nearest port is c1.2 at (300,300), 20 units away (…, output, 8 bits)"`);
  a wire that passes through a port names the wire id and the `logisim_wire_remove {loc: …}` repair; a
  reference that matches two ports is refused with both listed.
- **Deterministic simulation**: the background `Simulator` thread is stopped; `setInput` fires
  `fireInvalidated()` so values actually take effect.
- **Headless-safe errors**: no modal dialogs; every failure is `isError: true` with the next step in the text.
- **Analysis from the truth table** (`OutputExpressions` + `Implicant`), because `Analyze.computeExpression`
  is commented out in this fork — the same path the GUI's Analyze window uses.
- **Two transports**, three ways in: stdio (client owns the process), Streamable HTTP (client attaches to a
  running Logisim; `POST /mcp`, session id, 202/405/204/406/403 answered to specification) and TCP; one
  interface behind all of them. `System.out` is redirected to stderr in MCP mode.
- **Activity log**: every tool call is recorded with its outcome and duration (`ActivityLog`), which is what
  the MCP window (`Help | MCP Server…`) shows the user.
- **Dry runs**: `logisim_component_add` and `logisim_wire_add` take `dry_run`, returning ports, refs, routed
  segments, wire ids and warnings while committing nothing, so a layout can be checked before it is drawn.
- **Batch simulation**: `logisim_sim_run_vectors` applies a list of vectors in one call and returns a table,
  with optional `expect` per vector for pass/fail.
- **Conveniences that remove hand-work**: a splitter's `bit_ranges` shorthand (`{"0-31": 0}`) replaces
  thirty-two attributes, and an attribute a component does not have is skipped and listed under
  `ignored_attributes` instead of losing the whole call (`on_unknown_attribute: "fail"` restores refusal).
- **Machine-readable rules** in the MCP `instructions` field, all of them now living in
  `McpInstructions.java`: a `READ THIS FIRST` block (no diagonal wires, the 10-pixel grid, no negative
  coordinates, batch edits, how to react to a damaged circuit or file, heap and `skewed_wire_count` checks,
  verify electrically, do not guess what a pin means) and a `WIRING STYLE` block (tunnels for anything long
  or shared, wires for a few segments; face tunnels east or west, ground and power away from the wire they
  feed; place components before wiring). The tools that can break a circuit repeat the rule they enforce.
- **A wire says which net it joined.** An endpoint that lands on an existing wire - or within the two units
  Logisim's own wire test accepts, which used to be described wrongly as "connects nothing" - comes back
  under `joins` naming the wire id and the net it becomes part of, with that net's labels and drivers; a
  segment drawn along an existing wire is reported the same way, and `dry_run` reports it before drawing.
- **Values are echoed as they were understood.** `logisim_sim_set_input`/`set_inputs` return `parsed`
  (`{"given": "0x00000003", "understood_as": "0x00000003"}`), reads carry `hex`, and from four bits up
  `dec` and `signed` (a 32-bit `0xFFFFFFFE` reports `signed: -2`); `radix` renders the same value as
  `text`. A value that cannot be read is refused rather than becoming 0.
- **Two faults are refused rather than warned about**, because neither can be repaired afterwards: a
  circuit placed inside itself (the propagator recurses until the stack overflows, and Logisim's own
  `Dependencies.canAdd` already refuses it in the GUI), and a wire point within two units of an existing
  wire but not on it (Logisim splits the wire there and keeps the off-grid location, which is how the
  diagonal wire of §2 is created; `logisim_wire_remove {loc}` stays permissive, since removing it is the
  cure). An off-grid point elsewhere is refused with `on_off_grid: "warn"`/`"allow"` as the escape.
- **A call says when the circuit itself has become the problem**, because neither the heap nor the size
  of a circuit is visible to an agent: `logisim_status` reports `heap_used_percent` and `heap_pressure`,
  above four fifths of the heap *every* tool result carries the warning with the numbers and the `-Xmx`
  suggestion, a call that took five seconds or more says how long and what to batch instead, and a
  mutating call on a circuit with more than 400 components or 800 wires warns before the next one. The
  advice never changes a result, and a failure in producing it can never fail a call.

### 3.3 Packaging (added on this branch)

| Switch | Output |
|---|---|
| `-Jar` | `build/dist/Logisim.jar` (classes + `resources/` + the manual), `build/dist/app/Logisim-jar-with-dependencies.jar` (dependencies unpacked, runs with `java -jar`) |
| `-Exe` | `build/dist/Logisim/Logisim.exe` (jpackage app-image, carries its own runtime; needs JDK 14+) |
| `-Single` | `build/dist/Logisim-ITA.exe` (launch4j, fat jar wrapped inside, needs Java 8+; tooling staged from the local Maven cache, nothing downloaded) |
| `-Console` | makes the exe launchers console ones, which MCP over **stdio** needs; HTTP needs no console |

Three packaging faults were found and fixed here: the manual was not in the jars at all (so the GUI's Help
menu failed in a packaged build — `doc/` is now copied in, all five languages, ~4.5 MB), the `-Single` step
assigned a path to the script's own `[switch]$Exe` parameter (PowerShell variable names are case-insensitive,
which threw after a successful build), and a Windows GUI-subsystem exe was given no stdin, which the HTTP
transport read as "stop" so the packaged exe exited immediately; it now recognises the case and serves until
the process is stopped.

### 3.4 Defences against the diagonal-wire failure

| # | Defence | Where |
|---|---|---|
| 1 | **Refuse to open** a file containing such a wire, naming the line and the exact axis-aligned replacement (read-only text scan before `Loader` runs) | `McpSession.requireOpenable` |
| 2 | **Refuse an edit** of a circuit that contains one (removing the bad wire stays allowed, since that is the cure) | `McpSession.requireHealthyWires` + every tool in `EditTools` |
| 3 | **Repair automatically after every edit** (split the bad wire through a corner), reported as `last_wire_repair` | `McpSession.healSkewedWires` (from `commit`) |
| 4 | **Repair before every save**, reported as `wire_repair` | `McpSession.save` |
| 5 | **`logisim_circuit_repair_wires`** for explicit repair or `dry_run` inspection | `DiagTools` |
| 6 | **`logisim_file_recover`** for already-unopenable files: the damaged wire elements are rewritten *in memory* and handed to Logisim's own reader; nothing is written to disk | `McpSession.recover` |
| 7 | **Batched wiring** (`paths`) because Logisim repairs the whole circuit's wiring on every edit | `EditTools.addWire` |
| 8 | **Heap visibility** in `logisim_status` | `McpSession.memoryReport` |
| 9 | **A report, not a cure**: `logisim_circuit_lint` flags the off-grid coordinates and the port a wire runs through, which are how the damage starts | `LintTools` |
| 10 | **Refuse the creation of one**: a wire point (or a component port) within two units of an existing wire but not exactly on it is refused whatever `on_off_grid` says, because Logisim re-splits the wire on the next transaction and the automatic repair cannot converge on it | `EditTools.requireNotInexactlyOnAWire` |
| 11 | **Refuse a circuit inside itself**: a self-containing circuit cannot be simulated (`StackOverflowError` in `CircuitState.processDirtyComponents`), so `component_add`, `component_add_many` and `component_duplicate` ask Logisim's own `Dependencies.canAdd` first | `EditTools.requireNotCircular` |

### 3.5 Verified

- On 2.16.2.2 (earlier): half adder + truth table; hierarchical 4-bit adder (9+5=14, 15+15+1=31, 0+0+1=1);
  clocked D flip-flop (`D=1` + 2 ticks → `Q=1`); save/reopen; undo; TCP; `logisim_analyze_expression`;
  `logisim_circuit_build`; the damaged ALU refused in milliseconds with the exact line and fix.
- On 2.15.0.1 (this branch): clean build (679 sources); opens a `.circ` written by 2.16.2.2 and
  analyses it identically (8-row full-adder truth table); files it writes open again; the ±2 experiment
  reproduced and self-healed (`last_wire_repair` with the same two corners, `skewed_wire_count: 0`).
- **Not yet verified end-to-end: `logisim_file_recover`'s rewrite branch** — it needs a genuinely damaged
  file. The plan is for `logisim_self_test` (§5, P1.4) to build that fixture in the system temp folder, so the
  check becomes part of the smoke test instead of depending on a hand-made damaged project.
- **`logisim_circuit_nets` (P1.3) is implemented and verified** on 2.15.0.1: on a freshly wired XOR circuit it
  reports 3 nets with `drivers: 1` each (an input pin counts as the driver, an output pin does not), flags a
  stray pin under `unconnected_ports`, filters correctly by `net`/`port`, and a deliberate two-input-pin net
  reports `drivers: 2` — which the simulator then confirms by reading `e`.
- **Live mode (P2.8, the first phase) is implemented and verified** with a real window: the server runs
  inside Logisim, edits land in the open project and are highlighted on the canvas, read-only and pause
  refuse edits with a message naming which, the status read and both switches stay callable while paused,
  and `file_new`/`file_close` are refused so the user's document cannot be discarded.
- **Packaged builds serve MCP**: the fat jar was run from an empty directory and answered `initialize` with
  **48 tools**, read a manual page through `logisim_help` (reporting its own jar as the source), and the
  plain gui-header `-Single` exe served HTTP unattended.
- **The live-mode highlight**: with the window showing one circuit, an edit to another circuit no longer
  replaces the selection - it returns a hint naming both circuits and how to bring the edited one forward -
  while an edit to the displayed circuit still highlights exactly as before.
- **`Adder`'s Carry Out is corrected**, the only misleading port found in the Arithmetic and Plexers
  libraries: declared `Port.INPUT` (`Adder.java:90`) while the same file names it (line 95), draws it as
  `"c out"` (line 109) and writes it in `propagate` (line 135). `PortFixups` reports it as an output with a
  `direction_note` and counts it as a net driver; a simulation through the MCP read 0, 1, 0, 1 for the four
  canonical carry cases, and a carry net that used to look undriven now reports `drivers: 1`.
- **The library audit** (Arithmetic, 9 components; Plexers, 6) is in
  [`MCP-AGENT-FEEDBACK.md`](MCP-AGENT-FEEDBACK.md) §8: every port's direction agrees with the library's own
  name for it apart from the Carry Out, widths are consistent, and every port has a name. Two cosmetic
  findings are recorded there (a `"greator"` typo in Comparator's English text, and Adder/Multiplier giving
  both data inputs the same tooltip, so no name distinguishes them). **Memory, I/O, Gates, Wiring and TTL
  have not been audited** - the method is one catalogue call per library.
- **The feedback work (P0 and P1 of the plan in `MCP-AGENT-FEEDBACK.md`)** is implemented and verified:
  port references, nearest-port diagnostics, wire ids and `loc` repair in the short warning, lenient unknown
  attributes, `nets problems_only`, `dry_run` on both creating tools (stats unchanged), `sim_run_vectors`
  (four adder vectors: 3 passed, 1 deliberately wrong failed with the mismatching pin named), the splitter
  `bit_ranges` shorthand (`{"0-31": 0}` produced a single output), and `logisim_circuit_lint`, which reported
  14 findings on a trial circuit built to be wrong, 3 of them errors. That testing also established that a
  tunnel with no label carries the **default label `"tunnel"`**, so unlabelled tunnels join each other.
- **What lint cannot do**: its "wire through a port" finding is a backstop. Logisim splits a wire at a port
  as the commit runs, so a stored circuit normally shows a junction rather than a wire passing through the
  port; the prevention is the warning `logisim_wire_add` gives *before* committing.
- **The port metadata of all eight libraries was audited** - 79 components, including the five that had not
  been looked at before (Memory, I/O, Gates, Wiring, TTL). The full result is in
  [`MCP-AGENT-FEEDBACK.md`](MCP-AGENT-FEEDBACK.md) §8: one wrong direction in the whole project (the Adder's
  Carry Out, already corrected), no width inconsistency, and 84 ports that Logisim itself gives no name.
  Three defects *in the libraries* are recorded there and deliberately left alone: a `RAM`'s data bus loses
  its name to a misplaced assignment (`Ram.java:212-215`, a one-line swap, cosmetic), a `7402` is pinned like
  a `7400` (the fork's own later `master` already has the correct pin table), and a `Pin`'s port carries the
  toolbar tool's text as its name.
- **Four defects the audit found in our own reporting are fixed**: a `Splitter` used to report no pins at
  all - it is a `ManagedComponent`, so the catalogue never asked it for any - and now reports Logisim's own
  names for the combined end and each bit group; the catalogue and a placed component disagreed about a
  `Pin`'s direction and now share one rule; a port with no width of its own is described by a `width_note`
  instead of being printed as `0` bits; and a component whose ports cannot be listed says so (`ports_note`)
  instead of returning nothing. Re-running the audit shows every component reporting either a port list or a
  reason for not having one.
- **The third feedback round (2026-09)** - a second agent session reported eleven issues, and each was
  reproduced before anything was changed. Four had already shipped (`sim_run_vectors`, lenient attributes,
  `bit_ranges`, `dry_run`), and three of its central claims did **not** reproduce: padded hex does not parse
  as a bit pattern (eleven forms read correctly, malformed input refused), two wires that *cross* do not
  connect (`net_count` stays 2, while an endpoint landing on a wire does connect it - the distinction the
  report blurred), and `logisim_screenshot` needed no new Java interface (`ComponentDrawContext` already
  takes a `java.awt.Component`, and `ExportImage`/`Print` already render offscreen). Four real defects came
  out of it and are fixed: a `0x` literal as wide as its pin was read as a bit pattern (`"0x0"` on a 3-bit
  pin meant undefined, and `"0x00"` on 4 bits meant `00x0`); off-grid points near a wire joined nets while
  the diagnostic said "connects nothing" (defences 10, 11); and `circuit_select` asked for `name` where
  every other tool asks for `circuit`. What lint cannot do, and the audit method for the remaining checks,
  are both recorded in [`MCP-AGENT-FEEDBACK.md`](MCP-AGENT-FEEDBACK.md) §9.
- **The three new tools are verified together** (in `%TEMP%\mcp-test\test-p2.ps1`): `component_add_many`
  placed five components in one call (543-character reply) and one undo removed them, `component_duplicate`
  copied a pin twice with the copies' own ids and ports and refused a zero step, and `logisim_screenshot`
  returned an `image/png` block beside its text - the image shows the pins with their values, the labels,
  the wires and the junction dot - with `region`, `scale`, `transparent`, `path` and the `max_pixels`
  refusal all exercised. The three P0/P1 regression suites were re-run against the 51-tool build: the same
  14 lint findings on the trial circuit, `nets problems_only` and both dry runs unchanged, and the 79-
  component catalogue audit still reporting a port list or a reason for every one.
- **`logisim_self_test` is implemented and green on 2.15.0.1**: fourteen checks in about 1.2 s
  (`ok: true, check_count: 14, passed: 14`), run against a second session so nothing of the open project
  is touched. Running it found a defect in its own trace fixture within the first minute - a D
  flip-flop's data input has no name in Logisim, so the fixture had wired the complement to the output -
  which is the behaviour the tool exists for. It also **closed the one verification gap left open**
  (§3.5, `logisim_file_recover`'s rewrite branch): the check writes a `.circ`, rewrites one axis-aligned
  wire element into a diagonal one, and confirms that `logisim_file_open` refuses it with the reason and
  `logisim_file_recover` rescues it with `skewed_wire_count: 0`. Subsets work (`checks: ["simulate"]`),
  and a failing run keeps its scratch project in the temp directory.
- **`logisim_component_memory`** reads, writes and clears the words of a ROM and a RAM. Verified: a ROM
  accepted three words (`changed: 3`), read them back on the address, survived `logisim_undo` (the first
  word returned to `0x00`) and survived a save/close/reopen round trip; a RAM accepted words and a `fill`,
  and reported `undoable: false` with `persistence: "not saved"` - which is Logisim's own behaviour and
  was confirmed by the round trip, where the RAM came back empty. An out-of-range address and a value too
  wide for the memory (`0x1234` in a byte) are both refused with the range or the width.
- **`logisim_sim_trace`** records signals over cycles. Verified on a flip-flop whose complement feeds its
  data input: the rows alternate, `cycle: 0` is the state before the first edge (`x`), `only_changes`
  collapses seven rows to four, `radix: "hex"` reads a 32-bit value, and a signal may be named by label,
  by `"c1.1"` or by `"c1.Q"`.
- **The router grew a search and lost a bug**: the two single corners and the eight fixed lanes are
  still tried first, and a bounded search over the grid of interesting coordinates (the ends, and one or
  two grid steps either side of every port and wire end) now handles what they cannot, with collinear
  steps collapsed so a route is three wires rather than twenty. Testing it exposed a real defect in the
  old heuristics: **a corner landing exactly on a port was accepted**, which silently joined that port to
  the net - reproduced with two tunnels placed on the two natural corner positions, where the netlist
  showed the tunnel on the net; with the corner rule added, the same call routes around
  (`segments_created: 3`) and the tunnels stay `unconnected_port_refs`. Only an *interior* port was
  checked before; an endpoint landing on a port is exactly what a wire is supposed to do for the two
  ends of the route, and exactly what an intermediate corner must never do.
- **Three defects outside the new work were found and fixed while testing** (all in this layer's own
  files, nothing of Logisim's circuit logic):
  1. **`Main.getVersion()` threw on every real runtime** - `Double.parseDouble` on `1.8.0` (or `21.0.2`)
     fails with *multiple points*, so `Main`'s static initialiser died and **the server could not start at
     all** on this machine (`java` on PATH is 1.8). The leading `major.minor` is now read, which is what
     the nine `JAVA_VERSION < 10.0` guards actually ask about. This is why the earlier sessions' run
     instructions could not have worked: it is a one line defect that made the whole feature unusable.
  2. **A component id could not be resolved after `logisim_file_open`** until something happened to index
     the circuit, so `{"component": "c1"}` on a freshly opened file answered *no component 'c1'* - and
     every tool that takes a reference was affected, not just the new one. `findById` now hands out the ids
     (which is idempotent, and uses the same order `logisim_circuit_describe` reports) before giving up.
  3. **The size and heap advice** described below, which turned the two failure modes this project has
     actually suffered into something a call says out loud.

---

## 4. Workspace files that were lost (and what can be restored)

| File | Status |
|---|---|
| `MCP-PLAN.md` (this file) | was untracked and was cleaned away; **recreated and now tracked** |
| `riscv-alu.circ` | removed from the workspace. Recoverable: VS Code local history holds it as `%APPDATA%\Code\User\History\-521fb5ab\zmUJ.circ` (resource `.../Logisim/riscv-alu.circ`), and a copy `riscv_alu.circ` sits in the Recycle Bin (deleted from `C:\Users\ZhuoruiGan\WorkBuddy\2026-09-18-13-37-03`) |
| `riscv-session.md` | removed; not in git, no snapshot found |
| `mcp-generated-*.circ` (3 sample circuits) | removed; not referenced by any doc, and regenerable at any time through the server |
| `Logisim-Fork/target/` | stale **2.16.2.2** fat jar, still locked by an orphaned `java -jar …` MCP server process; delete once that process is gone (it is gitignored) |

---

## 5. Planned

### P0 — waiting on a decision (touch existing Logisim logic)

1. **Stop `WireRepair` from creating non-axis-aligned wires** (only split at points exactly on the wire;
   never build a merge result that is diagonal). Removes the root cause and, unlike the MCP layer, also
   protects the GUI.
2. **Make `WireIterator` terminate** even for a malformed wire (two lines). Safety net: without it, a file
   damaged by an older session or by the GUI can never be opened again.

### P1 — next safe work (no decisions needed)

**Done since this list was written** (learned from watching agents use the server, see `MCP.md`):

* **`logisim_help`** — reads Logisim's own manual (guide and library reference) offline, in three modes:
  index, search, page. The manual is now packaged into the jar and the exe by `build-mcp.ps1`, which also
  fixes the GUI's Help menu there; before this, a packaged build had no documentation in it at all.
* **Pin meanings** — `logisim_component_catalog` now reports each port's library name (`"Clock: state updates
  on trigger"`, `"Clear: When 1, pin state to 0 asynchronously"`), direction, width and position, and the same
  names are attached to the ports of a component that `logisim_component_add` placed. The ports are built
  from a throwaway instance of the component, because most libraries compute ports per instance rather than
  per factory.
* **No negative coordinates** — `logisim_component_add`, `logisim_component_move` and `logisim_wire_add`
  refuse coordinates below zero (the component's body as well as its anchor, and every numeric wire point),
  with a message saying what to use instead, so an agent cannot put work off the visible canvas.
* **One place for the agent's briefing** — every tool description, the `initialize` instructions and the
  wording of the refusals now live in `com.cburch.logisim.mcp.McpInstructions`. Verified that the move
  changed no text: the `tools/list` output was captured before and after and compared line by line.
* **The feedback work (2026-09)** — everything P0 and P1 of `MCP-AGENT-FEEDBACK.md` marks as done: port
  `ref`/`ref_by_name` and named wire endpoints, nearest-port diagnostics for endpoints that connect nothing,
  wire ids and the `loc` repair in the pass-through warning, `logisim_wire_remove {loc: …}`, lenient unknown
  attributes (`ignored_attributes`), `logisim_circuit_nets {problems_only: true}`,
  `logisim_component_add`/`logisim_wire_add` `dry_run`, `logisim_sim_run_vectors`, a splitter's `bit_ranges`,
  `logisim_circuit_lint`, the corrected Carry Out direction (`PortFixups`), and the live-mode highlight fix.
  Deferred with reasons in that document: `logisim_component_add_many` (the pain it addressed went away with
  lenient attributes) and a compact catalogue mode (the port lists are already gated on a small result set).

3. **`logisim_circuit_nets`** — each net with its ports, labels and width, plus which port drives it.
   **Implemented and verified** (see §3.5); it walks the circuit directly rather than reaching into Logisim's
   package-private bundle map, so nothing was added inside an original package.
4. **`logisim_self_test`** — **DONE this round**, as designed below (fourteen checks in a session of its
   own, ~1.2 s, a subset runnable by name, the scratch project kept when something fails). It closed the
   `logisim_file_recover` gap as planned and immediately found one defect in its own fixture. One call
   that builds a known circuit, checks a truth table, exercises batched
   wiring, a dry run, the lint, the catalogue and a save/load round trip, and returns pass/fail. The repo has
   no tests, so this doubles as a smoke test for every future change, including the packaging switches. It is
   also the only way to close the one verification gap left (the rewrite branch of `logisim_file_recover`,
   §3.5), by building the damaged fixture in the temp directory instead of resurrecting `riscv-alu.circ`.
   **Designed in full below.**
5. **Memory contents** — **DONE this round**: `logisim_component_memory` reads, writes and clears a ROM's
   or a RAM's words and says where the words live (a ROM keeps them in an undoable attribute that the file
   carries, a RAM in simulation state that is neither undoable nor saved - Logisim's own behaviour,
   confirmed by a round trip). ALU/CPU work needs it and the fork already stores them as an
   `addr/data` attribute.
6. **Better router** — **DONE this round**, as a bounded A* search over the grid of interesting
   coordinates (the ends, and a grid step or two either side of every port and every wire end), tried only
   where the two single corners and the eight fixed lanes fail, with collinear steps collapsed and a
   corner that lands on a port refused. Logisim's `AvoidanceMap` was not used: it serves the GUI's
   interactive tool, and the search answers the same question with no coupling to it.
7. **Size/time guards** — **DONE this round**: `heap_pressure` in `logisim_status`, a heap warning with a
   `-Xmx` suggestion appended to every result once the heap is four fifths full, an explanation on a call
   that took five seconds or more, and a warning on a mutating call to a circuit with more than 400
   components or 800 wires. Verified on a 452 component circuit.

#### `logisim_self_test` in detail (P1.4)

**What it is for.** One call answering "is this server, in this build, in this packaging, still working?" -
the throwaway harnesses in `%TEMP%\mcp-test` made permanent, and the only way to close the last verification
gap.

**Where the work happens.** In a scratch project of the server's own, never in the user's. The tool builds a
second `LogisimFile` through `Loader` (so the on-disk format is Logisim's), runs the checks against that,
and restores whatever was open in a `finally` under the session lock. What it writes goes to the system temp
directory and is deleted unless `keep_files: true` - which is what you want when a check fails. It touches
nothing on screen, so it works headless and with a window open, and it never saves over a user's file.

**The checks**, each reported as `{name, ok, detail, ms}`:

| Check | What it proves |
|---|---|
| `tools` | the registry registers the expected tools - a tool that fails to register is otherwise invisible |
| `catalogue` | a `Splitter` reports its five named ports, a `Probe` reports a `width_note`, the `Adder`'s Carry Out is `output` with `direction_note` - the library audit's fixes, pinned by a test |
| `build` | a known circuit (two input pins, an 8-bit `Adder`, an output pin, a splitter) placed and wired in one batched edit, with the negative-coordinate guard refusing a point below zero |
| `dry_run` | a dry run leaves `circuit_stats` unchanged |
| `simulate` | `logisim_sim_run_vectors` over the adder's carry cases, which is what the corrected Carry Out direction has to survive |
| `analysis` | `analyze_truth_table` and `analyze_expression` agree with the circuit that was built |
| `nets` | the carry net reports one driver, i.e. the `PortFixups` correction still holds |
| `lint` | a deliberately wrong circuit produces the expected kinds (`tunnel_label_default`, `off_grid`, `unconnected_port`) |
| `save/load` | the file is read back with the same component, wire and port counts and the same truth table |
| `recover` | a damaged `.circ` written to the temp directory is refused by `file_open` with the line named, then opened by `file_recover` with `skewed_wire_count: 0` - **the gap that is still open** |
| `docs` | `logisim_help` returns a manual page, which is exactly what a jar that lost `doc/` cannot do |

**Response and cost.** `{ok, checks, failed, temp, summary}`, with `verbose: false` (the default) returning
only the failures, so a healthy run costs a few lines. Arguments: `checks` (run a subset), `verbose`,
`keep_files`, `temp_dir`. One call, a few hundred milliseconds.

**How it will be verified.** Headless over stdio and over HTTP, then from the packaged jar and the single
`-Console` exe (where the `docs` check earns its place); and each check will be shown to be able to fail -
remove `doc/` from the jar, sabotage the `Adder` correction, corrupt the fixture further - so that a green
run means something.

### P2 — the interactive goal

8. **`-mcp --with-gui`: embed the server in the GUI process**, so an agent drives the same live project the
   user is looking at. Needs a thread-safety pass (every `Project`/`Circuit` access marshalled onto the EDT
   with `SwingUtilities.invokeAndWait`), no `System.exit` from the MCP path, and dialog-safe error handling.
   **Designed in [`MCP-GUI-DESIGN.md`](MCP-GUI-DESIGN.md)** — phased, with the exact Logisim-side additions
   named (an MCP window, a preferences tab, the `--with-gui` entry point), all of them additive UI/plumbing
   and inert unless `-mcp` is passed. The open decisions are settled (new window per opened file, no window
   closing or new-project by the agent, start stopped, read-only off by default, highlighting replaces the
   selection, a freeze during heavy analysis is accepted, grouped undo deferred), and the transport is fixed
   as **HTTP only with a window**, because a GUI must not be the child of a client and Windows GUI exes have
   no stdio. The HTTP transport itself is already implemented and verified, and live mode too: the server
   runs inside the window, its switches live in the MCP window (`Help | MCP Server…`), and the settings
   (auto-start, port, read-only, pause) are remembered.
9. **GUI-side health**: a "Repair broken wires" menu item and an on-open check, so the GUI can rescue a
   damaged file by itself (a safety net once P0.1 lands).
   (The main part of this item — the MCP window with start/stop, the port, read-only, pause, the activity log
   and the Connect tab — is Phase 2 of [`MCP-GUI-DESIGN.md`](MCP-GUI-DESIGN.md) and is **done**: it is one
   window under `Help | MCP Server…`, and its settings (auto-start, port, read-only at start, paused at
   start) live in that window and in the platform preference store rather than in `AppPreferences`, so
   Logisim's own settings machinery and resource bundles were left alone.)
10. **Observability**: `logisim_screenshot` (**done** - a PNG of a circuit or a region, returned in the reply's
    image block and optionally written to `path`) and `logisim_sim_trace` (values of named pins over n ticks,
    i.e. sequence tables for clocked circuits).

### P3 — polish

11. Truth-table export (CSV/LaTeX), more `logisim_analyze_expression` formats, K-map output.
12. Document the MCP server in `README.md`, and keep `MCP.md` in step with the packaging switches.
13. Multi-file projects (`Loader.openLogisimFile(file, substitutions)`) and library substitutions.

### Not planned (and why)

- **Writing `.circ` XML directly from the layer** — Logisim's reader/writer owns the format. The one
  exception is the *in-memory* rewrite in `logisim_file_recover`; what reaches disk is written by Logisim.
- **Raising the Java level or adding dependencies on master** — 2.16.2.2 builds on Java 8 with Gson already
  present. (On 2.15.0.1 the extra `libs/gson-2.8.9.jar` is required because that commit had no JSON
  dependency at all.)
- **Editing a user's file without asking** — `riscv-alu.circ` was left alone, and nothing is restored into
  the workspace without a decision.

---

## 6. Immediate next steps

1. **Commit the work.** Two verified rounds are uncommitted: the P0/P1 feedback round and this one
   (`logisim_self_test`, `logisim_component_memory`, `logisim_sim_trace`, the router search with the
   corner-on-port fix, the size and heap advice, and the three defects found while testing - the
   `Main.getVersion()` crash that stopped the server starting at all, the unresolvable component id after
   `logisim_file_open`, and the corner that landed on a port). It is a large but self-contained and
   verified change set; nothing should start on top of it.
2. **Decide on the three library findings** ([`MCP-AGENT-FEEDBACK.md`](MCP-AGENT-FEEDBACK.md) §8.5). Two
   are worth a decision: a `RAM`'s data bus loses its name to a misplaced assignment (one-line swap, no
   effect on simulation), and a `7402` is pinned like a `7400`, which the fork's own later `master` has
   already corrected. Both are Logisim's own files, so they stay untouched until you say otherwise. The
   third - a `Pin`'s port named after the toolbar tool - is cosmetic.
3. **Decide P0.1 / P0.2** (the two core fixes: `WireRepair` must never build a non-axis-aligned wire, and
   `WireIterator` must terminate on one) — only then can the GUI be made safe by itself.
4. **Then pick by intent**, on the agent capability axis, in the order the recorded friction suggests:
   `logisim_connect` (tunnels versus wires, facing and label decided by the tool rather than by prose in
   the instructions - [`user-mcp-problems.md`](user-mcp-problems.md) items 1 and 3), undo checkpoints
   (`logisim_checkpoint`, `logisim_undo` to a checkpoint), a `logisim_circuit_diff`, an annotated
   screenshot that draws each `ref` on the picture, truth-table and vector export, and the protocol
   breadth items of the round-4 review (resources, prompts, tool annotations, progress and cancellation).
6. Housekeeping: the orphaned `java -jar …\target\Logisim-jar-with-dependencies.jar -mcp` process, if it is
   still alive, holds a stale **2.16.2.2** build and locks `Logisim-Fork/target/`; the folder can go once it
   is gone.

---

## 7. Build, run and editor notes

```powershell
cd Logisim-Fork
.\build-mcp.ps1                 # compile into build/classes
.\build-mcp.ps1 -Clean -Run     # ... after deleting build/, then serve MCP over stdio
.\build-mcp.ps1 -Jar -Exe -Single -Console   # packaging (see §3.3)
.\build-mcp.ps1 -Single         # one exe, jar wrapped inside
```

- The **project root must be on the runtime classpath** (`-cp "build\classes;.;libs/*"`): locale bundles are
  looked up as `resources/logisim/...`, and without it every open answers *"No locale bundles are
  available"*.
- Do **not** pass `--release 8`: the tree calls JDK 10+ APIs (`getMenuShortcutKeyMaskEx`, `viewToModel2D`)
  behind `Main.JAVA_VERSION` run-time guards, so it must compile against a modern API.
- `.class` files **inside `com/`** are stale output from before the editor output path was fixed (the old
  `Logisim-Fork/.classpath` had `output=""`, i.e. the project root). They are deleted, they are gitignored
  (`*.class`), and new compiles go to `Logisim-Fork/build/jdt` — so they should not come back.
- Verify the server without a client: pipe newline-delimited JSON-RPC (`initialize`,
  `notifications/initialized`, then `tools/call`) into
  `java -Xmx512m -cp "build\classes;.;libs/*" com.cburch.logisim.Main -mcp`; answers go to stdout, logs to
  stderr.

---

## 8. File map

| Path | What |
|---|---|
| `Logisim-Fork/com/cburch/logisim/mcp/` | protocol, server, transports, session, instructions, port fixups, documentation reader (21 files) |
| `Logisim-Fork/com/cburch/logisim/mcp/tools/` | the 48 tools (13 files) |
| `Logisim-Fork/build-mcp.ps1` | build, run and packaging |
| `Logisim-Fork/MCP.md` | tool reference, transports, guarantees, limitations, packaging notes |
| `Logisim-Fork/libs/gson-2.8.9.jar` | JSON dependency that 2.15.0.1 did not have |
| `.classpath`, `.vscode/settings.json` | how the Eclipse layout is resolved in VS Code (output: `Logisim-Fork/build/jdt`) |
| `.vscode/mcp.json` | VS Code MCP client registration (runs the class build) |
| `MCP-PLAN.md` | this file |
| `MCP-AGENT-FEEDBACK.md` | the agent-feedback proposal, its priorities, and the library audit |
| `MCP-GUI-DESIGN.md` | the live-mode design and the decisions behind it |
| `Compiled/` | upstream's released `Logisim-ITA.jar` / `.exe` (untouched) |
