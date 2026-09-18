# Watching the agent work: GUI mode for the Logisim MCP server

Design proposal, for review before implementation. Companion to
[`MCP-PLAN.md`](MCP-PLAN.md) (status/roadmap) and [`Logisim-Fork/MCP.md`](Logisim-Fork/MCP.md)
(tool reference).

---

## 1. What we want

The user should be able to **watch the circuit change while an agent works on it**, in the normal
Logisim window:

* edits appear as they happen, in the circuit the user is looking at;
* the circuit being worked on is brought to the front, and the parts just touched are highlighted;
* simulation values change colour live while the agent pokes inputs and ticks the clock;
* every agent action shows up in the Edit menu as `Undo <description>`, so the user can revert it;
* the user keeps control: pause the agent, restrict it to reading, or stop the server.

## 2. Why today's server cannot do this

`java -cp … com.cburch.logisim.Main -mcp` runs a **separate headless process**:

1. `McpMain.run()` sets `java.awt.headless=true` before anything touches AWT;
2. `McpSession` builds its **own** `Loader` / `LogisimFile` / `Project`, so it has its own copy of the
   world. The agent's edits are invisible; the user's window knows nothing about them;
3. tools run on the transport thread, which is fine with no GUI but is not allowed to touch Swing or the
   Logisim model once a window exists — Swing and `Project`/`Circuit` must be used from the **event
   dispatch thread (EDT)**.

So the change is not "add a few tools"; it is "run the server **inside** the GUI process and make every
model access go through the EDT".

## 3. Target architecture

```
MCP client ── stdio (client spawns the GUI) ─┐
           ── http  (user started the GUI) ──┤
                                            ▼
                                   MCP transport thread
                                            │ tool call
                                            ▼
                                     EdtGate (invokeAndWait + timeout)
                                            │
                                            ▼
                                 EDT: McpSession in live mode
                                            │
                    Project / LogisimFile / Circuit  (owned by the GUI)
                                            │
                                            ▼
                     Frame / Canvas repaint, selection, undo stack
```

* **Two modes, one server.** `-mcp` (headless, unchanged) and `-mcp --with-gui` (new, live).
* **Live project resolution.** In live mode the session does *not* own a project. Each call resolves the
  current one from the open windows: `JFrame.getFrames()` filtered to
  `com.cburch.logisim.gui.main.Frame`, then `frame.getProject()` / `frame.getCanvas()`. "Current" = the
  focused Logisim window, else the most recently focused one (the MCP layer tracks focus through a
  `WindowFocusListener`; no Logisim change). Several windows are supported, and a tool lists them so the
  agent can ask for a specific one.
* **Everything on the EDT.** A small `EdtGate` runs each tool body on the EDT. When the EDT is busy with a
  modal dialog, a bounded wait returns a clear error ("Logisim is showing a dialog; dismiss it and retry")
  instead of wedging the agent forever. No tool ever calls `System.exit`, and no tool opens a dialog.
* **Transports.** In GUI mode the server is **HTTP only** (`--transport http`, default port 7331;
  `POST /mcp`, implemented already and verified independently of the GUI). stdio is deliberately not offered
  with a window, and the reasons are not stylistic:
  1. **A window must not be the child of a client.** MCP clients restart, and they kill the processes they
     started — that would take the user's unsaved work with it. With HTTP the user owns Logisim's lifetime
     and a client can attach, drop and reattach freely.
  2. **Windows GUI executables have no usable stdio.** A client could not talk to the released `.exe` that
     way at all.

  Headless mode keeps stdio as its default, so the existing client setup is untouched. (If a stdio GUI is
  ever wanted for a console build, it can be added later without disturbing anything.)
* **No circuit semantics change.** Not one line of wire/circuit/simulation logic is touched: the agent uses
  the *same* actions the GUI uses, through `Project.doAction`.

## 4. Changes to the MCP layer (no Logisim edits needed)

