/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import com.cburch.logisim.mcp.Json;
import com.cburch.logisim.mcp.McpInstructions;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.SelfTest;
import com.cburch.logisim.mcp.ToolContext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The one call that answers "is this server working?".
 *
 * <p>
 * It needs no project of its own: the checks run in a session of their own, which is why this
 * tool is not tied to the session the way the others are, and why it is safe to call at any
 * moment - with a file open, in live mode, or while the agent is still deciding what to build.
 * </p>
 */
public final class SelfTools {
	private SelfTools() {
	}

	public static McpTool selfTest() {
		return new AbstractTool("logisim_self_test", McpInstructions.SELF_TEST,
				Schemas.object()
						.opt("checks", Schemas.array(Schemas.string("a check name, for example \"simulate\""),
								"Run only these checks; the names are listed in the description"))
						.opt("verbose", Schemas.bool("Also return the checks that passed (default false: only the "
								+ "failures are returned, so a healthy run is a few lines)"))
						.opt("keep_files", Schemas.bool("Keep the scratch project and the damaged file it builds "
								+ "in the temp directory, to be looked at (default false; a failing run keeps them "
								+ "anyway)"))
						.opt("temp_dir", Schemas.string("Where to put the scratch files (default: the system "
								+ "temp directory)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				JsonObject options = new JsonObject();
				JsonArray checks = ctx.optArray("checks");
				if (checks != null) {
					options.add("checks", checks);
				}
				options.addProperty("verbose", Boolean.valueOf(ctx.optBool("verbose", false)));
				options.addProperty("keep_files", Boolean.valueOf(ctx.optBool("keep_files", false)));
				String directory = ctx.optString("temp_dir", null);
				if (directory != null && !directory.trim().isEmpty()) {
					options.addProperty("temp_dir", directory);
				}
				return Json.element(SelfTest.run(options));
			}
		};
	}
}
