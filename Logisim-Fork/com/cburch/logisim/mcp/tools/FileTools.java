/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.io.File;

import com.cburch.logisim.circuit.Circuit;
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

/**
 * File level tools. Every read and write goes through Logisim's own loader and
 * writer, so the on disk file format is always exactly what Logisim itself
 * produces.
 */
public final class FileTools {
	private FileTools() {
	}

	public static McpTool newFile(final McpSession session) {
		return new AbstractTool("logisim_file_new", McpInstructions.FILE_NEW,
				Schemas.object()) {
			@Override
			public JsonElement call(ToolContext ctx) {
				if (session.isLive()) {
					throw new McpException("logisim_file_new is refused while the server runs inside the Logisim window: it "
							+ "would replace the project the user has open. Use logisim_file_open, which brings up a new "
							+ "window, or ask the user to create the project.");
				}
				session.newFile();
				return describeState(session, "created a new empty project in memory");
			}
		};
	}

	public static McpTool open(final McpSession session) {
		return new AbstractTool("logisim_file_open", McpInstructions.FILE_OPEN,
				Schemas.object().req("path", Schemas.string("Path to the .circ file to open"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				File path = new File(ctx.requireString("path")).getAbsoluteFile();
				if (session.isLive()) {
					// Refuse a damaged file before Logisim's own Open does, because that path would
					// report the failure with a dialog box and hang the call until it is dismissed.
					session.requireOpenable(path);
					boolean already = com.cburch.logisim.proj.Projects.findProjectFor(path) != null;
					com.cburch.logisim.mcp.GuiBridge.openFile(path);
					JsonObject ret = new JsonObject();
					ret.addProperty("opened", path.getAbsolutePath());
					ret.addProperty("new_window", Boolean.valueOf(!already));
					ret.add("hint", Json.element(already
							? "that file was already open, so its window was brought to the front instead of being reopened"
							: "a new Logisim window is open for the user; the next tool call works on it"));
					return ret;
				}
				session.open(path);
				return describeState(session, "opened " + path.getAbsolutePath());
			}
		};
	}

	public static McpTool save(final McpSession session) {
		return new AbstractTool("logisim_file_save", McpInstructions.FILE_SAVE,
				Schemas.object().opt("path", Schemas.string("Destination path; defaults to the file this session "
						+ "was opened from"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				String requested = ctx.optString("path", null);
				if (requested == null && session.isLive()) {
					if (session.path() == null) {
						throw new McpException("this project has not been written to a file yet, so 'path' is required; "
								+ "give the full path to write, or let the user save it from the File menu");
					}
					boolean ok = com.cburch.logisim.mcp.GuiBridge.saveProject(session.project());
					if (!ok) {
						throw new McpException("Logisim could not save '" + session.path().getAbsolutePath() + "'");
					}
					JsonObject ret = Json.object("saved", Boolean.TRUE, "path", session.path().getAbsolutePath(),
							"dirty", Boolean.valueOf(session.file().isDirty()));
					JsonArray healed = session.lastRepairReport();
					if (healed != null && healed.size() > 0) {
						ret.add("wire_repair", healed);
					}
					return ret;
				}
				File destination;
				if (requested == null) {
					if (session.path() == null) {
						throw new McpException("this session was not opened from a file, so a 'path' is required");
					}
					destination = session.path();
				} else {
					destination = new File(requested).getAbsoluteFile();
				}
				session.save(destination);
				JsonObject ret = Json.object("saved", Boolean.TRUE, "path", destination.getAbsolutePath(), "dirty",
						Boolean.valueOf(session.file().isDirty()));
				JsonArray healed = session.lastRepairReport();
				if (healed != null && healed.size() > 0) {
					ret.add("wire_repair", healed);
				}
				return ret;
			}
		};
	}

	public static McpTool recover(final McpSession session) {
		return new AbstractTool("logisim_file_recover", McpInstructions.FILE_RECOVER,
				Schemas.object().req("path", Schemas.string("Path to the .circ file to recover"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireHeadless(name());
				File path = new File(ctx.requireString("path")).getAbsoluteFile();
				session.recover(path);
				JsonObject ret = describeState(session, "recovered " + path.getAbsolutePath());
				JsonArray rewrites = session.lastRecoveryReport();
				if (rewrites != null && rewrites.size() > 0) {
					ret.addProperty("wires_rewritten", rewrites.size());
					ret.add("rewrites", rewrites);
				}
				ret.add("hint", Json.element("the file on disk is untouched; call logisim_file_save_as to write "
						+ "the repaired file, and check skewed_wire_count is 0 with logisim_circuit_describe"));
				return ret;
			}
		};
	}

	public static McpTool saveAs(final McpSession session) {		return new AbstractTool("logisim_file_save_as", McpInstructions.FILE_SAVE_AS,
				Schemas.object().req("path", Schemas.string("Destination path for the .circ file"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				File destination = new File(ctx.requireString("path")).getAbsoluteFile();
				session.save(destination);
				JsonObject ret = Json.object("saved", Boolean.TRUE, "path", destination.getAbsolutePath());
				JsonArray healed = session.lastRepairReport();
				if (healed != null && healed.size() > 0) {
					ret.add("wire_repair", healed);
				}
				return ret;
			}
		};
	}

	public static McpTool close(final McpSession session) {
		return new AbstractTool("logisim_file_close", McpInstructions.FILE_CLOSE,
				Schemas.object()) {
			@Override
			public JsonElement call(ToolContext ctx) {
				if (session.isLive()) {
					throw new McpException("logisim_file_close is refused while the server runs inside the Logisim window: "
							+ "closing would discard the user's work. Ask the user to close the window.");
				}
				session.close();
				return Json.object("open", Boolean.FALSE);
			}
		};
	}

	public static McpTool info(final McpSession session) {
		return new AbstractTool("logisim_file_info", McpInstructions.FILE_INFO,
				Schemas.object()) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				JsonObject ret = new JsonObject();
				ret.addProperty("path", session.defaultSaveLocation());
				ret.addProperty("name", session.file().getName());
				ret.addProperty("dirty", Boolean.valueOf(session.file().isDirty()));
				ret.addProperty("main_circuit", session.file().getMainCircuit().getName());
				JsonArray circuits = new JsonArray();
				for (Circuit circuit : session.file().getCircuits()) {
					circuits.add(circuit.getName());
				}
				ret.add("circuits", circuits);
				JsonArray libraries = new JsonArray();
				for (com.cburch.logisim.tools.Library library : session.file().getLibraries()) {
					libraries.add(library.getName());
				}
				ret.add("libraries", libraries);
				return ret;
			}
		};
	}

	private static JsonObject describeState(McpSession session, String what) {
		JsonObject ret = new JsonObject();
		ret.addProperty("status", what);
		ret.addProperty("path", session.defaultSaveLocation());
		ret.addProperty("dirty", Boolean.valueOf(session.file().isDirty()));
		ret.addProperty("main_circuit", session.file().getMainCircuit() == null ? null
				: session.file().getMainCircuit().getName());
		JsonArray circuits = new JsonArray();
		for (Circuit circuit : session.file().getCircuits()) {
			circuits.add(circuit.getName());
		}
		ret.add("circuits", circuits);
		ret.addProperty("hint", "nothing has been written to disk yet");
		return ret;
	}
}