| # | Change | Notes |
|---|---|---|
| 1 | `ProjectTarget` abstraction in `McpSession`: an owned project (headless) or the live GUI project (resolved per call) | the tools already funnel through `McpSession`, so this is contained |
| 2 | `EdtGate`: `invokeAndWait` marshalling, bounded wait, error mapping | the single most important piece for safety |
| 3 | Live-mode file tools: `open`/`new` delegate to `ProjectActions.doOpen(...)` / `doNew(...)` so a **new window** appears for the user; `save`/`save_as` keep using `Loader.save` + `setFileAsClean`; `file_new` refused unless the user allows it (it would discard the open document); `close` only if allowed | the user sees files open and save in their own UI |
| 4 | New GUI tool group: `logisim_gui_status` (mode, window, circuit, read-only, paused, client), `logisim_gui_windows`, `logisim_gui_focus` (raise a window/circuit), `logisim_gui_select` (highlight by reference), `logisim_gui_deselect` | built on public API: `project.setCurrentCircuit(...)`, `canvas.getSelection().addAll(...)`, `canvas.getSelection().clearSelected()` |
| 5 | Auto-highlight: after each mutating call, select the components/wires it touched | this is what makes "watching" obvious |
| 6 | Read-only and pause switches honoured centrally in the tool registry | the user can freeze or restrict the agent |
| 7 | `instructions` update for live mode: the user is watching, keep edits small and legible, focus the circuit being changed, prefer one batched edit | aligns the agent with the new situation |

Confirmed while implementing: highlighting uses `Selection.addAll(...)`, and deselecting is
`SelectionBase.clear(CircuitMutation)`, which is package private - the public-looking
`SelectionActions.clear/drop/dropAll` remove the components from the *circuit* instead. A
five-line additive bridge (`gui/main/SelectionBridge.java`) widens that access, and refuses to
clear while the user has components in mid air (a paste or drag not yet dropped).

## 5. Changes to Logisim itself (the exception you approved)

All of these are **additive UI/plumbing only**; none touches circuit, wire, simulation, file-format or
existing-behaviour code paths, and all are inert unless `-mcp` is passed.

| # | Change | File(s) | Why | Size |
|---|---|---|---|---|
| A | Accept `--with-gui`: start the server on a background thread, then continue into the normal GUI startup | `Main.java` (a few lines), `McpMain.java` (new mode) | entry point for live mode | tiny |
| B | An **"MCP Server…" window**: state (stopped / listening on host:port / client connected / tool count), Start and Stop buttons, port field, **Read-only** toggle, **Pause** button, and a log tail of the last tool calls | new `com/cburch/logisim/gui/mcp/*`, plus one item in the Tools menu (`gui/menu/MenuTools.java` or `MenuHelp.java`) | the user needs to see and control the server; Logisim has no status bar, and the MCP client's own log is in another application | new package + one menu line |
| C | An **MCP tab** in Preferences (enable, transport, port, allow file operations, highlight edits, log level), persisted | new `gui/prefs/McpOptions.java`, one entry in the `panels` array in `gui/prefs/PreferencesFrame.java`, a few `AppPreferences` attributes | persist settings and make the feature discoverable | small |
| D | *(optional, later)* an overlay in `Canvas` to draw an "agent is working here" marker | `gui/main/Canvas.java` | prettier than using the real selection | skip for now |
| E | *(optional, later)* an activity line inside the main window | `gui/main/Frame.java` | keeps everything in one window | skip for now |

Deliberately **not** touched: `Wire`, `WireIterator`, `WireRepair`, `Circuit*`, the simulator, the file
format, existing menus and shortcuts, and the default startup path (no `-mcp`, no difference).

## 6. What the user actually sees

| Agent action | What appears on screen |
|---|---|
| add / remove / move / wire | the canvas repaints immediately (Canvas already listens to `CircuitEvent`), and the touched items become the selection |
| switch circuit | the window switches to that circuit's tab |
| simulate (`set_input`, `tick`, `propagate`) | wire and pin colours change live, exactly as if the user had poked them |
| open a file | a new Logisim window appears with that project |
| every call | one line in the MCP window's log; one `Undo <description>` entry in the Edit menu |
| needs attention | the agent's error text is in the tool result the user can read in chat |

## 7. Concurrency and safety rules

1. **EDT only.** Every tool body runs on the EDT; the transport thread never touches the model.
2. **Bounded waits.** A blocked EDT yields an error, not a hang.
3. **One client.** A second connection is refused with a message naming the current one.
4. **User overrides.** Read-only blocks all mutating tools; Pause makes tool calls return "the user paused
   the agent"; both are visible in the MCP window.
