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
| Checks | `logisim_circuit_lint` |
| Circuits | `logisim_circuit_list`, `logisim_circuit_create`, `logisim_circuit_delete`, `logisim_circuit_set_main`, `logisim_circuit_select`, `logisim_circuit_describe`, `logisim_circuit_stats`, `logisim_circuit_nets` |
| Editing | `logisim_component_add`, `logisim_component_add_many`, `logisim_component_duplicate`, `logisim_component_remove`, `logisim_component_move`, `logisim_component_set_attributes`, `logisim_wire_add`, `logisim_wire_remove` |
| Simulation | `logisim_sim_reset`, `logisim_sim_set_input`, `logisim_sim_set_inputs`, `logisim_sim_read`, `logisim_sim_read_all`, `logisim_sim_propagate`, `logisim_sim_tick`, `logisim_sim_trace`, `logisim_sim_status`, `logisim_sim_run_vectors` |
| Memory | `logisim_component_memory` |
| Analysis | `logisim_analyze_truth_table`, `logisim_analyze_expression`, `logisim_circuit_build` |
| Picture | `logisim_screenshot` |
| Repair | `logisim_circuit_repair_wires` |
| Self test | `logisim_self_test` |

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
  of whatever `logisim_component_add` places, because both are read the same way, so a
  component can be checked pin by pin after placing it. This holds for every component
  in every library, including ones whose pins the library computes itself rather than
  declaring on the factory: a `Splitter` reports `Combined end of splitter` (width 4)
  and `Bit 0`…`Bit 3 from combined end`, which is the bus-to-pin map nothing else
  states.

  Two fields exist for the cases where a port cannot be described in the usual way,
  so that neither is silently missing:

  * **`width_note`** instead of `width`, for a port with no width of its own - a Probe
    or a Pull Resistor takes the width of whatever it is attached to (printing
    `0` would read as "carries no bits");
  * **`ports_note`** when a component has no pins at all (a text label) or when its
    pins cannot be read without placing it.

  A port with no `name` is Logisim's own answer, not a missing one: most gates, the
  D/J/K/S/R inputs of the flip-flops and the stages of a shift register have no tooltip
  in Logisim at all. Position and direction are still reported, `ref_by_name` is simply
  absent, and the index form (`c3.1`) always works.
* **`logisim_help`** reads Logisim's manual, which the program already ships: the user
  guide and the library reference, one page per component, in plain text. With no
  arguments it lists the manual, with `query` it searches every page and returns the
  paragraph each hit matched, and with `topic` it returns one page - a page path
  (`libs/mem/flipflops.html`), a word (`flipflops`) or a component name (`D Flip-Flop`,
  which falls back to a search because the manual's page names do not always match the
  component's). It needs no project open.

### Two faults the tools refuse to create

A few mistakes cannot be repaired after the fact, so they are refused rather than warned about. Both are
cases where the tool can see the exact geometry that will break the file, and where Logisim offers no way
back:

* **A circuit cannot be placed inside itself.** Logisim's own editor refuses this too (`AddTool` asks
  `Dependencies.canAdd`); the API path did not, and a circuit that contains itself cannot be simulated - the
  propagator recurses through the subcircuit until the stack overflows, the call never returns and the
  session has to be restarted. `logisim_component_add`, `logisim_component_add_many` and
  `logisim_component_duplicate` all ask Logisim's own question first.
* **A wire point that is off the 10 pixel grid is refused** (`on_off_grid: "warn"` to keep it anyway, or
  `"allow"` to say nothing), and a point *within two units of an existing wire but not on it* is refused
  whatever `on_off_grid` says. Logisim accepts such a point as lying on the wire, splits the wire there and
  keeps the off-line location, which leaves a wire whose ends differ in both x and y - nothing can iterate
  it, so every later edit, every undo and every attempt to load the saved file runs until the heap is
  exhausted. An automatic repair cannot converge on it either: the next transaction splits the wire again.
  A component *port* landing in that band is refused for the same reason, which is the one case where an
  off-grid component position matters. `logisim_wire_remove {loc}` is deliberately left permissive, because
  removing the bad geometry is the cure.

### Staying on the canvas

