/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.gui.appear.AppearanceCanvas;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.gui.main.Selection;
import com.cburch.logisim.gui.main.SelectionBridge;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectActions;
import com.cburch.logisim.proj.Projects;
import com.cburch.draw.model.CanvasObject;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Everything this layer needs from the running GUI, in one place.
 *
 * <p>
 * Nothing here is called in headless mode. When the server runs inside a Logisim
 * window these methods are what make the user part of the loop: they find the window
 * the user is looking at, bring a circuit or a window to the front, put what the agent
 * just changed into the selection so it is visibly highlighted, and open and save
 * files through Logisim's own actions so the standard menus stay in step.
 * </p>
 *
 * <p>
 * All of it goes through public Logisim API; the only assumption is what a user would
 * expect anyway, namely that a project is a window ({@code Frame}) and that the
 * circuit on screen is the project's current circuit.
 * </p>
 */
public final class GuiBridge {
	private GuiBridge() {
	}

	/** Every open project window, in no particular order. */
	public static List<Frame> frames() {
		List<Frame> ret = new ArrayList<Frame>();
		java.awt.Frame[] all = java.awt.Frame.getFrames();
		if (all == null) {
			return ret;
		}
		for (java.awt.Frame candidate : all) {
			if (candidate instanceof Frame && ((Frame) candidate).getProject() != null) {
				ret.add((Frame) candidate);
			}
		}
		return ret;
	}

	/** The window the user is working in: the focused one, else the first there is. */
	public static Frame focusedFrame() {
		List<Frame> all = frames();
		if (all.isEmpty()) {
			return null;
		}
		Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
		while (focused != null) {
			if (focused instanceof Frame && ((Frame) focused).getProject() != null) {
				return (Frame) focused;
			}
			focused = focused.getOwner();
		}
		for (Frame frame : all) {
			if (frame.isActive()) {
				return frame;
			}
		}
		return all.get(0);
	}

	public static Project project() {
		Frame frame = focusedFrame();
		return frame == null ? null : frame.getProject();
	}

	public static Frame frameFor(Project project) {
		if (project == null) {
			return null;
		}
		for (Frame frame : frames()) {
			if (frame.getProject() == project) {
				return frame;
			}
		}
		return null;
	}

	/**
	 * What the user sees, described for an agent: one entry per window, with the file
	 * it came from, whether it has unsaved changes, which circuit is on screen, which
	 * view that window is showing, and which window has the focus.
	 */
	public static JsonArray describeWindows(McpSession session) {
		Frame focused = focusedFrame();
		JsonArray ret = new JsonArray();
		for (Frame frame : frames()) {
			Project project = frame.getProject();
			JsonObject window = new JsonObject();
			window.addProperty("title", frame.getTitle());
			window.addProperty("focused", Boolean.valueOf(frame == focused));
			File source = project.getLogisimFile() == null ? null
					: project.getLogisimFile().getLoader().getMainFile();
			if (source != null) {
				window.addProperty("path", source.getAbsolutePath());
			}
			window.addProperty("dirty", Boolean.valueOf(project.isFileDirty()));
			window.addProperty("project_name", project.getLogisimFile() == null ? "" : project.getLogisimFile().getName());
			Circuit current = project.getCurrentCircuit();
			if (current != null) {
				window.addProperty("current_circuit", current.getName());
			}
			window.addProperty("view", view(frame));
			boolean appearanceView = Frame.EDIT_APPEARANCE.equals(frame.getEditorView());
			List<String> selected = appearanceView ? appearanceSelection(project) : selection(session, project);
			window.addProperty("selection_of", appearanceView ? "appearance" : "circuit");
			window.addProperty("selection_count", selected.size());
			if (selected.size() > 0) {
				window.add("selection", toJson(selected));
			}
			ret.add(window);
		}
		return ret;
	}

