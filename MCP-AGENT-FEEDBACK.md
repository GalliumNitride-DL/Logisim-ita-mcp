# What agents actually struggle with, and what to change

Two sources of feedback:

* the four problems you listed — tunnels versus direct wires, pin layout and bit widths, the facing of
  tunnels/ground/power, and a wrong highlight in live mode;
* a full session report (RV32I ALU, 45 components, 58 wires, 110 tool calls, 14/14 vectors correct) with
  seven "pains" and eleven suggestions.

Both say the same thing in different words: **the tools are good at telling an agent what it did, and weak
at telling it what it is about to do wrong.** Every complaint is either *the answer arrived too late* (the
short circuit was reported after the wire landed) or *the answer was available but not where the agent
looked* (the catalogue has the pins, but the agent guesses instead of asking). This document proposes
changes on those two axes, and nothing else.

Each claim below was checked against the source before being repeated; the two that are real bugs are
marked with the exact line.

---

## 1. Correctness first: two things we report wrongly

### 1.1 `Adder`'s Carry Out, and any port like it — verified in Logisim's own source

```java
// Logisim-Fork/com/cburch/logisim/std/arith/Adder.java:90
ps[C_OUT] = new Port(-20, 20, Port.INPUT, 1);   // declared an input, but the adder drives it
```

The port's tooltip says *"Carry Out"* and `propagate` writes it, but the declared type is `INPUT`. The
catalogue and `logisim_circuit_nets` faithfully report that type, so an agent sees Carry Out as an input
and — worse — a net driven only by a Carry Out looks undriven. That is exactly what the report noticed.
It is an upstream data error in this fork, not an MCP one, but we are the ones repeating it.

**Recommendation (MCP layer, no Logisim edit).** Add a small correction table
(`mcp/PortFixups.java`) mapping `library/type/portIndex → real direction`, consulted wherever a direction
is reported or a driver is counted (`describeComponent`, `describeFactoryPorts`, `describeNets`). Seeding
it is a two-step job that must be done honestly:

1. scan the libraries for ports declared `Port.INPUT` whose tooltip reads like an output
   (`...OutTip`, "Carry Out", "Controlled Buffer's out", …) — that gives candidates, not answers;
2. confirm each candidate by simulation: place the component, drive its inputs, propagate, and see which
   ports the component *produces* a value on. Only confirmed ports go in the table.

Anything not confirmed stays exactly as Logisim declares it, and the reply can carry
`"direction_source": "declared"|"corrected"` so the difference is visible rather than hidden.

*Alternative, rejected for now:* change `Port.INPUT` to `Port.OUTPUT` in `Adder.java`. One character, but
it changes what Logisim's conflict detection and port drawing do for that component, for every user, to fix
something the MCP can fix for itself. Offer it to upstream instead.

### 1.2 The live-mode highlight — verified in our own code

```java
// mcp/McpSession.java:239
public void finishCall() {
    ...
    if (!touched.isEmpty()) {
        GuiBridge.select(project, new ArrayList<Component>(touched));   // whatever circuit they are in
    }
```

`touched` is whichever components the call changed. If the agent edits circuit *X* while the window shows
circuit *Y* (both in the same project), the selection becomes components of *X* — and Logisim's canvas
paints highlights for the selected items at their coordinates over whatever is displayed. Hence "erroneous
information": highlights that belong to a circuit the user cannot see.

**Recommendation, in three parts** (any one alone is incomplete):

1. `McpSession.finishCall` only selects when every touched component is in the circuit the window is
   showing (`GuiBridge.displayedCircuit(project)`), and clears the selection otherwise. Never paint one
   circuit's items over another's.
2. When it does not select, the tool result gains a `hint`: *"these edits are in circuit X, which is not on
   screen; call logisim_circuit_select with circuit 'X' to watch them"* — the agent can then choose to
   bring it forward, which is what "bring them to the UI" should be: deliberate, not automatic.