Coordinates are `x >= 0, y >= 0`, in multiples of 10. Logisim's canvas starts at the
origin and grows right and down, so anything at a negative coordinate is valid but
off-screen - a circuit that looks empty to the user. The editing tools therefore refuse a negative coordinate, from either end: `logisim_component_add` and
`logisim_component_move` check the component's position *and* its body (a Pin's
anchor is its centre, so a Pin at (0,0) has corners at -25) and tell the agent the
coordinates to use instead; `logisim_wire_add` checks every point the agent wrote as
numbers, while leaving symbolic endpoints (`"A"`, `"FA0.Cout"`) alone, since those
name ports that already exist.

### Seeing the schematic: `logisim_screenshot`

A schematic is a picture, and an agent that can only read numbers is judging a design by
text alone. `logisim_screenshot` draws a circuit and returns it as a PNG in the reply's
image content block; with `path` it also writes the file, so the user can open it.

It renders **offscreen** — the drawing code Logisim's own "Export As Image" dialog uses
needs only a `Graphics` and a `Component`, not a window — so it works in a headless session
as well as a live one. `region`, `scale`, `print_view` and `transparent` choose what is
drawn, and a request larger than `max_pixels` (four megapixels by default) is refused
rather than exhausting the heap.

One thing it cannot do is tell you what is *connected*: a wire that crosses another looks
exactly like a wire that joins it, with the difference visible only at the junction dot.
Use `logisim_circuit_nets` for the electrical answer and the picture for the layout.

### Building in bulk: `logisim_component_add_many` and `logisim_component_duplicate`

One call per component is how a 70-component circuit becomes 70 replies, each with an
attribute table, a port list and an "is not connected to anything yet" warning. Both of
these tools exist to make that cheaper, and both commit as a **single undo step**:

* `logisim_component_add_many` takes a list of the objects `logisim_component_add` takes
  and places them in order. `include_ports: false` returns ids and positions only, and
  `dry_run` reports what would be placed without placing it. It is all or nothing: one
  impossible component refuses the call, with the index that failed named.
* `logisim_component_duplicate` copies a component (or a wire) `count` times, each offset
  by `dx`/`dy` from the previous one — a bank of tunnels or a row of gates in one call.
  `attributes` changes the copies only, which is where a row of tunnels gets its labels;
  the reply warns when the copies keep the original's label, because two tunnels with one
  label are one signal.

### Memory contents: `logisim_component_memory`

A CPU design cannot run until its memory holds something, and before this tool nothing could
put a program there: the memory was the one part of a circuit the layer could not reach.
`logisim_component_memory` reads a range of words, writes words, and zeroes the memory, for a
ROM and for a RAM, with the addresses and values written the way the rest of the server writes
them (`0x` hex, decimal, `0b` binary) and a value that does not fit the memory's data width
refused rather than truncated.

The two memories keep their data in different places, and the result says which happened
rather than hiding the difference:

* a **ROM** holds its words in a component attribute, and that attribute is the text Logisim
  writes into the `.circ` file - so a write is an ordinary `logisim_undo` step, and the file
  carries the contents (`persistence: "saved with the file"`). Verified by writing three
  words, saving, closing, reopening and reading them back;
* a **RAM** holds its words in the simulation state the simulator attaches to the instance, so
  a write takes effect at once, is **not** an undo step, and is **not** saved: opening the
  saved file starts the memory empty again. That is Logisim's own behaviour, not a choice made
  here, and the reply says so plainly, because an agent that loads a program into a RAM and
  saves the file has lost the program.

Both are reached through `com.cburch.hex.HexModel`, the interface Logisim's own hex window
edits, and a ROM's words are put back through the attribute's own parser - so what the circuit
ends up holding is what Logisim itself would have written, with nothing added to Logisim.

### Sequences: `logisim_sim_trace`

`logisim_sim_tick` says what a circuit reads *now*; a flip-flop, a counter, a shift register or
a program counter is only verified by what it reads **over time**. `logisim_sim_trace` records
named signals once per cycle and returns a table: one row per cycle, one column per signal,
with `only_changes` keeping just the transitions (a six cycle run of a toggle becomes four
rows instead of seven). Signals are named the way `logisim_wire_add` names endpoints - a pin
label, a `"c3.1"` or `"c3.Q"` port reference, or an `"x,y"` location - and default to every pin.
Run against a D flip-flop whose complement feeds its data input, the rows show `x` before the
first edge and then the alternation, which is the check the self test pins it with.

