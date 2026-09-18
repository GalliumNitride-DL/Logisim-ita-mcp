/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import com.cburch.logisim.Main;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Propagator;
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

/** Session level information and undo. */
public final class SessionTools {
	private SessionTools() {
	}

	public static McpTool status(final McpSession session, final String transportDescription,
			final com.cburch.logisim.mcp.ToolRegistry registry) {
		return new AbstractTool("logisim_status", McpInstructions.STATUS,
				Schemas.object()) {
			@Override
			public JsonElement call(ToolContext ctx) {
				JsonObject ret = new JsonObject();

				JsonObject server = new JsonObject();
				server.addProperty("name", "logisim-mcp");
				server.addProperty("version", Main.VERSION.mainVersion());
				server.addProperty("logisim_full_version", Main.VERSION_NAME);
				server.addProperty("transport", transportDescription);
				server.addProperty("tool_count", registry == null ? 0 : registry.size());
				ret.add("server", server);

				JsonObject fileInfo = new JsonObject();
				fileInfo.addProperty("open", Boolean.valueOf(session.isOpen()));
				if (session.isOpen()) {
					fileInfo.addProperty("path", session.defaultSaveLocation());
					fileInfo.addProperty("name", session.file().getName());
					fileInfo.addProperty("dirty", Boolean.valueOf(session.file().isDirty()));
					fileInfo.addProperty("main_circuit", session.file().getMainCircuit() == null ? null
							: session.file().getMainCircuit().getName());
					JsonArray names = new JsonArray();
					for (Circuit circuit : session.file().getCircuits()) {
						names.add(circuit.getName());
					}
					fileInfo.add("circuits", names);
					fileInfo.addProperty("circuit_count", session.file().getCircuits().size());
				}
				ret.add("file", fileInfo);

				if (session.isOpen()) {
					Circuit current = session.circuit();
					JsonObject circuitInfo = new JsonObject();
					circuitInfo.addProperty("name", current.getName());
					circuitInfo.addProperty("component_count", current.getNonWires().size());
					circuitInfo.addProperty("wire_count", current.getWires().size());
					circuitInfo.addProperty("input_pin_count", session.inputPins(current).size());
					circuitInfo.addProperty("output_pin_count", session.outputPins(current).size());
					ret.add("current_circuit", circuitInfo);

					JsonObject simulation = new JsonObject();
					Propagator propagator = session.state().getPropagator();
					simulation.addProperty("tick_count", propagator.getTickCount());
					simulation.addProperty("oscillating", Boolean.valueOf(propagator.isOscillating()));
					simulation.addProperty("simulator_running", Boolean.valueOf(session.project().getSimulator()
							.isRunning()));
					ret.add("simulation", simulation);
				}
				ret.add("memory", McpSession.memoryReport());
				if (session.lastRepairReport() != null) {
					ret.add("last_wire_repair", session.lastRepairReport());
				}
				return ret;
			}
		};
	}

	public static McpTool undo(final McpSession session) {
		return new AbstractTool("logisim_undo", McpInstructions.UNDO,
				Schemas.object()) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				session.undo();
				return Json.object("undone", Boolean.TRUE, "dirty", Boolean.valueOf(session.file().isDirty()));
			}
		};
	}

	public static McpTool circuitDetails(final McpSession session) {
		return new AbstractTool("logisim_circuit_info", McpInstructions.CIRCUIT_INFO,
				Schemas.object().opt("circuit", Schemas.string("Circuit name; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				return session.describeCircuit(circuit, false, true);
			}
		};
	}

	static void requireCircuit(Circuit circuit, String what) {
		if (circuit == null) {
			throw new McpException("no circuit available for " + what);
		}
	}
}