	/**
	 * Which of the two views a window is showing, named the way the tool that switches
	 * it names them: the circuit's schematic, or its appearance (its symbol). Together
	 * with {@code current_circuit} this says what the user is looking at, which is what
	 * {@link #showAppearance} changes and what an agent has to be able to check.
	 */
	public static String view(Frame frame) {
		if (frame == null) {
			return "unknown";
		}
		String view = frame.getEditorView();
		if (Frame.EDIT_APPEARANCE.equals(view)) {
			return "appearance (the symbol of this circuit, as Project | Edit Circuit Appearance shows it)";
		}
		return Frame.EDIT_LAYOUT.equals(view) ? "circuit (the schematic)" : String.valueOf(view);
	}

	/** Brings a window and (if given) one of its circuits to the front. */
	public static void focus(Project project, Circuit circuit) {
		Frame frame = frameFor(project);
		if (frame != null) {
			if (!frame.isVisible()) {
				frame.setVisible(true);
			}
			frame.toFront();
			frame.requestFocus();
		}
		if (circuit != null && project.getCurrentCircuit() != circuit) {
			project.setCurrentCircuit(circuit);
		}
		if (frame != null) {
			Canvas canvas = frame.getCanvas();
			if (canvas != null) {
				canvas.repaint();
			}
		}
	}

	/** Replaces the selection with these components, or clears it when the list is empty. */
	public static void select(Project project, Collection<Component> components) throws McpException {
		Frame frame = frameFor(project);
		if (frame == null) {
			return;
		}
		Canvas canvas = frame.getCanvas();
		if (canvas == null || canvas.getSelection() == null) {
			return;
		}
		Selection selection = canvas.getSelection();
		if (SelectionBridge.hasFloatingComponents(selection)) {
			throw new McpException("the user is in the middle of placing components (a paste or a drag that has not "
					+ "been dropped yet), so the selection is left alone; wait a moment and try again");
		}
		SelectionBridge.clearSelection(selection);
		if (components != null && !components.isEmpty()) {
			selection.addAll(components);
		}
		canvas.repaint();
	}

	public static void deselect(Project project) throws McpException {
		select(project, null);
	}

	/**
	 * Shows the user a circuit's <i>appearance</i> - the symbol it is drawn as inside another
	 * circuit - because that is the one thing an agent can change that the ordinary canvas does
	 * not show at all: Logisim draws a symbol only in its appearance view (Project | Edit
	 * Circuit Appearance), so leaving the window on the canvas after such an edit would hide
	 * the work from the user completely.
	 *
	 * <p>
	 * The circuit is made current first, because switching circuit puts the window back on the
	 * canvas view; then the view is switched, and whatever the call changed is selected so it
	 * stands out. The window is not raised: the user may be looking at another project on
	 * purpose, and {@code logisim_gui_focus} is there for when they should be interrupted.
	 * </p>
	 *
	 * @return a line for the tool result, or null when there is no window to show it in
	 */
	public static String showAppearance(Project project, Circuit circuit, List<CanvasObject> touched) {
		Frame frame = frameFor(project);
		if (frame == null || circuit == null) {
			return null;
		}
		if (project.getCurrentCircuit() != circuit) {
			project.setCurrentCircuit(circuit);
		}
		frame.setEditorView(Frame.EDIT_APPEARANCE);
		AppearanceCanvas canvas = appearanceCanvas(frame.getContentPane());
		if (canvas != null) {
			com.cburch.draw.canvas.Selection selection = canvas.getSelection();
			if (selection != null) {
				selection.clearSelected();
				List<CanvasObject> present = new ArrayList<CanvasObject>();
				List<CanvasObject> now = circuit.getAppearance().getObjectsFromBottom();
				if (touched != null) {
					for (CanvasObject object : touched) {
						for (CanvasObject current : now) {
							if (current == object) {
								present.add(object);
								break;
							}
						}
					}
				}
				if (!present.isEmpty()) {
					selection.setSelected(present, true);
				}
			}
			canvas.repaint();
		}
		return "The window is now showing the appearance (the symbol) of circuit '" + circuit.getName()
				+ "', with what changed selected; Project | Edit Circuit Appearance or the canvas button "
				+ "returns to the circuit.";
	}