3. A preference in the MCP window, **"follow the agent's circuit"** (off by default): when on, a call that
   edits a displayed-project circuit switches the window to it first, so the user watches the work. This is
   the honest version of auto-switching — the user opts in, and the window says so.

*Rejected:* switching the window silently on every call. The user may be reading another circuit on purpose,
and having the view jump under them is worse than a hint.

---

## 2. Make the answer impossible to miss (your items 1–3, report items 2, 5, 6, 9, 11)

### 2.1 Tunnels or direct wires — a rule, and a checker

Nothing in the current instructions says which to reach for. The report's own success came from choosing
"tunnel for every named bus, wire only for 20–40 pixel hops", but that was the agent's invention. Logisim's
rules, stated accurately, are:

* a **crossing is not a connection** — two wires crossing without an end there are unrelated;
* a **wire end that lands on another wire is a connection** (Logisim splits the wire there);
* a **wire passing through a port is a connection**, and that is the accident that costs the most;
* a **tunnel connects to every tunnel with the same label**, anywhere in the circuit.

So the convention to teach, in the `instructions` and repeated in `logisim_wire_add`/`component_add`:

> Connect adjacent components — a few segments — with real wires. Anything that has to travel further, or
> across other wiring, or to more than one place, uses **tunnels named after the signal**, and every tunnel
> is placed with the same facing (east/west, pointing along the flow). Mixed styles for the same net are
> allowed but hurt readability. Put every component down first, then wire, so a wire never has to cross a
> component port on the way to something that has not been placed yet.

### 2.2 `logisim_circuit_lint` — report the conventions instead of trusting the agent

A new **read-only** tool that inspects a circuit and returns findings, each with ids and a suggested fix.
This is where items 1, 2 and 3 of your list become enforceable rather than advisory:

| Finding | Why it matters |
|---|---|
| a wire passes through a port (short), with both wire ids and net names | the report's only wiring accident |
| a net with more than one driver, or none | silently wrong circuits |
| ports connected to nothing | unfinished work |
| off-grid coordinates, coordinates outside `x,y >= 0` | the diagonal-wire failure, and invisible work |
| tunnels with an empty label, or the same label used by both a tunnel and a plain wire | readability |
| tunnels facing north/south, or two tunnels on one net facing opposite ways | readability (your item 3) |
| ground/power facing against the flow, pins facing away from their wiring | readability |
| a placed component overlapping another | ambiguous schematics |

Lint is cheap to implement on top of what already exists (`describeNets`, `portInside`, the wire walk) and
carries no risk: it changes nothing. It is also the natural partner of `dry_run` below — one asks "would
this be a mistake?", the other asks "is the circuit already wrong?".

### 2.3 The agent should not need to be told to look things up — tell it at the moment it matters

The report says agents "guess and then criticise themselves". Prose in the instructions has not fixed it, so
the answer has to appear in the results of the tools that get used anyway:

* **`component_add` returns a usable reference per port**: `"ref": "c22.4"` and `"ref_by_name": "c22.Less"`,
  plus a one-line `hint`: *"wire to these references instead of computing coordinates"*. (The port *names*
  are already there since the last change; this makes them addressable.)
* **`wire_add` diagnoses endpoints that connect nothing**: if a numeric point is not a port, not on a wire,
  and not part of this path, the reply says so and names the nearest port within a few units —
  *"nothing is at (222, 240); the nearest port is c22.4 (Less, output, width 1) at (220, 240), 2 units away"*.
  This is the single highest-value change for your item 2: the mistake is caught at the moment of guessing.
* **`logisim_wire_add`'s pass-through warning carries the wire ids**, so the segment can be removed
  precisely instead of undoing the batch.
* **`logisim_component_catalog` gains a compact mode** (`ports_only`): port index → name, direction, width,
  offset, ref, with no attributes. The report's "verbose output eats context" is why agents avoid looking;
  make looking cheap.
* **`logisim_circuit_nets` gains `problems_only`**: just multi-driver nets, undriven nets and dangling
  ports. 53 nets of output for a 45-component circuit is context an agent will not spend twice.

