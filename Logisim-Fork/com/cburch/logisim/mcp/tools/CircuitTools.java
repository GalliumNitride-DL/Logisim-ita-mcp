/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.util.Collection;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.FileStatistics;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.mcp.Json;
import com.cburch.logisim.mcp.McpException;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;
import com.cburch.logisim.mcp.McpInstructions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Tools for creating and inspecting whole circuits (schematic sheets). */
public final class CircuitTools {
	private CircuitTools() {
	}

	public static McpTool list(final McpSession session) {
		return new AbstractTool("logisim_circuit_list", McpInstructions.CIRCUIT_LIST,
				Schemas.object()) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				JsonArray circuits = new JsonArray();
				for (Circuit circuit : session.file().getCircuits()) {
					JsonObject item = new JsonObject();
					item.addProperty("name", circuit.getName());
					item.addProperty("is_main", Boolean.valueOf(circuit == session.file().getMainCircuit()));
					item.addProperty("component_count", circuit.getNonWires().size());
					item.addProperty("wire_count", circuit.getWires().size());
					item.addProperty("input_pin_count", session.inputPins(circuit).size());
					item.addProperty("output_pin_count", session.outputPins(circuit).size());
					Collection<Circuit> users = circuit.getCircuitsUsingThis();
					item.addProperty("used_by_subcircuit_instances", users == null ? 0 : users.size());
					circuits.add(item);
				}
				JsonObject ret = new JsonObject();
				ret.add("circuits", circuits);
				ret.addProperty("current", session.circuit().getName());
				return ret;
			}
		};
	}

	public static McpTool describe(final McpSession session) {
		return new AbstractTool("logisim_circuit_describe", McpInstructions.CIRCUIT_DESCRIBE,
				Schemas.object()
						.opt("circuit", Schemas.string("Circuit name; defaults to the current circuit"))
						.opt("include_wires", Schemas.bool("Include the wire list (default true)"))
						.opt("include_attributes", Schemas.bool("Include component attributes (default true)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				return session.describeCircuit(circuit, ctx.optBool("include_wires", true),
						ctx.optBool("include_attributes", true));
			}
		};
	}

	public static McpTool create(final McpSession session) {
		return new AbstractTool("logisim_circuit_create", McpInstructions.CIRCUIT_CREATE,
				Schemas.object().req("name", Schemas.string("Name of the new circuit, must not already exist"))
						.opt("set_current", Schemas.bool("Make the new circuit current (default true)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				String name = ctx.requireString("name").trim();
				if (name.isEmpty()) {
					throw new McpException("circuit name must not be empty");
				}
				if (session.file().getCircuit(name) != null) {
					throw new McpException("a circuit named '" + name + "' already exists");
				}
				Circuit circuit = new Circuit(name);
				session.project().doAction(LogisimFileActions.addCircuit(circuit));
				session.invalidateCatalog();
				if (ctx.optBool("set_current", true)) {
					session.setCircuit(circuit);
				}
				JsonObject ret = Json.object("created", name, "current",
						Boolean.valueOf(ctx.optBool("set_current", true)));
				ret.add("hint", Json.element("add pins to this circuit, then place it inside another circuit "
						+ "with logisim_component_add {lib: 'Circuits', type: '" + name + "'}"));
				return ret;
			}
		};
	}

	public static McpTool remove(final McpSession session) {
		return new AbstractTool("logisim_circuit_delete", McpInstructions.CIRCUIT_DELETE,
				Schemas.object().req("name", Schemas.string("Name of the circuit to delete"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				String name = ctx.requireString("name").trim();
				Circuit circuit = session.circuitByName(name);
				if (session.file().getCircuits().size() <= 1) {
					throw new McpException("a project must keep at least one circuit");
				}
				if (circuit == session.file().getMainCircuit()) {
					throw new McpException("'" + name + "' is the main circuit; make another circuit the main one "
							+ "with logisim_circuit_set_main first");
				}
				Collection<Circuit> users = circuit.getCircuitsUsingThis();
				if (users != null && !users.isEmpty()) {
					StringBuilder buf = new StringBuilder();
					for (Circuit user : users) {
						if (buf.length() > 0) {
							buf.append(", ");
						}
						buf.append(user.getName());
					}
					throw new McpException("'" + name + "' is still used as a subcircuit inside: " + buf.toString()
							+ "; remove those instances first");
				}
				session.project().doAction(LogisimFileActions.removeCircuit(circuit));
				session.invalidateCatalog();
				return Json.object("deleted", name);
			}
		};
	}

	public static McpTool setMain(final McpSession session) {
		return new AbstractTool("logisim_circuit_set_main", McpInstructions.CIRCUIT_SET_MAIN,
				Schemas.object().req("name", Schemas.string("Name of the circuit to make main"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.requireString("name"));
				session.project().doAction(LogisimFileActions.setMainCircuit(circuit));
				return Json.object("main_circuit", circuit.getName());
			}
		};
	}

	public static McpTool select(final McpSession session) {
		return new AbstractTool("logisim_circuit_select", McpInstructions.CIRCUIT_SELECT,
				Schemas.object()
						.opt("circuit", Schemas.string("Name of the circuit to work on, as every other tool calls "
								+ "it. 'name' is accepted as well"))
						.opt("name", Schemas.string("The same thing, for callers that already use this spelling"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				String wanted = ctx.optString("circuit", null);
				if (wanted == null) {
					wanted = ctx.optString("name", null);
				}
				if (wanted == null) {
					throw new McpException("give 'circuit': the name of the circuit to work on");
				}
				Circuit previous = session.circuit();
				Circuit circuit = session.circuitByName(wanted);
				session.setCircuit(circuit);
				return Json.object("current", circuit.getName(), "was", previous.getName(), "component_count",
						circuit.getNonWires().size(), "wire_count", circuit.getWires().size());
			}
		};
	}

	public static McpTool statistics(final McpSession session) {
		return new AbstractTool("logisim_circuit_stats", McpInstructions.CIRCUIT_STATS,
				Schemas.object().opt("circuit", Schemas.string("Circuit name; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				FileStatistics stats = FileStatistics.compute(session.file(), circuit);
				JsonArray counts = new JsonArray();
				for (FileStatistics.Count count : stats.getCounts()) {
					JsonObject item = new JsonObject();
					item.addProperty("component", count.getFactory().getDisplayName());
					if (count.getLibrary() != null) {
						item.addProperty("library", count.getLibrary().getDisplayName());
					}
					item.addProperty("unique", count.getUniqueCount());
					item.addProperty("including_subcircuits", count.getRecursiveCount());
					counts.add(item);
				}
				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.add("counts", counts);
				FileStatistics.Count without = stats.getTotalWithoutSubcircuits();
				FileStatistics.Count with = stats.getTotalWithSubcircuits();
				ret.add("total_this_circuit", Json.object("unique", without.getUniqueCount(), "including_nested",
						without.getRecursiveCount()));
				ret.add("total_including_subcircuits",
						Json.object("unique", with.getUniqueCount(), "including_nested", with.getRecursiveCount()));
				return ret;
			}
		};
	}
}

