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

## 8. Audit of every library's port metadata

Every library in the project was checked for the kind of misleading metadata the Carry Out error
represents: a port whose direction, width or name disagrees with what the component actually does. All eight
libraries (Base, Gates, TTL, Wiring, Plexers, Arithmetic, Memory, I/O) and all 79 of their components,
including the five that had not been looked at before (Memory, I/O, Gates, Wiring, TTL).

**Method** (cheap and repeatable, one catalogue call per library): `logisim_component_catalog {verbose:
true}` prints every port of every component with the library's own name, direction, width and position, and
the names come from Logisim's own tooltips, so they are the authority on what each pin is. Anything that
disagreed with its name - or looked wrong on its own - was then read in the source and, where it mattered,
confirmed with the simulator.

**Result: three defects in the libraries, four in our own reporting, and nothing else.**

### 8.1 Directions: one wrong port in 79 components

`Adder`'s Carry Out is the only one, and `PortFixups` corrects it (§1.1): declared `Port.INPUT` while the
same file names it (line 95), draws it as `"c out"` (line 109) and writes it in `propagate` (line 135). With
an output pin on that port the simulator gives 0, 1, 0, 1 for A=1/B=1, 255/1, 127/127, 128/128 - four of
four as a carry should - and a net whose only source is that port now reports `drivers: 1`.

Everywhere else the declared direction agrees with the library's own name: a flip-flop's clock is an input,
a counter's carry an output, a RAM's data bus `input_output`; a clock, a constant, power, ground, a button,
a dip switch, a slider, a joystick and a keyboard's data are outputs; an LED, a 7-segment display, a buzzer
and a hex-digit display read. Three cases look wrong until you read the source, and all three are right:

* a **Pin**'s port type is inverted by Logisim - an input pin's port is `Port.OUTPUT` because it drives the
  net, an output pin's is `Port.INPUT` because it reads it. Our two views of a component *disagreed* about
  this, which the audit found and fixed (§8.4); both now report the pin's role, so an input pin shows
  `direction: input` beside the name "Add an input pin".
* a **Transistor**, a **Transmission Gate** and the I/O library's **Switch** read one port and drive
  another, by design: they are switches, not wires, and the value passes one way only - wire something into
  a Switch's `Output` terminal and its `Input` terminal never sees it.
* a **Tunnel** is `input_output`: it is a name rather than a device, and the netlist treats it as such.

### 8.2 Widths: all consistent, but two ports have none

Every width agrees with what the component does, including the ones that look odd until you think about
them - `Shifter`'s distance is 3 bits (log2 of an 8-bit input), `BitAdder`'s output is 4 (a count of up to 8
ones), `BitFinder`'s index and `PriorityEncoder`'s output are 3, `DisplayDecoder`'s input is 4 (BCD),
`Multiplier`'s carry and `Divider`'s upper are data-width because they carry data rather than a flag, and
every TTL pin is one bit.

Two components have no width of their own: a **Probe** and a **Pull Resistor** declare
`BitWidth.UNKNOWN`, which is a width of zero, and the port then takes the width of whatever it is attached
to. The catalogue used to print `"width": 0`, which reads as "carries no bits"; it now omits the width and
explains it in a `width_note` (§8.4).

### 8.3 Names: 84 ports have none, and that is Logisim's own answer

Most of the Gates library sets no port tooltips at all - `AbstractGate.java:120-126` builds
"output, then the inputs top to bottom" with no names - and the same is true of a flip-flop's D/J/K/S/R
inputs, a shift register's stages, a RAM's data bus (§8.5), a probe, a tunnel, a button, the dip switch
bits, the oscilloscope's channels and the larger TTL pin lists. Nothing is invented to fill those gaps: the
position and the direction are reported, `ref_by_name` is simply absent, and the index form (`c3.1`) always
works.