### 2.4 Do not fail a whole call over an attribute that was never going to exist

```java
// mcp/tools/EditTools.java:126
throw new McpException("a " + factory.getName() + " has no attribute '" + name + "'; ...");
```

Arithmetic-library components and multiplexers have **no `label` attribute** (`Adder.java:80` declares only
`StdAttr.WIDTH`), so a batch of adds that carried a label failed wholesale. The report is right that this is
expensive, and right that the error text itself is good.

**Recommendation:** `logisim_component_add` (and the batch tool below) take
`on_unknown_attribute: "fail" | "warn"`, defaulting to **`warn`**: the component is placed, and the reply
lists `ignored_attributes` so nothing is hidden and a typo (`witdh`) is still visible in the result. This is
a behavioural change in the MCP layer only, and it is the one place where being lenient is strictly better
than being strict, because the alternative is an agent that gives up on labelling.

---

## 3. Recover cheaply from the accidents that still happen (report items 2, 3, 5, 6)

* **`logisim_wire_remove` takes a location** (`loc: [x, y]`): removes every segment passing through that
  point and returns their ids. Today's `from`/`to` form fails exactly when it is most needed, because
  Logisim has already split the wire at the port the agent ran through, so the endpoints the agent holds no
  longer describe a real segment. Add `describe`'s wire list to the error message as it is now, and accept
  `loc` as the escape hatch.
* **`dry_run` for `logisim_wire_add` and `logisim_component_add`**: validate, route, and report what *would*
  be created — including pass-through warnings, unconnected endpoints and resulting net names — without
  committing. For programmatic layout (draw the whole thing, then check) this is the difference between one
  call and an undo cycle. `dry_run` costs almost nothing to implement: the mutation is built either way and
  simply not committed.
