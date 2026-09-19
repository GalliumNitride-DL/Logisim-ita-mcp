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
| Code | `Logisim-Fork/com/cburch/logisim/mcp` (21 files) + `.../mcp/tools` (13 files) |
| Existing code touched | `Main.main` (the `-mcp` dispatch and the GUI hook), `gui/menu/MenuHelp.java` (one item, `Help | MCP Server…`), `gui/log/ComponentSelector.java` (one line, upstream's own later fix); plus the new `gui/main/SelectionBridge.java` and `gui/mcp/McpFrame.java` |
| Tools | **48** (see §3.1) |
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

### 3.1 Tools (48)

| Group | Tools |
|---|---|
| Session | `logisim_status` (server, file, current circuit, simulation, heap, `last_wire_repair`), `logisim_undo`, `logisim_circuit_info` |
| Files | `logisim_file_new`, `logisim_file_open`, `logisim_file_recover`, `logisim_file_save`, `logisim_file_save_as`, `logisim_file_close`, `logisim_file_info` |
| Discovery | `logisim_library_list`, `logisim_component_catalog` (types, attributes, option lists, and each port's **name, direction, width, offset and `ref`/`ref_by_name`**) |
| Documentation | `logisim_help` (the bundled manual: index, search, page) |
| Circuits | `logisim_circuit_list`, `logisim_circuit_describe` (components, ports, wires, `skewed_wire_count`, `broken` flags), `logisim_circuit_create`, `logisim_circuit_delete`, `logisim_circuit_set_main`, `logisim_circuit_select`, `logisim_circuit_stats`, `logisim_circuit_nets` (nets: wires, ports, labels, drivers, unconnected ports, tunnel notes, `problems_only`) |
| Checks | `logisim_circuit_lint` (shorts, net faults, tunnel labels, facing, coordinates, overlaps - each finding with a severity and the tool that fixes it) |
| GUI (live mode) | `logisim_gui_status`, `logisim_gui_windows`, `logisim_gui_focus`, `logisim_gui_select`, `logisim_gui_deselect`, `logisim_gui_read_only`, `logisim_gui_pause` |
| Editing | `logisim_component_add` (`on_unknown_attribute`, `dry_run`), `logisim_component_remove`, `logisim_component_move`, `logisim_component_set_attributes`, `logisim_wire_add` (`points` / `paths` / `from`+`to`, auto-routing, `dry_run`), `logisim_wire_remove` (`wire` / `from`+`to` / `loc`) |
| Simulation | `logisim_sim_reset`, `logisim_sim_set_input`, `logisim_sim_set_inputs`, `logisim_sim_read`, `logisim_sim_read_all`, `logisim_sim_propagate`, `logisim_sim_tick`, `logisim_sim_status`, `logisim_sim_run_vectors` |
| Analysis | `logisim_analyze_truth_table`, `logisim_analyze_expression`, `logisim_circuit_build` |
| Repair | `logisim_circuit_repair_wires` |

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
4. **`logisim_self_test`** — one call that builds a known circuit, checks a truth table, exercises batched
   wiring and a save/load round trip, and returns pass/fail. The repo has no tests, so this doubles as a
   smoke test for every future change, including the packaging switches.
5. **Memory contents** — read/write ROM/RAM contents (`logisim_component_memory`); ALU/CPU work needs it and
   the fork already stores them as an `addr/data` attribute.
6. **Better router** — replace the two-corner + Z-lane heuristics in `logisim_wire_add` with a grid search
   guided by Logisim's own `AvoidanceMap`, so dense wiring needs no hand-made steering points.
7. **Size/time guards** — warn (or refuse) when one edit would touch a very large circuit, and surface the
   `-Xmx` hint when the heap is tight.

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
10. **Observability**: `logisim_screenshot` (PNG of a circuit or region) and `logisim_sim_trace` (values of
    named pins over n ticks, i.e. sequence tables for clocked circuits).

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

1. **Commit the current work** — the P0 and P1 feedback items above, plus the refreshed `MCP.md`, the plan and
   `MCP-AGENT-FEEDBACK.md`. It is a large but self-contained and verified change set; the next item should not
   start on top of it.
2. **`logisim_self_test`** (P1.4 below) — one call that builds a known circuit, checks a truth table,
   exercises batched wiring, a dry run, the lint and a save/load round trip, and returns pass/fail. It turns
   the throwaway harnesses in `%TEMP%\mcp-test` into something permanent, covers the packaging switches, and
   is the only remaining way to close the `logisim_file_recover` gap: build the damaged fixture in the system
   temp folder rather than resurrecting `riscv-alu.circ` (§4).
3. **Audit the remaining five libraries** (Memory, I/O, Gates, Wiring, TTL) with the method recorded in
   `MCP-AGENT-FEEDBACK.md` §8 — one catalogue call per library, then confirm anything suspicious in the source
   and by simulation.
4. **Then pick by intent**: `logisim_sim_trace` with memory contents if the next work is clocked or CPU-like,
   or `logisim_screenshot` if an agent should be able to look at a schematic (§5, P2.10).
5. **Decide P0.1 / P0.2** (the two core fixes) — only then can the GUI be made safe by itself.
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