### Is this server working? `logisim_self_test`

Every failure this project has had was found by hand, with a throwaway script in the temp
folder. `logisim_self_test` is that idea kept where it can run again: one call, fourteen
checks, about a second, and a one line answer per check with `ok` and the detail needed to see
why not. It builds a small circuit of its own in a **second session**, so nothing of the open
project is touched, changed, saved or closed - which is why it is safe to call at any moment,
including in live mode with unsaved work on screen.

The checks are `tools`, `catalogue`, `build`, `guard`, `dry_run`, `simulate`, `nets`,
`analysis`, `lint`, `memory`, `trace`, `save/load`, `recover` and `docs`. Between them they pin
the things that were once wrong and could go wrong again: that a Splitter reports five named
ports, that the Adder's Carry Out still comes back as an `output` with its `direction_note`,
that a negative coordinate, an off grid position and a circuit placed inside itself are all
refused, that a dry run changes nothing, that an 8-bit adder adds with the right carry, that
the carry net still has exactly one driver, that the truth table and the minimised expression
agree with the circuit, that a deliberately wrong circuit is linted, that a ROM write survives
an undo, that a flip-flop's trace changes over cycles, that a project survives a save and
reopen, that a file containing a wire Logisim cannot iterate is refused by `file_open` and
rescued by `file_recover` - the one branch of that tool which had never been exercised - and
that the bundled manual can still be read (which a jar that lost its `doc` folder cannot do).

A failing check says what it expected and what it saw, and keeps its scratch project in the
temp directory so the state can be looked at; `checks: ["simulate"]` runs a subset, and each
check builds whatever it needs first, so a subset works on its own. When this round was
written the test immediately found a defect in its own trace fixture (the D input of a
flip-flop has no name in Logisim, so the fixture had wired the complement to the *output*),
which is exactly what it is for.

### Making the answer hard to miss

Agents were guessing where pins were and then blaming themselves, so the tools now answer at the
moment of the mistake rather than only when asked:

* **Every port comes back with a reference.** `logisim_component_add`, `logisim_component_catalog` and
  `logisim_circuit_describe` report `ref` (`c22.4`) and, when a short name identifies exactly one port,
  `ref_by_name` (`c7.Clock`). `logisim_wire_add` accepts either, so a wire endpoint never has to be
  arithmetic: `from: "c7.clock"` works, and a name that matches two ports is refused with both listed.
* **An endpoint that connects nothing says so, and names the nearest port**: *"an endpoint at (320,340) is
  not a port and does not touch any wire that was already there…; the nearest port is c1.2 at (300,300), 20
  units away (Output: the sum of the inputs (plus carry in), output, 8 bits)"*.
* **A wire that runs through a port names the wire** (`wire c4`) and the one-line repair
  (`logisim_wire_remove {loc: [300, 300]}`), because Logisim has already split that wire at the port and the
  original `from`/`to` no longer describes a segment. `logisim_wire_remove` takes `loc` for exactly that
  reason, and removes every segment through the point.
* **An attribute the component does not have no longer loses the call.** Arithmetic and Plexers components
  have no `label`, so a labelled batch used to fail whole. Now the component is placed and the attribute is
  listed under `ignored_attributes`; `on_unknown_attribute: "fail"` restores the refusal.
* **`logisim_circuit_nets {problems_only: true}`** returns only the nets that will read `x` or `e`, with a
  count of the clean ones it left out - the whole netlist is unreadable context for a 45-component circuit.
  `compact: true` shrinks even that: one line per net, with the ports as references (`c3.1`) instead of a
  description of each one, so a six-net circuit reports in about half the characters. Every port in the full
  form now carries its `ref` too.
* **A wire says which net it just joined.** An endpoint that lands on an existing wire - or within the two
  units Logisim's own wire test accepts, which is the case that used to be described wrongly as "connects
  nothing" - comes back under `joins` with the wire id and the net it becomes part of: *"an endpoint lands
  on wire c9, so from now on they are one net"*, with the net's labels and driver count beside it. A segment
  drawn along an existing wire is reported the same way. `logisim_wire_add {dry_run: true}` reports the same
  joins before anything is drawn, so *"what am I about to connect together?"* has an answer.
