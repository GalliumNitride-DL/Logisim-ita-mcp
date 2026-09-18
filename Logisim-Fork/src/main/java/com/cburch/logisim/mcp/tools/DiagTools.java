/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.mcp.Json;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Repair and health checks for the one class of damage that Logisim cannot survive: a
 * wire whose ends are not axis aligned.
 */
public final class DiagTools {
	private DiagTools() {
	}

	public static McpTool repairWires(final McpSession session) {
		return new AbstractTool("logisim_circuit_repair_wires",
				"Repairs wires whose two ends differ in both x and y. Logisim should never contain such a wire, but "
						+ "its own wire repair can create one, and it cannot iterate one: WireIterator walks from end to "
						+ "end diagonally and only stops when it lands exactly on the far end, which never happens, so "
						+ "iteration never terminates. Since every edit makes Logisim walk every point of every wire "
						+ "(and so does opening a file), a single such wire makes the next edit, and every later load "
						+ "of the saved file, spin until the Java heap is exhausted - 'Java heap space' after a long "
						+ "pause is the classic symptom. This tool replaces each such wire with two axis aligned "
						+ "segments through a corner, keeping both ends connected. It is safe to run on a damaged "
						+ "circuit because the broken wire is removed before Logisim repairs the circuit's wires, so it "
						+ "is never walked. Every edit and every save already repairs such wires automatically, so this "
						+ "tool is normally only needed to inspect a circuit or to force a repair.",
				Schemas.object()
						.opt("circuit", Schemas.string("Circuit to repair; defaults to the current circuit"))
						.opt("dry_run", Schemas.bool("Only report what would be repaired (default false)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				boolean dryRun = ctx.optBool("dry_run", false);
				JsonObject ret = session.repairSkewedWires(circuit, dryRun);
				if (ret.get("broken_wires").getAsInt() == 0) {
					ret.add("note", Json.element("every wire in this circuit is axis aligned; edits are safe"));
				}
				ret.add("hint", Json.element("call logisim_circuit_describe and check that skewed_wire_count is 0; "
						+ "a circuit is also repaired by making any edit, or by saving the file"));
				return ret;
			}
		};
	}
}
