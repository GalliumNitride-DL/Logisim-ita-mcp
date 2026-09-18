/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

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

	private SimTools() {
	}

	public static McpTool reset(final McpSession session) {
		return new AbstractTool("logisim_sim_reset",
				"Resets the simulation of a circuit: all stored signal values, flip-flop and memory contents are "
						+ "thrown away and the circuit starts from its initial (undefined) state. The circuit's "
						+ "components and wires are not touched.",
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
		return new AbstractTool("logisim_sim_set_input",
				"Drives one input pin to a value and propagates the circuit, so combinational logic settles "
						+ "immediately. Values may be 0, 1, x (unknown), e (error/conflict), z (high impedance), or an "
						+ "integer for a multi-bit pin (decimal, 0x hex, 0b binary). Identify the pin by its label or "
						+ "by its location \"x,y\".",
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
				Value value = Values.parse(ctx.requireString("value"), widthOf(pin));
				drivePin(state, pin, value);

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("pin", session.pinName(pin));
				ret.addProperty("set", Values.format(value));
				if (ctx.optBool("propagate", true)) {
					Propagator propagator = state.getPropagator();
					propagator.propagate();
					ret.addProperty("oscillating", Boolean.valueOf(propagator.isOscillating()));
					addSignals(session, circuit, ret);
				}
				return ret;
			}
		};
	}

	public static McpTool setInputs(final McpSession session) {
		return new AbstractTool("logisim_sim_set_inputs",
				"Drives several input pins at once and propagates the circuit once at the end, which is the fast and "
						+ "deterministic way to apply a test vector before reading the outputs.",
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
				for (Map.Entry<String, JsonElement> entry : requested.entrySet()) {
					Instance pin = session.inputPinByName(circuit, entry.getKey());
					Value value = Values.parse(entry.getValue().getAsString(), widthOf(pin));
					drivePin(state, pin, value);
					applied.add(entry.getKey());
				}
				Propagator propagator = state.getPropagator();
				propagator.propagate();

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.add("applied", applied);
				ret.addProperty("oscillating", Boolean.valueOf(propagator.isOscillating()));
				addSignals(session, circuit, ret);
				return ret;
			}
		};
	}

	public static McpTool read(final McpSession session) {
		return new AbstractTool("logisim_sim_read",
				"Reads the current value of named signals (pin labels) or of specific locations, without changing "
						+ "anything. Values are those from the most recent propagation; call logisim_sim_propagate or "
						+ "logisim_sim_set_input first if the circuit has just been edited.",
				Schemas.object()
						.opt("names", Schemas.array(Schemas.string("pin label or location"),
								"Pin labels, or locations written as \"x,y\""))
						.opt("locs", Schemas.array(Schemas.point("a point [x, y]"),
								"Locations to read, for example a point on an internal wire"))
						.opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);

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
						values.add(name, Values.describe(state.getValue(loc)));
					}
				}
				JsonArray locs = ctx.optArray("locs");
				if (locs != null) {
					for (JsonElement element : locs) {
						Location loc = McpSession.parseLocation(element);
						values.add(loc.toString(), Values.describe(state.getValue(loc)));
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
				return ret;
			}
		};
	}

	public static McpTool readAll(final McpSession session) {
		return new AbstractTool("logisim_sim_read_all",
				"Reads every pin of a circuit at once and returns the values both as a simple name-to-value map and "
						+ "as a detailed list including each pin's direction, width, position and the value the pin "
						+ "itself is driving. This is the quickest way to see the result of a test vector.",
				Schemas.object().opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				JsonArray pins = new JsonArray();
				for (Instance pin : session.pins(circuit)) {
					JsonObject item = session.describePin(pin);
					Value net = state.getValue(pin.getLocation());
					Value driven = Pin.FACTORY.getValue(state.getInstanceState(pin));
					item.add("net_value", Values.describe(net));
					item.add("driven_value", Values.describe(driven));
					pins.add(item);
				}
				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("tick_count", state.getPropagator().getTickCount());
				ret.addProperty("oscillating", Boolean.valueOf(state.getPropagator().isOscillating()));
				addSignals(session, circuit, ret);
				ret.add("pins", pins);
				return ret;
			}
		};
	}

	public static McpTool propagate(final McpSession session) {
		return new AbstractTool("logisim_sim_propagate",
				"Settles all combinational logic and returns the resulting pin values. Clocked components do not "
						+ "advance: use logisim_sim_tick for that. If the circuit never settles, 'oscillating' comes "
						+ "back true, which usually means a combinational feedback loop.",
				Schemas.object().opt("circuit", Schemas.string("Circuit to use; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitState state = session.stateFor(circuit);
				Propagator propagator = state.getPropagator();
				propagator.propagate();
				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("oscillating", Boolean.valueOf(propagator.isOscillating()));
				ret.addProperty("tick_count", propagator.getTickCount());
				addSignals(session, circuit, ret);
				return ret;
			}
		};
	}

	public static McpTool tick(final McpSession session) {
		return new AbstractTool("logisim_sim_tick",
				"Advances the simulation by whole clock cycles: each cycle fires one edge on every Clock component "
						+ "and then settles the logic, which is how sequential circuits (flip-flops, counters, "
						+ "registers, control units) make progress. Returns the pin values after the last cycle.",
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
					clocked |= propagator.tick();
					propagator.propagate();
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
				return ret;
			}
		};
	}

	public static McpTool status(final McpSession session) {
		return new AbstractTool("logisim_sim_status",
				"Reports how the simulation of a circuit currently stands: how many clock cycles have elapsed, "
						+ "whether the circuit is oscillating (never settling), and the current pin values.",
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
				addSignals(session, circuit, ret);
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
