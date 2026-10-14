/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Propagator;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.mcp.Json;
import com.cburch.logisim.mcp.McpException;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;
import com.cburch.logisim.mcp.Values;
import com.cburch.logisim.mcp.McpInstructions;
import com.cburch.logisim.std.wiring.Pin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Tools to drive a circuit and read its signals.
 *
 * <p>
 * Propagation is entirely under the agent's control: Logisim's background
 * simulator thread is disabled for MCP sessions, so nothing changes unless a
 * tool asks for it. That is what makes these tools reproducible.
 * </p>
 */
public final class SimTools {
	/** Upper bound for one logisim_sim_tick call, to keep responses bounded. */
	private static final int MAX_TICKS = 100000;

	/** Upper bound for the rows of one logisim_sim_trace call. */
	private static final int MAX_TRACE_ROWS = 1000;

	private SimTools() {
	}

	public static McpTool reset(final McpSession session) {
		return new AbstractTool("logisim_sim_reset", McpInstructions.SIM_RESET,
				Schemas.object().opt("circuit", Schemas.string("Circuit to reset; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				session.resetSimulation(circuit);
				return Json.object("reset", circuit.getName(), "tick_count", 0);
			}
		};
	}

	public static McpTool setInput(final McpSession session) {
		return new AbstractTool("logisim_sim_set_input", McpInstructions.SIM_SET_INPUT,
				Schemas.object()
						.req("name", Schemas.string("Label of the input pin, or its location as \"x,y\""))
						.req("value", Schemas.string("Value to drive, e.g. \"1\", \"0\", \"8\" or \"0x3f\""))
						.opt("propagate", Schemas.bool("Propagate after setting (default true)"))
						.opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				Instance pin = session.inputPinByName(circuit, ctx.requireString("name"));
				String text = ctx.requireString("value");
				Value value = Values.parse(text, widthOf(pin));
				drivePin(state, pin, value);

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("pin", session.pinName(pin));
				ret.addProperty("set", Values.format(value));
				ret.add("parsed", parsed(value, text));
				if (ctx.optBool("propagate", true)) {
					Propagator propagator = settle(session, state);
					ret.addProperty("oscillating", Boolean.valueOf(propagator.isOscillating()));
					addSignals(session, circuit, ret);
				}
				session.noteStoppedSimulator(ret);
				return ret;
			}
		};
	}

