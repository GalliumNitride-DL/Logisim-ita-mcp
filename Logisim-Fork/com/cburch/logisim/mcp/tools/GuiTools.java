/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.mcp.ActivityLog;
import com.cburch.logisim.mcp.GuiBridge;
import com.cburch.logisim.mcp.Json;
import com.cburch.logisim.mcp.McpException;
import com.cburch.logisim.mcp.McpMain;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.McpHttpServer;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;
import com.cburch.logisim.proj.Project;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The tools that only exist because the user is watching: they report what is on
 * screen, bring a window or circuit to the front, and put what the agent touched into
 * the selection so it is visibly highlighted.
 *
 * <p>
 * All of them run on the event dispatch thread already (the call path makes sure of
 * that in live mode), so they may touch Swing and the project model directly.
 * </p>
 */
public final class GuiTools {
	private GuiTools() {
	}

	public static McpTool status(final McpSession session) {
		return new AbstractTool("logisim_gui_status",				"What the user can see right now, and what state this session is in: whether the server runs inside "
						+ "a Logisim window (live mode) or headless, the transport and its address, every open window "
						+ "with its file, unsaved-changes flag, current circuit and selection, whether the user has "
						+ "paused the agent or turned on read-only mode, and the tail of the activity log (the same "
						+ "lines the user sees). Call this first in a session, and after anything that suggests the "
						+ "user changed something themselves.",
				Schemas.object()) {
			@Override
			public JsonElement call(ToolContext ctx) {
				JsonObject ret = new JsonObject();
				ret.addProperty("mode", session.isLive() ? "live (inside a Logisim window)" : "headless");
				ret.addProperty("read_only", Boolean.valueOf(session.isReadOnly()));
				ret.addProperty("paused", Boolean.valueOf(session.isPaused()));
				if (session.isPausedByUser()) {
					ret.addProperty("paused_by", "the user, from the Logisim window");
				}
				if (session.pausedReason() != null) {
					ret.addProperty("pause_reason", session.pausedReason());
				}
				McpHttpServer http = McpMain.httpServer();
				if (http != null) {
					JsonObject server = new JsonObject();
					server.addProperty("transport", "http");
					server.addProperty("url", http.url());
					server.addProperty("requests", http.requestCount());
					server.addProperty("tool_calls", http.toolCallCount());
					if (http.sessionId() != null) {
						server.addProperty("client_attached", Boolean.TRUE);
					}
					ret.add("server", server);
				}
				if (session.isLive()) {
					JsonArray windows = GuiBridge.describeWindows(session);
					ret.addProperty("window_count", windows.size());
					ret.add("windows", windows);
					Frame focused = GuiBridge.focusedFrame();
					if (focused != null && focused.getProject() != null) {
						Project project = focused.getProject();
						JsonObject current = new JsonObject();
						current.addProperty("title", focused.getTitle());
						if (project.getCurrentCircuit() != null) {
							current.addProperty("circuit", project.getCurrentCircuit().getName());
						}
						List<String> selected = GuiBridge.selection(session, project);
						current.addProperty("selection_count", selected.size());
						if (!selected.isEmpty()) {
							JsonArray items = new JsonArray();
							for (String item : selected) {
								items.add(item);
							}
							current.add("selection", items);
						}
						ret.add("focused_window", current);
					}
				} else if (session.isOpen()) {
					JsonObject file = new JsonObject();
					file.addProperty("open", Boolean.TRUE);
					file.addProperty("name", session.file().getName());
					if (session.path() != null) {
						file.addProperty("path", session.path().getAbsolutePath());
					}
					ret.add("file", file);
				}
				JsonArray activity = new JsonArray();
				for (String line : ActivityLog.recent(5)) {
					activity.add(line);
				}
				ret.add("activity_tail", activity);
				return ret;
			}
		};
	}

