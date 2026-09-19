/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;
import com.cburch.logisim.mcp.McpInstructions;

import com.google.gson.JsonElement;

/**
 * Net level introspection: which ports are on which node, which ports touch nothing,
 * and which node has more than one driver. This is the tool that answers "why does my
 * output read x or e" without a screen.
 */
public final class NetTools {
	private NetTools() {
	}

	public static McpTool nets(final McpSession session) {
		return new AbstractTool("logisim_circuit_nets", McpInstructions.CIRCUIT_NETS,
				Schemas.object()
						.opt("circuit", Schemas.string("Circuit to inspect; defaults to the current circuit"))
						.opt("net", Schemas.string("Only report this net, as an id returned by a previous call "
								+ "(for example \"n3\", or just \"3\")"))
						.opt("port", Schemas.any("Only report the net that this port belongs to: a pin or component "
								+ "label (\"S\"), a component id (\"c3\") or one of its ports (\"c3.1\")"))
						.opt("include_unconnected", Schemas.bool("Also list the ports that touch no wire at all "
								+ "(default true)"))
						.opt("problems_only", Schemas.bool("Only report the nets that are wrong: more than one driver "
								+ "(reads 'e'), or nothing driving them (reads 'x'). Use this when checking a finished circuit, "
								+ "instead of reading every net"))
						.opt("limit", Schemas.integer("Maximum number of nets to return, largest first (default 100)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				String netFilter = ctx.optString("net", null);
				Location portFilter = null;
				JsonElement port = ctx.optElement("port");
				if (port != null) {
					portFilter = session.resolvePoint(circuit, port);
				}
				boolean includeUnconnected = ctx.optBool("include_unconnected", true);
				boolean problemsOnly = ctx.optBool("problems_only", false);
				int limit = ctx.optInt("limit", Integer.valueOf(100)).intValue();
				if (limit <= 0) {
					throw new com.cburch.logisim.mcp.McpException("'limit' must be greater than zero");
				}
				return session.describeNets(circuit, netFilter, portFilter, includeUnconnected, problemsOnly, limit);
			}
		};
	}
}

