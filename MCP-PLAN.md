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
| Workspace | `D:\gzr\HUST2024\AiC\Logisim`, branch **`agents/mcp-support-2.15.0.1`**, HEAD `bdc2086` |
| Base | commit `da78b00` (tag `v2.15.0.1`) — Eclipse layout, no Maven, no JSON dependency |
| Branches | `agents/mcp-support-2.15.0.1` (here, Logisim **2.15.0.1**), `agents/mcp-support-2.16.2.2` (`e47c347`, same layer on **2.16.2.2**), `master` = upstream `ad7c02a`. Nothing is pushed anywhere. |
| Entry point | `com.cburch.logisim.Main -mcp` (also `--transport tcp --port N`, `--file f.circ`, `--help`) |
| Code | `Logisim-Fork/com/cburch/logisim/mcp` (13 files) + `.../mcp/tools` (9 files) |
| Existing code touched | `Main.main` (6-line dispatch), `gui/log/ComponentSelector.java` (one line, upstream's own later fix) |
| Tools | **37** (see §3) |
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

### 3.1 Tools (37)

| Group | Tools |
|---|---|
| Session | `logisim_status` (server, file, current circuit, simulation, heap, `last_wire_repair`), `logisim_undo`, `logisim_circuit_info` |
| Files | `logisim_file_new`, `logisim_file_open`, `logisim_file_recover`, `logisim_file_save`, `logisim_file_save_as`, `logisim_file_close`, `logisim_file_info` |
| Discovery | `logisim_library_list`, `logisim_component_catalog` (libraries, types, attributes, option lists) |
| Circuits | `logisim_circuit_list`, `logisim_circuit_describe` (components, ports, wires, `skewed_wire_count`, `broken` flags), `logisim_circuit_create`, `logisim_circuit_delete`, `logisim_circuit_set_main`, `logisim_circuit_select`, `logisim_circuit_stats` |
| Editing | `logisim_component_add`, `logisim_component_remove`, `logisim_component_move`, `logisim_component_set_attributes`, `logisim_wire_add` (`points` / `paths` / `from`+`to`, auto-routing), `logisim_wire_remove` |
| Simulation | `logisim_sim_reset`, `logisim_sim_set_input`, `logisim_sim_set_inputs`, `logisim_sim_read`, `logisim_sim_read_all`, `logisim_sim_propagate`, `logisim_sim_tick`, `logisim_sim_status` |
| Analysis | `logisim_analyze_truth_table`, `logisim_analyze_expression`, `logisim_circuit_build` |
| Repair | `logisim_circuit_repair_wires` |

### 3.2 Behaviour

- **Symbolic references** everywhere: `[x,y]`, a label (`"A"`), a component id (`"c3"`), a port (`"c3.1"`),
  a subcircuit pin (`"FA0.Cout"`) — an agent rarely computes coordinates.
- **Deterministic simulation**: the background `Simulator` thread is stopped; `setInput` fires
  `fireInvalidated()` so values actually take effect.
- **Headless-safe errors**: no modal dialogs; every failure is `isError: true` with the next step in the text.
- **Analysis from the truth table** (`OutputExpressions` + `Implicant`), because `Analyze.computeExpression`
  is commented out in this fork — the same path the GUI's Analyze window uses.
- **Two transports** (stdio, TCP) behind one interface; `System.out` is redirected to stderr in MCP mode.
- **Machine-readable rules**: the MCP `instructions` field carries a `READ THIS FIRST` block (no diagonal
  wires, 10-pixel grid, batch edits, how to react to a damaged circuit/file, heap and `skewed_wire_count`
  checks, verify electrically), and the four tools that can break a circuit repeat the relevant rule.

### 3.3 Packaging (added on this branch)

| Switch | Output |
|---|---|
| `-Jar` | `build/dist/Logisim.jar` (classes + `resources/`), `build/dist/app/Logisim-jar-with-dependencies.jar` (dependencies unpacked, runs with `java -jar`) |
| `-Exe` | `build/dist/Logisim/Logisim.exe` (jpackage app-image, carries its own runtime; needs JDK 14+) |
| `-Single` | `build/dist/Logisim-ITA.exe` (launch4j, fat jar wrapped inside, needs Java 8+; tooling staged from the local Maven cache, nothing downloaded) |
| `-Console` | makes the `-Exe` / `-Single` launcher a console one, which stdio MCP needs |

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

### 3.5 Verified

- On 2.16.2.2 (earlier): half adder + truth table; hierarchical 4-bit adder (9+5=14, 15+15+1=31, 0+0+1=1);
  clocked D flip-flop (`D=1` + 2 ticks → `Q=1`); save/reopen; undo; TCP; `logisim_analyze_expression`;
  `logisim_circuit_build`; the damaged ALU refused in milliseconds with the exact line and fix.
- On 2.15.0.1 (this branch): clean build (1202 classes); 37 tools; opens a `.circ` written by 2.16.2.2 and
  analyses it identically (8-row full-adder truth table); files it writes open again; the ±2 experiment
  reproduced and self-healed (`last_wire_repair` with the same two corners, `skewed_wire_count: 0`).
- **Not yet verified end-to-end: `logisim_file_recover`'s rewrite branch** — it needs a genuinely damaged
  file. §6.1 says how to finish this.

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

3. **`logisim_circuit_nets`** — each net with its ports, labels and width, plus which component pin drives it.
   The single most useful debugging addition: a missed port or two shorted nets become obvious instead of
   being inferred from `x` / `e` values. (`CircuitWires.BundleMap` is package private, so this needs a small
   bridge class in `com.cburch.logisim.circuit`.)
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
9. **GUI-side health**: a "Repair broken wires" menu item and an on-open check, so the GUI can rescue a
   damaged file by itself (a safety net once P0.1 lands).
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

1. **Finish verifying `logisim_file_recover`** — restore `riscv-alu.circ` from the VS Code history snapshot
   (or the Recycle Bin copy), run `logisim_file_recover` on it, expect `skewed_wire_count: 0` plus a
   `rewrites` report, then `logisim_file_save_as` a repaired copy. That both recovers the project and closes
   the last unverified feature.
2. **Implement P1.3 `logisim_circuit_nets`**, then **P1.4 `logisim_self_test`**.
3. **Decide P0.1 / P0.2** (the two core fixes) — only then can the GUI be made safe.
4. Housekeeping: kill the orphaned `java -jar …\target\Logisim-jar-with-dependencies.jar -mcp` process (it
   still runs the **2.16.2.2** build and locks `Logisim-Fork/target/`), then delete that folder.

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
| `Logisim-Fork/com/cburch/logisim/mcp/` | protocol, server, transports, session (13 files) |
| `Logisim-Fork/com/cburch/logisim/mcp/tools/` | the 37 tools (9 files) |
| `Logisim-Fork/build-mcp.ps1` | build, run and packaging |
| `Logisim-Fork/MCP.md` | tool reference, transports, guarantees, limitations, packaging notes |
| `Logisim-Fork/libs/gson-2.8.9.jar` | JSON dependency that 2.15.0.1 did not have |
| `.classpath`, `.vscode/settings.json` | how the Eclipse layout is resolved in VS Code (output: `Logisim-Fork/build/jdt`) |
| `.vscode/mcp.json` | VS Code MCP client registration (runs the class build) |
| `MCP-PLAN.md` | this file |
| `Compiled/` | upstream's released `Logisim-ITA.jar` / `.exe` (untouched) |