	public static McpTool windows(final McpSession session) {
		return new AbstractTool("logisim_gui_windows",
				"Lists the Logisim windows that are open, with the file each one came from, whether it has unsaved "
						+ "changes, which circuit is on screen, and the current selection. Pass one of the returned "
						+ "titles or paths to logisim_gui_focus to bring that window to the front.",
				Schemas.object()) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireLive(name());
				JsonObject ret = new JsonObject();
				JsonArray windows = GuiBridge.describeWindows(session);
				ret.addProperty("count", windows.size());
				ret.add("windows", windows);
				return ret;
			}
		};
	}

	public static McpTool focus(final McpSession session) {
		return new AbstractTool("logisim_gui_focus",
				"Brings a window to the front, and switches it to a circuit, so the user is looking at what the "
						+ "agent is about to change. Use it before any burst of edits: watching a circuit change on "
						+ "screen is the whole point of running the server inside the window. Identify the window by a "
						+ "substring of its title or file path (as listed by logisim_gui_windows); omit it for the "
						+ "window the user is already in.",
				Schemas.object()
						.opt("window", Schemas.string("Part of a window title or file path; defaults to the window "
								+ "the user is in"))
						.opt("circuit", Schemas.string("Circuit to show; defaults to leaving it as it is"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireLive(name());
				Project project = pickProject(session, ctx.optString("window", null));
				Circuit circuit = null;
				String wanted = ctx.optString("circuit", null);
				if (wanted != null) {
					circuit = session.circuitByName(wanted);
				}
				GuiBridge.focus(project, circuit);
				JsonObject ret = new JsonObject();
				Frame frame = GuiBridge.frameFor(project);
				ret.addProperty("window", frame == null ? "" : frame.getTitle());
				if (project.getCurrentCircuit() != null) {
					ret.addProperty("circuit", project.getCurrentCircuit().getName());
				}
				return ret;
			}
		};
	}

	public static McpTool select(final McpSession session) {
		return new AbstractTool("logisim_gui_select",
				"Highlights components and wires on screen by putting them into the selection, which is how the user "
						+ "sees what a call is about to touch or has just touched. Every mutating tool does this for "
						+ "the parts it changed; call this to point at something without changing it, for example to "
						+ "show the user the two nets that shorted. Any component reference works: an id (\"c3\"), a "
						+ "label (\"A\"), a port (\"c3.1\") or a location ([150,200]).",
				Schemas.object()
						.req("components", Schemas.array(Schemas.any("a component reference"),
								"Component references to select, or an empty list to clear the selection"))
						.opt("circuit", Schemas.string("Circuit the references belong to; defaults to the current one"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireLive(name());
				Project project = session.project();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				JsonArray raw = ctx.requireArray("components");
				Collection<Component> chosen = new ArrayList<Component>();
				for (JsonElement element : raw) {
					chosen.add(session.requireComponent(circuit, element));
				}
				GuiBridge.select(project, chosen);
				JsonObject ret = new JsonObject();
				ret.addProperty("selection_count", chosen.size());
				JsonArray ids = new JsonArray();
				for (Component comp : chosen) {
					ids.add(session.idOf(comp));
				}
				ret.add("selection", ids);
				return ret;
			}
		};
	}

	public static McpTool deselect(final McpSession session) {
		return new AbstractTool("logisim_gui_deselect",
				"Clears the selection, so the user sees nothing highlighted any more. Useful before handing control "
						+ "back, or after an inspection that was only meant to point at something.",
				Schemas.object()) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireLive(name());
				GuiBridge.deselect(session.project());
				return Json.object("selection_count", Integer.valueOf(0));
			}
		};
	}

	public static McpTool readOnly(final McpSession session) {
		return new AbstractTool("logisim_gui_read_only",
				"Turns read-only mode on or off for this session. The user owns this switch: while it is on, every "
						+ "tool that would change the project is refused with an error saying so, and only inspection "
						+ "and simulation work. The MCP window in Logisim flips the same switch, so an agent that finds "
						+ "editing refused should tell the user rather than fight it.",
				Schemas.object().req("read_only", Schemas.bool("True to forbid changes, false to allow them"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireLive(name());
				boolean value = ctx.optBool("read_only", true);
				session.setReadOnly(value);
				ActivityLog.record("read-only mode " + (value ? "on" : "off"));
				return Json.object("read_only", Boolean.valueOf(session.isReadOnly()));
			}
		};
	}

	public static McpTool pause(final McpSession session) {
		return new AbstractTool("logisim_gui_pause",
				"Pauses or resumes the agent. While paused, every tool call is refused with a message that says the "
						+ "user paused it, so the user can inspect or edit the circuit without the agent moving "
						+ "underneath them. Like read-only mode this belongs to the user; it is exposed as a tool so "
						+ "the mechanism is usable before the MCP window exists.",
				Schemas.object().req("paused", Schemas.bool("True to stop the agent, false to let it work again"))
						.opt("reason", Schemas.string("Why, shown in the reply to the agent and in the window"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireLive(name());
				boolean value = ctx.optBool("paused", true);
				if (!value && session.isPausedByUser()) {
					throw new McpException("the user paused this session from the Logisim window, so only they can "
							+ "resume it; ask them to press resume");
				}
				session.setPaused(value, ctx.optString("reason", null));
				ActivityLog.record("agent " + (value ? "paused" : "resumed"));
				JsonObject ret = new JsonObject();
				ret.addProperty("paused", Boolean.valueOf(session.isPaused()));
				if (session.pausedReason() != null) {
					ret.addProperty("reason", session.pausedReason());
				}
				return ret;
			}
		};
	}

	/** The window whose title or path matches, or the one the user is in. */
	private static Project pickProject(McpSession session, String wanted) throws McpException {
		List<Frame> frames = GuiBridge.frames();
		if (frames.isEmpty()) {
			throw new McpException("no Logisim window is open");
		}
		if (wanted == null || wanted.isEmpty()) {
			Frame focused = GuiBridge.focusedFrame();
			if (focused == null) {
				throw new McpException("no Logisim window is open");
			}
			return focused.getProject();
		}
		for (Frame frame : frames) {
			String title = frame.getTitle() == null ? "" : frame.getTitle();
			Project project = frame.getProject();
			String path = project.getLogisimFile() == null || project.getLogisimFile().getLoader() == null
					|| project.getLogisimFile().getLoader().getMainFile() == null ? ""
							: project.getLogisimFile().getLoader().getMainFile().getAbsolutePath();
			if (title.toLowerCase().contains(wanted.toLowerCase()) || path.toLowerCase().contains(wanted.toLowerCase())) {
				return project;
			}
		}
		throw new McpException("no open window matches '" + wanted + "'; call logisim_gui_windows to see them");
	}
}