	/**
	 * The appearance canvas of a window, found in its component tree: the view is built the
	 * first time it is shown and Logisim never exposes it, so this walks the window looking for
	 * the canvas class instead of reaching into a field.
	 */
	private static AppearanceCanvas appearanceCanvas(java.awt.Container container) {
		if (container == null) {
			return null;
		}
		java.awt.Component[] children = container.getComponents();
		for (java.awt.Component child : children) {
			if (child instanceof AppearanceCanvas) {
				return (AppearanceCanvas) child;
			}
		}
		for (java.awt.Component child : children) {
			if (child instanceof java.awt.Container) {
				AppearanceCanvas found = appearanceCanvas((java.awt.Container) child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/**
	 * The ids of the currently selected components, for reporting.
	 *
	 * <p>
	 * This is the selection of the <i>circuit</i> canvas, which is not necessarily the one the
	 * user is looking at: {@link #view} says which view a window is showing, and
	 * {@link #appearanceSelection} describes the other one.
	 * </p>
	 */
	public static List<String> selection(McpSession session, Project project) {
		List<String> ret = new ArrayList<String>();
		Frame frame = frameFor(project);
		if (frame == null || frame.getCanvas() == null || frame.getCanvas().getSelection() == null) {
			return ret;
		}
		Collection<Component> components = frame.getCanvas().getSelection().getComponents();
		if (components == null) {
			return ret;
		}
		for (Component comp : components) {
			try {
				ret.add(session.idOf(comp) + ":" + comp.getFactory().getName());
			} catch (Throwable t) {
				ret.add(comp.getFactory().getName());
			}
		}
		return ret;
	}

	/**
	 * The objects the appearance canvas has selected, named the way the appearance tools name
	 * them ({@code "a6:rect"}). Reported instead of the component selection when the window is
	 * showing an appearance, because that is the one the user can see - and a call that edits an
	 * appearance selects what it changed.
	 */
	public static List<String> appearanceSelection(Project project) {
		List<String> ret = new ArrayList<String>();
		Frame frame = frameFor(project);
		Circuit circuit = project == null ? null : project.getCurrentCircuit();
		if (frame == null || circuit == null) {
			return ret;
		}
		AppearanceCanvas canvas = appearanceCanvas(frame.getContentPane());
		if (canvas == null || canvas.getSelection() == null) {
			return ret;
		}
		java.util.Set<CanvasObject> objects = canvas.getSelection().getSelected();
		if (objects == null) {
			return ret;
		}
		for (CanvasObject object : objects) {
			String name = Appearance.nameOf(circuit, object);
			ret.add((name == null ? "?" : name) + ":" + Appearance.kindOf(object));
		}
		return ret;
	}

	/**
	 * Opens a file in a new Logisim window, the way File | Open would. A file that is
	 * already open is brought to the front instead of being reopened, which both avoids
	 * the "lose your changes?" dialog and cannot lose the user's work.
	 */
	public static Project openFile(File source) throws McpException {
		Project already = Projects.findProjectFor(source);
		if (already != null) {
			focus(already, null);
			return already;
		}
		Project opened = ProjectActions.doOpen(null, null, source);
		if (opened == null) {
			throw new McpException("Logisim could not open '" + source.getAbsolutePath() + "'");
		}
		focus(opened, null);
		return opened;
	}

	/** Saves through the project's own loader, which is what File | Save does. */
	public static boolean saveProject(Project project) {
		return ProjectActions.doSave(project);
	}

	/** True when the project is a subcircuit instance whose pins have labels. */
	public static boolean isSubcircuit(Component comp) {
		return comp != null && comp.getFactory() instanceof SubcircuitFactory;
	}

	private static JsonArray toJson(List<String> values) {
		JsonArray ret = new JsonArray();
		for (String value : values) {
			ret.add(new com.google.gson.JsonPrimitive(value));
		}
		return ret;
	}
}