5. **Collisions are naturally serialised.** The user's and the agent's actions both happen on the EDT as
   separate actions, so they interleave cleanly and are individually undoable. No new locking.
6. **Heavy analysis is the one open problem.** `logisim_analyze_truth_table` and friends run `2^inputs`
   simulations; on the EDT that would freeze the window for seconds. Options: (a) accept it with a busy
   cursor and document it; (b) run analysis on a private copy of the circuit off the EDT (needs a cheap
   circuit copy — a `Loader` round trip is not cheap); (c) run it on the EDT but only when the user is idle.
   Recommendation: (a) for Phase 1 (with the `max_inputs` cap already in place), then (b) if it hurts.
7. **Unsaved work is never discarded** by an agent unless the user explicitly allows file operations.

## 8. Phases

**Phase 1 — live mode without touching Logisim (DONE).**
Implemented: the `--with-gui` entry point (`Main` hands the stripped command line to the normal
startup and the server runs on a background thread), live project resolution from the focused window,
the `EdtGate` (30 s deadline, reported as an ordinary tool error), auto-highlighting of what each call
touched, the seven `logisim_gui_*` tools, live-mode file rules (`open` opens a window or focuses an
existing one, `save` uses Logisim's Save, `new`/`close`/`recover` are refused), and the `READ THIS
FIRST` block for live mode in the server instructions. The only Logisim-side addition needed was one
five-line bridge (`gui/main/SelectionBridge.java`) to widen access to the existing deselect, which is
the kind of exception agreed for exposing data.

Verified live (window open, HTTP on 7331): status reports `live` with one window; an edit lands in the
open project; the touched components become the selection (an add selects the pin, a wire call
replaces it with the new wire); read-only and pause both refuse edits with a message naming which; the
status read and the two switches stay callable while paused (a fix found by testing: the pause used to
block the tools needed to release it, which made it a trap); `file_new` and `file_close` are refused;
the activity log records each call.

*Verify next:* the same in a hand-started window with a client attached over HTTP after a client
restart, a modal dialog open (expect the deadline error), and two windows open (focus switching).

**Phase 2 — the user-facing controls (needs the core additions B and C).**
MCP window (state, Start/Stop, port, read-only, pause, log tail), MCP preferences tab, and the
Streamable HTTP transport so a hand-started Logisim can be attached to.

**Phase 3 — polish.** `logisim_screenshot` and per-tool permissions; expose the current circuit/schematic as
an MCP *resource* so an agent can "look" at it; optional "agent is here" overlay; an optional
`begin_task`/`end_task` pair to group a burst of edits into one undo entry.

## 9. Decisions (agreed)

1. **Agent opens a file** → a **new window** (never replaces the user's document).
2. **The agent may not close windows or start a new empty project** (that would discard unsaved work); both
   are refused with an explanatory error unless the user enables them.
3. **A hand-started GUI serves nothing until the user starts it** from the MCP window (the setting is
   remembered). Until that window exists, `--transport http` on the command line is the way in.
4. **Read-only is off by default** — editing allowed, with Pause as the instant brake and every action
   undoable.
5. **Highlighting replaces the selection** with the items the last call touched; it does not accumulate.
6. **Heavy analysis may freeze the window.** The user accepts this: a complex circuit is expected to cost
   time, and the freeze is honest feedback rather than a hidden stall. No off-EDT analysis work is planned.
   *(Implemented as: truth table calls keep their `max_inputs` cap and run on the EDT. If it turns out to be
   painful in practice, revisit.)*
7. **Grouped undo** — deferred. Revisit if a burst of edits turns out to be awkward to undo one by one.

**Transport** — agreed: HTTP only in GUI mode, with the reasoning in §3; headless keeps stdio.

## 10. Testing

* Headless mode must stay byte-for-byte identical in behaviour (existing smoke tests + the netlist checks).
* Live-mode tests need a display: drive the GUI from a scripted MCP client, then assert through
  `logisim_gui_status` / `logisim_circuit_nets` that the model changed as expected, that the selection holds
  the expected components, and that a following `logisim_undo` restores the previous state.
* Failure paths to cover explicitly: modal dialog open (bounded error), no window open, two windows open,
  client disconnect while the GUI lives on, user pressing Pause mid-call, user deleting the agent's
  selection, agent pointed at a window whose project is dirty and unsaved.