* **`allow_port_passthrough`** (default `true`, keeping today's warning behaviour) lets a careful agent ask
  to be refused instead. Not the default: refusing would break sequences that are legitimate today.
* **Symbolic endpoints by port name**: `resolvePoint` already understands `"c18.0"`; teach it `"c18.Q"`,
  `"c22.Less"`, `"FA0.Cout"` as well. The names are already computed, so this is a lookup, and it turns the
  report's "port index needs rote memory" complaint into `c22.Less` — which reads like the documentation.
* **`logisim_component_add_many`**: a list of components in one call, one undo step, per-item results, and
  `dry_run`. This is the component-side twin of `wire_add`'s `paths`, which the report calls the best part
  of the tool set. It also removes the batch-with-one-label problem at its root.

---

## 4. Context economy: fewer, richer calls

| Change | Effect |
|---|---|
| `logisim_sim_run_vectors`: N input vectors, one call, one table of results, optional `expected` per vector so the reply is pass/fail per row | the report's 14 vectors become 1 call instead of 14; the natural shape for the assignment-style verification agents actually do |
| `logisim_component_catalog` compact mode (above) | looking something up stops being expensive |
| `logisim_circuit_nets` `problems_only` (above) | verification output shrinks from 53 nets to the two that matter |
| splitter `bit_ranges`: `{"0-4": 1, "5-31": 2}` expanded by the MCP layer into `bit0..bit31` | 32 attributes become 2; no Logisim change, the expansion is ours |
| undo granularity stated in the tool descriptions ("one batched edit is one undo step") | the report says this knowledge is what makes agents dare to batch |

---

## 5. Priorities

**P0 — small, and each one removes a costly step or a wrong report**

*(Implemented and verified in this working tree; the tools' own documentation is in `MCP.md`, section
"Making the answer hard to miss" and "Live mode: what is highlighted, and what is not".)*

| # | Change | Where | Cost |
|---|---|---|---|
| 1 | highlight only what is on screen, and hint when it is not (bug) | `McpSession.finishCall`, `GuiBridge` | small |
| 2 | port correction table for `Carry Out`-style ports, seeded by scan + simulation check | new `mcp/PortFixups.java` | small–medium |
| 3 | `ref`/`ref_by_name` per port in `component_add`'s result, with a hint | `EditTools`, `McpSession` | small |
| 4 | `wire_add`: diagnose endpoints that connect nothing, name the nearest port | `EditTools` | small |
| 5 | pass-through warning includes wire ids; `wire_remove` accepts `loc` | `EditTools` | small |
| 6 | lenient unknown attributes (`warn` default) with `ignored_attributes` in the result | `EditTools` | small |
| 7 | `nets problems_only` | `NetTools` | tiny |
| 8 | instructions: tunnels versus wires, facing conventions, "never guess a pin" (partly done) | `McpInstructions` | tiny |

**P1 — the tools that change how agents work**

*(Status: 9, 10, 12, 13 and 15 are implemented and verified; 11 and 14 are not, and the notes on
why are underneath. Item 13 was already delivered with the P0 work.)*

9. **`logisim_circuit_lint`** (read-only checks from §2.2). **Done** - severities, kinds and the fix
   for each finding, verified on a trial circuit built to be wrong.
10. **`dry_run`** on `wire_add` / `component_add`. **Done** - nothing is committed; stats proved it.
11. **`logisim_component_add_many`** (one undo step, per-item results). **Not done.** The pain it was
    meant to solve - a batch losing every component over one unwanted attribute - is gone with the
    lenient attribute rule, and the remaining benefit (fewer round trips) is smaller than it looks
    because each component needs its own ports read back. Worth doing when a session shows agents
    adding components in quantity.
12. **`logisim_sim_run_vectors`** (with `expected`). **Done** - and it is the item that most reduces
    context: the report's 14 vectors become one call with a table.
13. Port-name symbolic endpoints (`c18.Q`). **Done with the P0 work** (`ref` / `ref_by_name`).
14. Compact catalogue mode; optionally a `logisim_component_pins` alias. **Not done.** The port lists
    are already gated on a small result set, so the context cost is lower than the report's "verbose"
    experience suggests; revisit if agents start avoiding the catalogue again.
15. Splitter `bit_ranges` expansion. **Done** - `{"0-31": 0}` produced a single output, `{"0-3": 1, "4-7": 0}`
    two, which is the conversion from the shorthand's 0-based numbering to Logisim's 1-based attributes
    working.

**One thing the lint taught us:** its "wire through a port" finding is a backstop, not the defence.
Logisim splits a wire where a port meets it as the commit runs, so a stored circuit usually shows a
junction rather than a wire passing through a port - which is why the real protection is the warning
`logisim_wire_add` gives *before* committing, naming the wire and the `loc` repair. The lint check stays
because a file from elsewhere can arrive that way.

**P2 — worth doing, not urgent**

16. Facing *suggestions* in lint (currently only reports deviations), following the "follow the agent's
    circuit" preference and auto-selecting the circuit a call edited.
17. Grouped undo, screenshot, ROM/RAM contents, K-map output — unchanged from `MCP-PLAN.md` §5.

---

## 6. How each change should be verified

The harness from the last session already covers most of this, and it is what makes a large refactor safe:

* `tools/list` + `initialize` dumped before and after any wording change, then diffed line by line (this is
  how the `McpInstructions` move was shown to be text-identical);
* a headless run for the tool-level behaviour, and one live run with a window for anything that touches the
  GUI (the selection fix, "follow the circuit");
* the LLM-report scenarios as fixtures: the ALU-style task (tunnels, splitters, 14 vectors) and the
  accident sequence (wire through a port → lint/dry_run catches it → `wire_remove {loc}` repairs it);
* `logisim_self_test` (still open, `MCP-PLAN.md` P1.4) is the natural home for these as permanent checks.

### What the P0 work was checked against

* **Carry Out**: an `Adder` at width 8 reports port 4 as `output` with `direction_note`, and a wire from
  `c1.4` now forms a net that `logisim_circuit_nets` counts as `drivers: 1` instead of none.
* **Attribute leniency**: `label` on an `Adder` places the component and lists
  `ignored_attributes: ["label (this component has no label)"]`; `on_unknown_attribute: "fail"` still refuses
  with the attribute list.
* **Endpoint diagnostic**: a wire drawn to an empty spot returns the sentence above, naming the nearest port
  with its name, direction and width. A path cannot vouch for itself: the check uses only wires that existed
  before the call, and is skipped when two paths of the same call meet at the point.
* **Pass-through**: the warning names the wire and the `loc` repair; `logisim_wire_remove {loc: [300,300]}`
  removed both segments Logisim had split at that port.
* **References**: `c1.4` and `c7.Clock` both resolve in `logisim_wire_add`; an ambiguous name is refused with
  the candidates listed.
* **Netlist**: `problems_only` returned 1 of 2 nets, reporting `nets_omitted_as_clean: 1`.
* **Live highlight**: with the window showing one circuit, adding a component to another returned the hint
  and left the selection untouched; adding to the displayed circuit still highlights it; after
  `logisim_circuit_select` brought the circuit forward, the next call highlighted normally.

## 7. What I recommend *not* doing

* **Don't fix `Adder.java`'s port declaration** from here; keep the correction in the MCP layer where it can
  be verified by simulation and cannot change Logisim's behaviour for users.
* **Don't make lint or `dry_run` mandatory.** They are for agents that choose to be careful; a mandatory
  gate would break the working pattern the report describes (draw the whole thing in one batched edit).
* **Don't move the user's view automatically.** Hint instead, with an opt-in preference.
* **Don't replace the pass-through warning with a refusal by default** — offer `allow_port_passthrough:
  false` for agents that want it, and make the fix cheap (`loc` removal, wire ids in the warning) instead.

---

## 8. Audit of the Arithmetic and Plexers libraries

Before starting P1, both libraries were checked for the kind of misleading metadata the Carry Out error
represents. The method is cheap and repeatable: `logisim_component_catalog {lib: ..., verbose: true}` prints
every port with its direction, width, name and position (the names come from Logisim's own tooltips, so they
are the authority on what each port is), and any disagreement between a port's name and its declared
direction is then verified against the source and by simulation.

**Result: one misleading port in the two libraries, the one already corrected.**

* Directions: every Arithmetic and Plexers port agrees with its name, except `Adder`'s `Carry Out`, which is
declared `Port.INPUT` while the same file names it (line 95), *draws* it as "c out" (line 109) and writes it
in `propagate` (line 135). `PortFixups` corrects it, and a simulation through the MCP now confirms it: with an
output pin wired to that port, A=1/B=1 gives 0, A=255/B=1 gives 1, A=127/B=127 gives 0, A=128/B=128 gives 1 -
four of four as a carry should. The wiring of that test produced no warnings, which is also the first check
that the new endpoint diagnostic does not cry wolf on correct wiring.
* Widths: all consistent, including the ones that look odd until you think about them - `Shifter`'s distance
is 3 bits (log2 of an 8-bit input), `BitAdder`'s output is 4 (a count of up to 8 ones), `BitFinder`'s index
and `PriorityEncoder`'s output are 3, `DisplayDecoder`'s input is 4 (BCD), and `Multiplier`'s carry/`Divider`'s
upper are data-width because they carry data, not a flag.
* Names: every port in both libraries has one, so nothing is unlabelled. Two cosmetic findings for you to
decide on, neither of which is the MCP layer's business:
  1. `Comparator`'s Greater port is described as *"1 if A is greator than B"* - a typo in Logisim's own
     English bundle (`resources/logisim/en/std.properties`). One word, and it is the sort of thing an agent
     reads verbatim.
  2. `Adder` and `Multiplier` give both of their data inputs the *same* tooltip ("one of the numbers to
     add"), so no name distinguishes them and `logisim_component_add` deliberately omits `ref_by_name` there;
     the index form (`c1.0`) still works, and a label on the pin is the better name anyway.
* Not yet audited with this method: Memory, I/O, Gates, Wiring and TTL. The method is the same (one catalogue
call per library plus confirmation of anything suspicious), and given how clean these two were it is worth
running once over the rest when the library work comes up.
