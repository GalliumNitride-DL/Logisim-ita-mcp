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
import com.cburch.logisim.mcp.McpInstructions;

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
		return new AbstractTool("logisim_circuit_repair_wires", McpInstructions.CIRCUIT_REPAIR_WIRES,
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