* **An input value is echoed as it was understood.** `logisim_sim_set_input` and `logisim_sim_set_inputs`
  return `parsed`, so `{"a": "0x00000003"}` comes back as `{"given": "0x00000003", "understood_as":
  "0x00000003"}`. A value that cannot be read is refused rather than rounded to 0, and a `0x`-prefixed
  literal is never mistaken for a bit pattern: on a three-bit pin, `"0x0"` is the number 0, while `"x0x"`
  is still a bit pattern with an unknown bit in it.
* **A multi-bit read carries its numbers.** Every fully-known value comes back with `hex`, and from four
  bits up with `dec` and `signed` (a 32-bit `0xFFFFFFFE` reports `dec: 4294967294, signed: -2`); `radix`
  adds whichever reading you want as `text`. Before this, a 32-bit result was thirty-two 0s and 1s and no
  number at all to compare against.
* **The catalogue can answer cheaply.** `logisim_component_catalog {terse: true}` reports every match with
  attribute *names* and no port detail, which is the shape of the question "does this component have a
  label?" - an entire library in about a third of the characters `verbose` takes, and without the
  small-result-set gate.
* **`logisim_component_add` ends with a hint** naming the `ref` it just handed out, so an agent that reads
  only the tail of the reply still knows to wire by reference.
* **`logisim_circuit_select` accepts `circuit`**, the argument name every other tool uses (`name` still
  works), and reports the circuit it left under `was` - which is how an edit that landed somewhere
  unexpected becomes visible.

### Checking the work before handing it over

* **`logisim_circuit_lint`** is one read-only pass that reports, per finding, its kind, its
  severity and the tool that fixes it. Severity `error` means the circuit will not behave as
  drawn - a net with more than one driver, a net with none, a tunnel left on Logisim's default
  label (they all join each other), a wire segment stored through a port; `warning` means it
  works but reads against the conventions - a tunnel facing north or south, a ground or power
  whose facing differs from the rest of the circuit, a component or wire end off the 10 pixel
  grid, a component at a negative coordinate the user cannot see, two components drawn on top of
  each other, a port touching nothing. `severity: "error"` narrows it. A trial circuit with a
  north-facing tunnel, an unlabelled tunnel, a stray ground, an off-grid tunnel, two overlapping
  pins and an undriven wire produced 14 findings, 3 of them errors, in one call.
  *(The short-circuit check is a backstop: Logisim splits a wire at a port as it commits, so the
  stored model usually shows a junction rather than a wire running through a port. The prevention
  is in `logisim_wire_add`, which warns before the commit and names the wire.)*
* **`dry_run`** on `logisim_component_add` and `logisim_wire_add` returns everything a real call
  would - ports, refs, routed segments, wire ids, warnings - and commits nothing, so a layout can
  be checked before it is drawn. Verified by comparing `logisim_circuit_stats` before and after: unchanged.
* **`logisim_sim_run_vectors`** applies many vectors in one call: `{vectors: [{values: {...},
  expect: {...}, name: "..."}]}`, with hex accepted and `include_signals: false` for a small answer.
  Four adder vectors with one deliberately wrong expectation came back `passed: 3, failed: 1` with
  *"cout: expected 0, read 1"* on the failing row.
* **A splitter's `bit_ranges`** replaces thirty-two attributes with a couple: `{"0-31": 0}` sends
  every bit to output 0 (one output, `fanout` set to 1), `{"0-3": 1, "4-7": 0}` sends the two
  halves to two outputs. Outputs are numbered from 0 here; Logisim stores them from 1, and the
  conversion happens in one place.

### Directions Logisim itself gets wrong

A few components declare a port as an input and then drive it. `Adder`'s Carry Out is one
(`std/arith/Adder.java:90`), and it mattered: a carry net looked undriven, so an agent that had wired it
correctly was told its circuit had no driver. `PortFixups` corrects the direction and says so
(`direction_note`), and the netlist counts the port as a driver. Corrections are added one at a time and only
after checking by simulation that the port really is driven - never by editing Logisim's own libraries.

Two more things the libraries themselves get wrong came out of the audit of all eight libraries
(`MCP-AGENT-FEEDBACK.md` §8). Neither is corrected, and both are worth knowing:

* **A `7402`'s pins are numbered like a `7400`'s.** The library puts its outputs on pins 3, 6, 8 and 11,
  where the real chip has them on 1, 4, 10 and 13 (it is the mirror image of the 7400). The component
  itself works - the ports it declares are the ports it drives - but an agent following a datasheet will
  wire an input into a pin this model drives. Every other TTL chip in the library matches its real pinout.
* **A `RAM`'s data bus has no name.** `std/memory/Ram.java:212-215` sets the tooltip on one port and then
  replaces that port, so the text never reaches a port; it is the only unnamed port in the Memory library.
  The direction, the width and the position are all reported, so the port is usable, just unnamed.

### Live mode: what is highlighted, and what is not

A call that edits a circuit the window is **not** showing no longer replaces the selection - Logisim paints
the selected items of one circuit over whatever is displayed, which told the user something that was not
there. Nothing is highlighted in that case, and the reply explains it and says how to bring the circuit
forward:

> the edits are in circuit 'main', but the window is showing 'sekundär', so nothing was highlighted… Call
> logisim_circuit_select with circuit 'main' to bring it to the front.

The selection the user already had is left alone, and a call that edits the displayed circuit highlights what
it touched exactly as before.

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

### How a diagonal pair of points is turned into wires

Given two points that are not in line, the router tries, in order: the two single corner
routes; Z shaped routes through lanes just outside the ports and the existing wires; and
finally a **search** over the grid of interesting coordinates - the two ends, and one or two
grid steps either side of every port and every existing wire end - priced so that a straight run
costs its length and a corner costs a little more, which is the route a person would draw. The
search is what handles a circuit packed tightly enough that no corner and no fixed lane is free;
it is bounded (24 lanes per axis, each step reaching only the next lane), and its result is
collapsed into maximal straight runs so that a route is three wires rather than twenty.

Two shapes are refused rather than drawn, because both would silently join a component the
caller never mentioned: a segment that **passes through** a port (Logisim splits the wire there
and keeps the junction) and a **corner that lands exactly on** a port. The second one was found
by the new self test's own fixture: with corners at both of the natural corner positions, the
old router took one of them and shorted a tunnel into the net. If no route passes, the tool
refuses with the two points, the reason, and an example `points` list to steer with - and
`dry_run` reports the same route without drawing it.

## When a circuit is large, or the heap is filling up

Every edit repairs the wiring of the whole circuit, so the cost of a call is a function of the
circuit's size - which is not something an agent can see. Three things therefore say so, in the
`hint` every tool result can carry:

* `logisim_status` reports `heap_used_percent` and `heap_pressure` under `memory`; once the heap
  is four fifths full, **every** tool result says so, with the numbers and the suggestion to
  save and restart with a larger `-Xmx` (a non-axis-aligned wire is the one thing that exhausts
  the heap by itself, and the message says where to look);
* a call that took five seconds or more explains that this is what a large circuit costs, and
  what to do instead (batch the work with `logisim_wire_add`'s `paths` or
  `logisim_component_add_many`, one undo step per intention);
* a **mutating** call on a circuit with more than 400 components or 800 wires warns before the
  next one, naming the counts. Verified on a 452 component circuit, where the warning arrived
  appended to the tool's own hint.

The advice is a courtesy and never changes a result: it is added only when the call succeeded,
and the check that produces it can never fail a call.

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
bundle map, so no bridge class was needed), **documentation** (`logisim_help`, with the
manual packaged into the jar and the library's own port names in `logisim_component_catalog`),
and **visualisation** (item 3, as `logisim_screenshot` — the same `ComponentDrawContext` +
`ImageIO` route, drawn off screen with no window, which is also what the image export dialog
uses).

Still open:

1. **Single step propagation** — `Propagator.step()` / `isPending()` are package
   private; a bridge would allow delay accurate stepping, which is how signal
   races are debugged.
2. **Memory and register tools** — `MemContents`, `Ram.loadImage` and
   `InstanceLogger` already provide everything needed to load a program image and
   dump registers, which is the heart of CPU debugging.
3. **A self test** — one call that builds a known circuit, checks a truth table, a
   batched edit, a dry run, the lint, a save/load round trip and the recover path,
   and returns pass or fail; the design is in [`MCP-PLAN.md`](../MCP-PLAN.md) §5.
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