	public static McpTool setInputs(final McpSession session) {
		return new AbstractTool("logisim_sim_set_inputs", McpInstructions.SIM_SET_INPUTS,
				Schemas.object()
						.req("values", Schemas.freeObject("Pin label to value, e.g. {\"A\":\"1\",\"B\":\"0\"}"))
						.opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				JsonObject requested = ctx.requireObject("values");
				JsonArray applied = new JsonArray();
				JsonObject understood = new JsonObject();
				for (Map.Entry<String, JsonElement> entry : requested.entrySet()) {
					Instance pin = session.inputPinByName(circuit, entry.getKey());
					String text = entry.getValue().getAsString();
					Value value = Values.parse(text, widthOf(pin));
					drivePin(state, pin, value);
					applied.add(entry.getKey());
					understood.add(entry.getKey(), parsed(value, text));
				}
				Propagator propagator = settle(session, state);

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.add("applied", applied);
				ret.add("parsed", understood);
				ret.addProperty("parsed_note", PARSED_NOTE);
				ret.addProperty("oscillating", Boolean.valueOf(propagator.isOscillating()));
				addSignals(session, circuit, ret);
				session.noteStoppedSimulator(ret);
				return ret;
			}
		};
	}

	public static McpTool read(final McpSession session) {
		return new AbstractTool("logisim_sim_read", McpInstructions.SIM_READ,
				Schemas.object()
						.opt("names", Schemas.array(Schemas.string("pin label or location"),
								"Pin labels, or locations written as \"x,y\""))
						.opt("locs", Schemas.array(Schemas.point("a point [x, y]"),
								"Locations to read, for example a point on an internal wire"))
						.opt("radix", Schemas.stringEnum("Adds a 'text' field to each value, read this way: 'bin' (the "
								+ "default) is the bit pattern the 'value' field already holds; 'hex', 'dec' and 'signed' "
								+ "are the readings a multi-bit result needs", Values.RADIX_NAMES))
						.opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				String radix = radixOf(ctx);

				JsonObject values = new JsonObject();
				JsonArray names = ctx.optArray("names");
				if (names != null) {
					for (JsonElement element : names) {
						String name = element.getAsString();
						Instance pin = session.pinByName(circuit, name);
						Location loc = pin != null ? pin.getLocation() : McpSession.tryParseLocation(name);
						if (loc == null) {
							throw new McpException("'" + name + "' is neither a pin label nor a location \"x,y\" in "
									+ "circuit " + circuit.getName());
						}
						values.add(name, Values.describe(state.getValue(loc), radix));
					}
				}
				JsonArray locs = ctx.optArray("locs");
				if (locs != null) {
					for (JsonElement element : locs) {
						Location loc = McpSession.parseLocation(element);
						values.add(loc.toString(), Values.describe(state.getValue(loc), radix));
					}
				}
				if (names == null && locs == null) {
					throw new McpException("give 'names' (pin labels or \"x,y\" strings), 'locs', or use "
							+ "logisim_sim_read_all to read every pin");
				}
				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("tick_count", state.getPropagator().getTickCount());
				ret.add("values", values);
				session.noteStoppedSimulator(ret);
				return ret;
			}
		};
	}

	public static McpTool readAll(final McpSession session) {
		return new AbstractTool("logisim_sim_read_all", McpInstructions.SIM_READ_ALL,
				Schemas.object()
						.opt("radix", Schemas.stringEnum("How each value should be read in the added 'text' field: "
								+ "'bin' (default), 'hex', 'dec' or 'signed'", Values.RADIX_NAMES))
						.opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				String radix = radixOf(ctx);
				JsonArray pins = new JsonArray();
				for (Instance pin : session.pins(circuit)) {
					JsonObject item = session.describePin(pin);
					Value net = state.getValue(pin.getLocation());
					Value driven = Pin.FACTORY.getValue(state.getInstanceState(pin));
					item.add("net_value", Values.describe(net, radix));
					item.add("driven_value", Values.describe(driven, radix));
					pins.add(item);
				}
				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("tick_count", state.getPropagator().getTickCount());
				ret.addProperty("oscillating", Boolean.valueOf(state.getPropagator().isOscillating()));
				addSignals(session, circuit, ret);
				ret.add("pins", pins);
				session.noteStoppedSimulator(ret);
				return ret;
			}
		};
	}

	public static McpTool propagate(final McpSession session) {
		return new AbstractTool("logisim_sim_propagate", McpInstructions.SIM_PROPAGATE,
				Schemas.object().opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				Propagator propagator = settle(session, state);
				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("oscillating", Boolean.valueOf(propagator.isOscillating()));
				ret.addProperty("tick_count", propagator.getTickCount());
				addSignals(session, circuit, ret);
				session.noteStoppedSimulator(ret);
				return ret;
			}
		};
	}

	public static McpTool tick(final McpSession session) {
		return new AbstractTool("logisim_sim_tick", McpInstructions.SIM_TICK,
				Schemas.object()
						.opt("count", Schemas.integer("Number of clock cycles to advance (default 1)"))
						.opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				int count = ctx.optInt("count", Integer.valueOf(1)).intValue();
				if (count < 1) {
					throw new McpException("'count' must be at least 1");
				}
				if (count > MAX_TICKS) {
					throw new McpException("'count' must not exceed " + MAX_TICKS);
				}
				Propagator propagator = state.getPropagator();
				int done = 0;
				boolean clocked = false;
				for (int i = 0; i < count; i++) {
					session.quietSimulator();
					clocked |= propagator.tick();
					propagator.propagate();
					session.quietSimulator();
					done++;
					if (propagator.isOscillating()) {
						break;
					}
				}
				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("cycles_requested", count);
				ret.addProperty("cycles_done", done);
				ret.addProperty("any_clock_fired", Boolean.valueOf(clocked));
				ret.addProperty("tick_count", propagator.getTickCount());
				ret.addProperty("oscillating", Boolean.valueOf(propagator.isOscillating()));
				addSignals(session, circuit, ret);
				if (!clocked && count > 0) {
					ret.add("note", Json.element("no Clock component fired; add a Clock (Wiring library) to a "
							+ "circuit that should advance over time"));
				}
				session.noteStoppedSimulator(ret);
				return ret;
			}
		};
	}

	/**
	 * Signals over several clock cycles: the table a sequential circuit has to be checked
	 * with. One call replaces "tick, read, tick, read, ...", which is both slower and much
	 * easier to misread.
	 */
	public static McpTool trace(final McpSession session) {
		return new AbstractTool("logisim_sim_trace", McpInstructions.SIM_TRACE,
				Schemas.object()
						.opt("signals", Schemas.array(Schemas.string("a pin label, a component id, a \"c3.1\" port "
								+ "reference or a location written as \"x,y\""),
								"Signals to record, in this order; defaults to every pin of the circuit"))
						.opt("cycles", Schemas.integer("Clock cycles to run and record (default 8)"))
						.opt("tick", Schemas.bool("Run the cycles at all; false records the current values only "
								+ "(default true)"))
						.opt("only_changes", Schemas.bool("Emit a row only when a recorded value changed (default "
								+ "false), which is what makes a long run readable"))
						.opt("initial", Schemas.bool("Record the state before the first cycle as the row with "
								+ "'cycle': 0 (default true)"))
						.opt("radix", Schemas.stringEnum("Reads each cell this way instead of as a bit pattern: "
								+ "'hex', 'dec' or 'signed' (a 32-bit value needs one of these to be readable)",
								Values.RADIX_NAMES))
						.opt("max_rows", Schemas.integer("Refuse a request that would produce more rows than "
								+ "this (default " + MAX_TRACE_ROWS + ")"))
						.opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				String radix = radixOf(ctx);

				List<Location> watched = new ArrayList<Location>();
				List<String> names = new ArrayList<String>();
				JsonArray given = ctx.optArray("signals");
				if (given != null) {
					for (JsonElement element : given) {
						// a label, a component id, a "<id>.<port>" reference or an "x,y" location:
						// the same forms logisim_wire_add accepts, so a signal can be named the way
						// the agent already refers to it
						watched.add(session.resolvePoint(circuit, element));
						names.add(element.isJsonPrimitive() ? element.getAsString() : element.toString());
					}
				} else {
					for (Instance pin : session.pins(circuit)) {
						watched.add(pin.getLocation());
						names.add(session.pinName(pin));
					}
				}
				if (watched.isEmpty()) {
					throw new McpException("this circuit has no pins to watch; pass 'signals' with the \"x,y\" of an "
							+ "internal wire, or add an output pin to observe");
				}

				int maxRows = ctx.optInt("max_rows", Integer.valueOf(MAX_TRACE_ROWS)).intValue();
				if (maxRows < 2 || maxRows > MAX_TRACE_ROWS) {
					throw new McpException("'max_rows' must be between 2 and " + MAX_TRACE_ROWS);
				}
				int cycles = ctx.optInt("cycles", Integer.valueOf(8)).intValue();
				if (cycles < 0) {
					throw new McpException("'cycles' must not be negative");
				}
				if (cycles > MAX_TICKS) {
					throw new McpException("'cycles' must not exceed " + MAX_TICKS);
				}
				if (!ctx.optBool("tick", true)) {
					cycles = 0;
				}
				boolean onlyChanges = ctx.optBool("only_changes", false);
				boolean initial = ctx.optBool("initial", true);
				if (!onlyChanges && cycles + (initial ? 1 : 0) > maxRows) {
					throw new McpException("tracing " + cycles + " cycles would need more than " + maxRows
							+ " rows; use fewer cycles, 'only_changes': true to keep just the transitions, or a larger "
							+ "'max_rows'");
				}

				Propagator propagator = state.getPropagator();
				JsonArray rows = new JsonArray();
				Value[] previous = snapshot(state, watched);
				if (initial) {
					rows.add(traceRow(0, names, previous, radix));
				}
				int changes = 0;
				int cyclesDone = 0;
				boolean anyClockFired = false;
				boolean oscillating = propagator.isOscillating();
				for (int i = 0; i < cycles && !oscillating; i++) {
					session.quietSimulator();
					anyClockFired |= propagator.tick();
					propagator.propagate();
					session.quietSimulator();
					cyclesDone++;
					Value[] current = snapshot(state, watched);
					boolean changed = !same(previous, current);
					if (changed) {
						changes++;
					}
					if (changed || !onlyChanges) {
						rows.add(traceRow(i + 1, names, current, radix));
					}
					previous = current;
					oscillating = propagator.isOscillating();
				}

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.add("signals", Json.array(names));
				ret.addProperty("signal_count", Integer.valueOf(names.size()));
				ret.addProperty("cycles_requested", Integer.valueOf(cycles));
				ret.addProperty("cycles_done", Integer.valueOf(cyclesDone));
				ret.addProperty("tick_count", propagator.getTickCount());
				ret.addProperty("changes", Integer.valueOf(changes));
				ret.addProperty("row_count", Integer.valueOf(rows.size()));
				ret.addProperty("cells", radix == null
						? "bit patterns: 0, 1, x (unknown) or e (error); pass 'radix' for hex, dec or signed"
						: "read as '" + radix + "'");
				ret.add("rows", rows);
				if (oscillating) {
					ret.add("note", Json.element("the circuit is oscillating, so the trace stopped after "
							+ cyclesDone + " cycle(s): a combinational loop never settles"));
				} else if (cycles > 0 && !anyClockFired) {
					ret.add("note", Json.element("no Clock component fired, so nothing advanced: add a Clock "
							+ "(Wiring library) to a circuit that should change over time, or drive it with "
							+ "logisim_sim_set_inputs and read it with logisim_sim_read_all"));
				}
				session.noteStoppedSimulator(ret);
				return ret;
			}
		};
	}

	/** Guard against a script that asks for more vectors than one session should run. */
	private static final int MAX_VECTORS = 500;

	/**
	 * Many vectors, one call: the shape a verification pass actually has. Each vector is applied,
	 * propagated and read before the next one, and an optional 'expect' turns the answer into
	 * pass or fail per vector with the pins that disagreed.
	 */
	public static McpTool runVectors(final McpSession session) {
		return new AbstractTool("logisim_sim_run_vectors", McpInstructions.SIM_RUN_VECTORS,
				Schemas.object()
						.opt("vectors", Schemas.array(Schemas.freeObject("one vector: {values: {pin: value}, "
								+ "expect: {pin: value}, name: \"...\"}; 'expect' and 'name' are optional"),
								"The vectors to run, in order"))
						.opt("values", Schemas.freeObject("A single vector's inputs, as for "
								+ "logisim_sim_set_inputs; use this instead of 'vectors' for one test"))
						.opt("expect", Schemas.freeObject("Expected values for the pins named here, used with "
								+ "'values'"))
						.opt("include_signals", Schemas.bool("Include every pin's value in each row (default true; "
								+ "false keeps only the expected pins, which is far smaller)"))
						.opt("radix", Schemas.stringEnum("How observed values are written: 'bin' (default), 'hex', "
								+ "'dec' or 'signed'; hex and dec make a 32-bit result readable at a glance",
								Values.RADIX_NAMES))
						.opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				String radix = radixOf(ctx);
				List<JsonObject> vectors = new ArrayList<JsonObject>();
				JsonArray given = ctx.optArray("vectors");
				if (given != null) {
					for (JsonElement element : given) {
						if (!element.isJsonObject()) {
							throw new McpException("every entry of 'vectors' must be an object with a 'values' member");
						}
						vectors.add(element.getAsJsonObject());
					}
				} else if (ctx.has("values")) {
					JsonObject single = new JsonObject();
					single.add("values", ctx.requireObject("values"));
					if (ctx.has("expect")) {
						single.add("expect", ctx.requireObject("expect"));
					}
					vectors.add(single);
				} else {
					throw new McpException("give 'vectors' (a list) or 'values' (one vector)");
				}
				if (vectors.size() > MAX_VECTORS) {
					throw new McpException("'vectors' has " + vectors.size() + " entries; the maximum is "
							+ MAX_VECTORS);
				}
				boolean includeSignals = ctx.optBool("include_signals", true);

				JsonArray rows = new JsonArray();
				int passed = 0;
				int failed = 0;
				int errors = 0;
				int index = 0;
				for (JsonObject vector : vectors) {
					index++;
					JsonObject row = new JsonObject();
					row.addProperty("index", Integer.valueOf(index));
					if (Json.isPresent(vector, "name")) {
						row.addProperty("name", vector.get("name").getAsString());
					}
					Propagator propagator = null;
					try {
						JsonObject values = Json.isPresent(vector, "values")
								? vector.getAsJsonObject("values") : null;
						if (values == null) {
							throw new McpException("vector " + index + " has no 'values' object");
						}
						JsonObject applied = new JsonObject();
						for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
							Instance pin = session.inputPinByName(circuit, entry.getKey());
							Value value = Values.parse(entry.getValue().getAsString(), widthOf(pin));
							drivePin(state, pin, value);
							applied.add(entry.getKey(), Values.describe(value, radix));
						}
						propagator = settle(session, state);
						row.add("applied", applied);

						JsonObject expected = Json.isPresent(vector, "expect")
								? vector.getAsJsonObject("expect") : null;
						JsonObject observed = new JsonObject();
						JsonArray mismatches = new JsonArray();
						if (expected != null) {
							for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
								Instance pin = session.pinByName(circuit, entry.getKey());
								if (pin == null) {
									throw new McpException("vector " + index + " expects a pin called '"
											+ entry.getKey() + "', which this circuit does not have");
								}
								Value want = Values.parse(entry.getValue().getAsString(), widthOf(pin));
								Value got = state.getValue(pin.getLocation());
								observed.addProperty(entry.getKey(), read(got, radix));
								if (!want.equals(got)) {
									mismatches.add(entry.getKey() + ": expected " + read(want, radix) + ", read "
											+ read(got, radix));
								}
							}
							row.add("observed", observed);
							row.addProperty("pass", Boolean.valueOf(mismatches.size() == 0));
							if (mismatches.size() == 0) {
								passed++;
							} else {
								failed++;
								row.add("mismatches", mismatches);
							}
						}
						if (includeSignals) {
							addSignals(session, circuit, row);
						}
						if (propagator.isOscillating()) {
							row.addProperty("oscillating", Boolean.TRUE);
						}
					} catch (RuntimeException e) {
						// One vector that cannot be evaluated must never take the batch with it: an
						// exception used to lose every row, so the caller saw nothing but the
						// exception. The vector is counted as failed and named, and the simulation
						// is rebuilt so that the vectors after it run in a clean state - a
						// propagator left inconsistent by a running simulator stays inconsistent.
						errors++;
						failed++;
						row.addProperty("pass", Boolean.FALSE);
						row.addProperty("error", McpSession.message(e));
						row.addProperty("error_note", "this vector could not be evaluated at all; it is counted as "
								+ "failed, and the other vectors were still run. A value that does not fit its pin, "
								+ "a pin this circuit does not have, and a propagator left inconsistent by a running "
								+ "simulator are all named here instead of failing the whole call");
						session.resetSimulation(circuit);
						state = session.stateFor(circuit);
					}
					rows.add(row);
				}

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("vector_count", Integer.valueOf(vectors.size()));
				if (expectedGiven(vectors)) {
					ret.addProperty("passed", Integer.valueOf(passed));
					ret.addProperty("failed", Integer.valueOf(failed));
				}
				if (errors > 0) {
					ret.addProperty("errors", Integer.valueOf(errors));
				}
				ret.add("rows", rows);
				ret.addProperty("hint", errors > 0
						? "the rows carrying an 'error' could not be evaluated at all - the other vectors were still run, "
								+ "so the fault is in those vectors or in what they name, not in the batch"
						: failed > 0
								? "the failing rows name the pins that disagreed; logisim_circuit_nets {problems_only: true} "
										+ "shows which nets those pins sit on"
								: "every vector that had an 'expect' matched");
				session.noteStoppedSimulator(ret);
				return ret;
			}
		};
	}

	private static boolean expectedGiven(List<JsonObject> vectors) {
		for (JsonObject vector : vectors) {
			if (Json.isPresent(vector, "expect")) {
				return true;
			}
		}
		return false;
	}

	public static McpTool status(final McpSession session) {
		return new AbstractTool("logisim_sim_status", McpInstructions.SIM_STATUS,
				Schemas.object().opt("circuit", Schemas.string("Circuit to inspect; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				Propagator propagator = state.getPropagator();
				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("tick_count", propagator.getTickCount());
				ret.addProperty("oscillating", Boolean.valueOf(propagator.isOscillating()));
				ret.addProperty("input_pin_count", session.inputPins(circuit).size());
				ret.addProperty("output_pin_count", session.outputPins(circuit).size());
				ret.addProperty("background_simulator_running", Boolean.valueOf(session.project().getSimulator()
						.isRunning()));
				ret.addProperty("background_simulator_note", "Logisim's simulator starts running by default and its "
						+ "own thread propagates whatever is dirty, so every call that propagates or reads stops it first "
						+ "(two threads in one propagator corrupt its queue). F5 in the Logisim window starts it again");
				addSignals(session, circuit, ret);
				session.noteStoppedSimulator(ret);
				return ret;
			}
		};
	}

	/**
	 * Adds the current pin values to a response, plus an explanation when they
	 * contain unknown or error values, which is nearly always the moment an agent
	 * most needs a nudge.
	 */
	private static void addSignals(McpSession session, Circuit circuit, JsonObject target) {
		JsonObject signals = pinValues(session, circuit);
		target.add("signals", signals);
		String hint = McpSession.explainOddValues(signals);
		if (hint != null) {
			target.addProperty("hint", hint);
		}
	}

	private static BitWidth widthOf(Instance pin) {		BitWidth width = pin.getAttributeValue(StdAttr.WIDTH);
		return width == null ? BitWidth.ONE : width;
	}

	/**
	 * What a tool call meant by one value: the number it was understood as, in hex, or the
	 * bit pattern when some bit is not known. Echoing it is what makes a misread value
	 * visible at the moment it is set, instead of showing up as a strange result later.
	 */
	private static JsonElement parsed(Value value, String given) {
		String hex = Values.hex(value);
		JsonObject ret = new JsonObject();
		ret.addProperty("given", given);
		ret.addProperty("understood_as", hex == null ? Values.format(value) : "0x" + hex);
		return ret;
	}

	/** Said beside an echoed value, since a hex reading and a bit pattern look alike. */
	private static final String PARSED_NOTE = "'understood_as' is how each value was read: 0x-prefixed hex when "
			+ "every bit is known, otherwise the bit pattern (x unknown, e unknown result, z high impedance). "
			+ "A value that does not fit the pin is refused rather than rounded";

	/** The 'radix' argument, checked once so an unusable one is refused before anything runs. */
	private static String radixOf(ToolContext ctx) {
		String radix = ctx.optString("radix", null);
		if (radix == null) {
			return null;
		}
		for (String name : Values.RADIX_NAMES) {
			if (name.equalsIgnoreCase(radix)) {
				return name;
			}
		}
		throw new McpException("'" + radix + "' is not a way to read a value; use bin, hex, dec or signed");
	}

	/** A value as text: the reading asked for, falling back to the bit pattern. */
	private static String read(Value value, String radix) {
		String text = radix == null ? null : Values.reading(value, radix);
		return text == null ? Values.format(value) : text;
	}

	/**
	 * Drives an input pin.
	 *
	 * <p>
	 * Setting the pin's value is not enough on its own: Logisim only recomputes the
	 * parts of a circuit that have been marked dirty, so the component has to be
	 * invalidated afterwards. This is exactly what Logisim's own pin poker does when
	 * a user clicks a pin, and it is why a plain {@code setValue} followed by
	 * {@code propagate} would otherwise leave the old values in place.
	 * </p>
	 */
	private static void drivePin(CircuitState state, Instance pin, Value value) {
		InstanceState pinState = state.getInstanceState(pin);
		Pin.FACTORY.setValue(pinState, value);
		pinState.fireInvalidated();
	}

	/**
	 * Propagates with the window's own simulator held still, and holds it still again
	 * afterwards, so that nothing propagates behind the call's back. Logisim's simulator
	 * thread and this call propagating at the same moment corrupt the propagator's queue
	 * ({@link McpSession#quietSimulator()}), which is what makes a read look like the
	 * previous vector or fail with a NullPointerException.
	 */
	private static Propagator settle(McpSession session, CircuitState state) {
		session.quietSimulator();
		Propagator propagator = state.getPropagator();
		propagator.propagate();
		session.quietSimulator();
		return propagator;
	}

	/** The values of the watched signals right now, in the order they were asked for. */
	private static Value[] snapshot(CircuitState state, List<Location> watched) {
		Value[] ret = new Value[watched.size()];
		for (int i = 0; i < ret.length; i++) {
			ret[i] = state.getValue(watched.get(i));
		}
		return ret;
	}

	/** True when two snapshots hold the same values, which is what 'only_changes' compares. */
	private static boolean same(Value[] a, Value[] b) {
		if (a == null || b == null || a.length != b.length) {
			return false;
		}
		for (int i = 0; i < a.length; i++) {
			Value left = a[i];
			Value right = b[i];
			if (left == right) {
				continue;
			}
			if (left == null || right == null || !left.equals(right)) {
				return false;
			}
		}
		return true;
	}

	/** One row of a sequence table. */
	private static JsonObject traceRow(int cycle, List<String> names, Value[] values, String radix) {
		JsonObject ret = new JsonObject();
		ret.addProperty("cycle", Integer.valueOf(cycle));
		JsonObject cells = new JsonObject();
		for (int i = 0; i < names.size() && i < values.length; i++) {
			cells.addProperty(names.get(i), read(values[i], radix));
		}
		ret.add("values", cells);
		return ret;
	}

	/** Compact name-to-value map of every pin in a circuit. */
	static JsonObject pinValues(McpSession session, Circuit circuit) {
		CircuitState state = session.stateFor(circuit);
		JsonObject ret = new JsonObject();
		for (Instance pin : session.pins(circuit)) {
			ret.addProperty(session.pinName(pin), Values.format(state.getValue(pin.getLocation())));
		}
		return ret;
	}
}