Two cosmetic findings from the earlier pass stand (both Logisim's text, not ours):

1. `Comparator`'s Greater port is described as *"1 if A is greator than B"* - a typo in
   `resources/logisim/en/std.properties`.
2. `Adder` and `Multiplier` give both of their data inputs the *same* tooltip ("one of the numbers to
   add"), so no name distinguishes them and `ref_by_name` is deliberately omitted there.

### 8.4 Defects in our own reporting, found by the audit and fixed

1. **A splitter reported no pins at all.** `Splitter` is a `ManagedComponent`, not an `InstanceFactory`, and
   the catalogue only knew how to ask the instance library - so the one component whose entire purpose is
   the mapping from a bus to individual bits answered nothing, and said nothing about answering nothing.
   The catalogue now describes the component the library would build, and asks such a component for its own
   tooltip per pin, exactly as the canvas does: a splitter reports `Combined end of splitter` (width 4) and
   `Bit 0`/`Bit 1`/`Bit 2`/`Bit 3 from combined end`, which is the map an agent wiring a bus needs and no
   attribute states. A placed splitter reports the same names.
2. **The catalogue and a placed component disagreed about a Pin** (§8.1). There is now one rule
   (`McpSession.portDirection`) and both paths call it.
3. **`"width": 0`** became a `width_note` (§8.2).
4. **Silence instead of an answer**: when a component's pins cannot be read at all, the catalogue says so
   (`ports_note`), and a component that has no pins at all (a text label) says `this component has no pins`
   rather than showing an empty list.

Verified with the audit itself: all 79 components now report either a port list or a reason for not having
one, and both the "width 0" and the "no port list" groups are empty.

### 8.5 What is wrong inside Logisim, and what to do about it

Three findings that are Logisim's rather than ours. None of them is fixed; the first two are yours to
decide.

1. **A RAM's data bus has no name, because of a misplaced line.** `std/memory/Ram.java:212-215`:

   ```java
   } else {
       ps[DATA].setToolTip(Strings.getter("ramBusTip"));   // set on the old port...
       ps[DATA] = new Port(0, 0, Port.INOUT, DATA_ATTR);   // ...which is then replaced
   }
   ```

   The tooltip is applied to the port `configureStandardPorts` left in that slot, and the new port replaces
   it, so `ramBusTip` never reaches a port. The combined-bus RAM is the only unnamed port in the Memory
   library. Swapping the two statements is a one-line fix with no effect on simulation - unlike the Adder,
   nothing reads a tooltip - but it is Logisim's file, so it was left alone.
2. **A 7402's pins are numbered like a 7400's.** `std/ttl/Ttl7402.java:9` declares its outputs on pins 3, 6,
   8 and 11; the real 7402 has them on pins 1, 4, 10 and 13, because it is the mirror image of the 7400 -
   which is why the 7400 in the same library *does* use 3, 6, 8, 11. This one is not cosmetic: the simulator
   drives exactly the pins the library declares (a NOR truth table through the tool came back 4 for 4, and
   the netlist counts those ports as drivers), so the component works - but an agent that follows a
   datasheet, or a schematic drawn for the real chip, wires an input into a pin this model drives. The
   fork's own later version disagrees with this one: `master` (`ad7c02a`, the Logisim-Ita development line)
   has

   ```java
   super("7402", (byte) 14, new byte[] { 1, 4, 10, 13 }, true);
   ```

   with the two `ttlpropagate` loops reading the other way round, so the fix already exists in the project's
   own history - it arrived after `v2.15.0.1`, which is where this branch is based. Taking that file from
   `master` is the honest fix; the MCP layer cannot help here, because which pins carry the value is the
   thing that is wrong.
3. **A Pin's port is named after the toolbar tool.** `Pin.java:361-363` sets the port's tooltip to
   `pinInputToolTip`, the same string the tool palette shows, so hovering a pin on the canvas says *"Add an
   input pin"* - and so does the catalogue. It is truthful (an input pin is what it is) but it reads like a
   contradiction beside a direction, which for that port is `input`. Worth a better string in Logisim's
   bundle one day; not worth touching now.

### 8.6 What the audit did not find

No other direction errors, no width inconsistencies, and no name that Logisim has and we fail to report -
across all eight libraries. With the five audited here, every library is covered by the same method: one
catalogue call per library, the source for anything suspicious, the simulator for anything that matters.

---

## 9. The second agent report — what reproduced and what did not

A second agent session (a WorkBuddy agent, given the same kind of task) reported eleven issues with the
server. Every one was put through the running server before any code was touched, because the first round's
lesson was that a report is a list of hypotheses. The result: **three of its central claims did not
reproduce, four items were already shipped, and four real defects came out of it** — two of which it had not
noticed at all.

### 9.1 Claims that did not reproduce

| Claim | What actually happened |
|---|---|
| Hex input is parsed as a bit pattern: *"`0x03` on an 8-bit pin becomes three bits"* | Eleven forms tested on pins of 1, 3, 4, 8 and 32 bits — `0x03`, `0x3`, `0x00000003`, `3`, `11`, `0b11`, `"10"`, `0x0`, `0x00`, `0x0F`, `0xffffffff` — and every one parsed as the number. Malformed input (`zz`, `0x`, `3g`) is refused with a message rather than becoming 0. Our parser tested `0x` before the bit-pattern branch only when the string was *not* also a legal bit pattern, which is where a real defect was hiding (§9.2, N1) — the claim's own example was not one. |
| Two wires that cross are joined by the tools | `net_count` stayed at **2** with a crossing in both directions; the same circuit with an endpoint *landing* on the other wire gave **1**. A crossing is not a connection in Logisim, and the report's evidence was a case where an endpoint had landed. The distinction matters, and the old diagnostic made it worse by calling that landing *"connects nothing"*. |
| `logisim_screenshot` needs a new Java interface inside Logisim, so it cannot be a tool | No. `ComponentDrawContext`'s constructor takes a `java.awt.Component`, not a window, and `ExportImage`/`Print` in the export code already draw a whole circuit into a `BufferedImage` with no display. The tool was written in one file (`mcp/tools/ImageTools.java`) with nothing added to any original Logisim package, and its output was checked visually: the render shows pin values, labels, wires and the junction dot. |
| Items 4, 5, 8 and 10 (batched simulation, lenient attributes, splitter `bit_ranges`, `dry_run`) | All four were already in the build it was testing — `sim_run_vectors` since the P1 round, the rest since the day before its session. A server built before those commits would explain it; nothing to change. |

### 9.2 Defects it did not report, found while checking

* **N1 — a `0x` literal as wide as its pin was read as a bit pattern.** A `0x`-prefixed string is a number
  only if the digits after it are hex digits, but `isBitString` accepted `x` as an unknown bit, so `"0x0"`
  on a three-bit pin was read as the bit pattern `0x0` (undefined, not 0) and `"0x00"` on a four-bit pin
  became `00x0`. `Values.parse` now decides with `s.startsWith("0x") || s.startsWith("0b")` and only tries
  the bit-string branch when the string is not a number literal, so `"0x0"` is 0 everywhere while `"x0x"`
  is still a bit pattern with an unknown bit.
* **N2 — an off-grid point within Logisim's two-unit tolerance was reported as connecting nothing, then
  joined a net and poisoned the session.** `Wire.contains` accepts a location up to 2 units off a wire and
  `WireRepair.doSplits` keeps that off-line location, so a point at `(950, 502)` beside a wire at `y=500`
  split the wire into two segments, one of them diagonal. The transaction then went bad repeatedly and
  `logisim_undo` failed with `OutOfMemoryError: GC overhead limit exceeded` in `WireRepair.doOverlaps` — the
  automatic repair cannot converge on such a wire. Two changes: the endpoint diagnostic now uses
  `Wire.contains` (Logisim's own ±2 test) instead of exact segment arithmetic, so it says *joins* rather than
  *connects nothing*; and `requireNotInexactlyOnAWire` refuses that band outright, whatever `on_off_grid`
  says, for wire points and for component ports alike.
* **N3 — a circuit could be placed inside itself, and then take the session with it.** `logisim_component_add`
  with the current circuit's own name is accepted by the edit layer, but the resulting circuit cannot be
  simulated: the propagator recurses through the subcircuit, the call sat for 60 seconds and then died with
  `StackOverflowError` in `CircuitState.processDirtyComponents`. Logisim's own editor refuses this (`AddTool`
  asks `Dependencies.canAdd`); the API path did not. It does now.
* **N4 — `logisim_circuit_select` took `name`** while every other tool takes `circuit`, so an agent that
  learned the convention got "missing argument". It accepts both, and reports the circuit it left behind as
  `was` — which is how an edit that landed in the wrong circuit becomes visible.

### 9.3 What was implemented from the report

* `logisim_wire_add` reports **`joins`**: the wire id and the net an endpoint has just become part of, with
  that net's labels and driver count. `dry_run` reports it before the commit, and so answers "what am I about
  to connect together?".
* **Values are echoed as understood.** `sim_set_input`/`set_inputs` return `parsed`
  (`{"given": "0x00000003", "understood_as": "0x00000003"}`), and every fully-known read carries `hex`, with
  `dec` and `signed` from four bits up (`0xFFFFFFFE` → `signed: -2`). Before this, a 32-bit result was
  thirty-two 0s and 1s and no number to compare against.
* **`logisim_component_add_many` and `logisim_component_duplicate`**, each one mutation and one undo step.
  A five-component placement in one call returns 543 characters of reply against roughly 3 000 for five
  separate calls; the batch is all-or-nothing and names the index that failed.
* **`logisim_screenshot`**, returning the PNG in the reply's image content block and, with `path`, writing it
  to disk. It refuses a request over `max_pixels` (four megapixels by default) instead of risking the heap.
  What it cannot show is *connection*: a crossing and a junction look the same except for the dot, so the
  netlist remains the electrical answer and the picture is the layout answer.
* **`compact` on the netlist** and **`terse` on the catalogue** — the two smallest of the context savings,
  because the report's other context complaints were already addressed by `problems_only` and the
  small-result-set gate.

### 9.4 The context-cost measurements

| Response | Before | After |
|---|---|---|
| `logisim_component_catalog` for a whole library, names only | 12 035 chars | 3 872 (`terse: true`) |
| `logisim_circuit_nets` on an eight-net circuit | 2 120 chars | 1 150 (`compact: true`) |
| Five components placed | ~3 000 chars, five calls, five undo steps | 543 chars, one call, one undo step |
| A 32-bit value read | 32 bits of text, no number | the bits plus `hex`, `dec`, `signed` |

The pattern the report was really describing is not that any single reply is too long, but that a *batch* of
unrelated replies is: the fix is fewer, richer calls and an argument that says which of the two questions is
being asked, not a shorter reply in general.

### 9.5 What is left of the report

Nothing on the correctness side: the three claims that did not reproduce were checked again after the
changes, and the four defects are fixed and covered by the re-run suites. What remains is the report's one
sound structural suggestion — a single call that proves the server is working — which is
`logisim_self_test`, designed in full in [`MCP-PLAN.md`](MCP-PLAN.md) §5 (P1.4) and now the next item to
build. It is the right home for the fixtures this round was tested with: the throwaway scripts under
`%TEMP%\mcp-test` proved each behaviour once, and the value of a self test is that it proves them every time.
