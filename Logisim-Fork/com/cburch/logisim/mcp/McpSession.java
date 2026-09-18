/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import com.cburch.logisim.util.StringGetter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
/**
 * Everything the MCP tools operate on: the currently open Logisim file, the
 * {@link Project} that gives us undoable actions, the per-circuit simulation
 * state, and a session scoped naming scheme for components and wires.
 *
 * <p>
 * Design notes, in order of importance:
 * </p>
 * <ol>
 * <li>Nothing in this class reaches into Logisim internals: it only uses public
 * API, so no existing Logisim behaviour can change because of the MCP layer.</li>
 * <li>Disk access goes through Logisim's own reader and writer
 * ({@link Loader#openLogisimFile(File)} and {@link Loader#save}), never through
 * hand written XML handling.</li>
 * <li>The project's {@code Simulator} is deliberately put to sleep
 * ({@code setIsRunning(false)}) so that propagation only ever happens when a
 * tool asks for it. That makes every tool call deterministic and reproducible.</li>
 * </ol>
 */
public class McpSession implements Closeable {
	/** Pseudo library name under which subcircuits are offered to the agent. */
	public static final String CIRCUIT_LIBRARY = "Circuits";

	/** One entry of the component catalogue. */
	public static class CatalogEntry {
		public final String library;
		public final String name;
		public final String display;
		public final ComponentFactory factory;
		public final boolean subcircuit;

		CatalogEntry(String library, String name, String display, ComponentFactory factory, boolean subcircuit) {
			this.library = library;
			this.name = name;
			this.display = display;
			this.factory = factory;
			this.subcircuit = subcircuit;
		}
	}

	private static final Comparator<Instance> PIN_ORDER = new Comparator<Instance>() {
		@Override
		public int compare(Instance a, Instance b) {
			Location la = a.getLocation();
			Location lb = b.getLocation();
			if (la.getY() != lb.getY()) {
				return la.getY() - lb.getY();
			}
			if (la.getX() != lb.getX()) {
				return la.getX() - lb.getX();
			}
			return System.identityHashCode(a) - System.identityHashCode(b);
		}
	};

	private Loader loader;
	private LogisimFile file;
	private Project project;
	private File path;

	private final Map<Component, String> ids = new IdentityHashMap<Component, String>();
	private final Map<String, Component> byId = new LinkedHashMap<String, Component>();
	private int nextId = 1;

	private List<CatalogEntry> catalogCache;
	private final Map<ComponentFactory, String> libraryOfFactory = new HashMap<ComponentFactory, String>();

	/** Set when Logisim's own wire repair produced a wire that had to be repaired. */
	private JsonArray lastRepair;

	/** Set when a file had to be opened by rewriting non-axis-aligned wires. */
	private JsonArray lastRecovery;

	/**
	 * True when this session drives the windows of a running Logisim instead of a
	 * project of its own. Then the project is adopted from the window the user is
	 * looking at, every touch of it happens on the event dispatch thread, and what a
	 * call changes is put into the selection so the user can see it.
	 */
	private boolean live;

	/** Components and wires the current call changed, to be highlighted when it ends. */
	private final List<Component> touched = new ArrayList<Component>();

	/**
	 * Set by the user (through the MCP window, or the tools meanwhile) to restrain the agent.
	 * A pause set from the window can only be released there: the agent must not be able to
	 * remove the user's brake.
	 */
	private boolean readOnly;
	private boolean paused;
	private boolean pausedByUser;
	private String pausedReason;

	/** Matches the wire elements Logisim's writer emits, whatever the coordinates are. */
	private static final java.util.regex.Pattern WIRE_ELEMENT = java.util.regex.Pattern
			.compile("<wire\\s+from=\"\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)\"\\s+to=\"\\(\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)\"");

	// ------------------------------------------------------------------ live mode

	public boolean isLive() {
		return live;
	}

	public void setLive(boolean value) {
		this.live = value;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly(boolean value) {
		this.readOnly = value;
	}

	public boolean isPaused() {
		return paused;
	}

	/** True when the pause was set from the Logisim window rather than by a tool call. */
	public boolean isPausedByUser() {
		return pausedByUser;
	}

	public String pausedReason() {
		return pausedReason;
	}

	public void setPaused(boolean value, String reason) {
		setPaused(value, reason, false);
	}

	public void setPaused(boolean value, String reason, boolean byUser) {
		this.paused = value;
		this.pausedReason = value ? reason : null;
		this.pausedByUser = value && byUser;
	}

	/**
	 * In live mode, adopts the project of the window the user is looking at, so that a
	 * tool call always works on what is on screen - including a file the user opened in
	 * the meantime. Called once per tool call, on the event dispatch thread.
	 */
	public void refresh() {
		if (!live) {
			return;
		}
		Project current = GuiBridge.project();
		if (current == project) {
			return;
		}
		project = current;
		file = current == null ? null : current.getLogisimFile();
		loader = file == null ? null : file.getLoader();
		path = loader == null ? null : loader.getMainFile();
		// ids belong to the components of one project, so they start over with a new one
		ids.clear();
		byId.clear();
		nextId = 1;
	}

	/** Remembers something a tool changed, so the user can see it highlighted. */
	public void noteTouched(Component comp) {
		if (comp != null && live) {
			touched.add(comp);
		}
	}

	public void noteTouched(java.util.Collection<? extends Component> comps) {
		if (comps != null && live) {
			for (Component comp : comps) {
				noteTouched(comp);
			}
		}
	}

	/**
	 * Ends a tool call: in live mode the components the call touched become the
	 * selection, which is how the user sees what just changed without reading a single
	 * line of JSON. The selection is replaced rather than added to, so the agent never
	 * fights the user for it.
	 */
	public void finishCall() {
		if (!live) {
			return;
		}
		try {
			if (!touched.isEmpty()) {
				GuiBridge.select(project, new ArrayList<Component>(touched));
			}
		} catch (Throwable t) {
			// highlighting is a courtesy: never fail a call over it
		} finally {
			touched.clear();
		}
	}

	/** Refuses a tool that only makes sense in headless mode. */
	public void requireHeadless(String tool) {
		if (live) {
			throw new McpException(tool + " is not available while the MCP server runs inside the Logisim window: "
					+ "the user owns this project. Use logisim_gui_status to see what is on screen, or run the server "
					+ "in headless mode (java -jar Logisim.jar -mcp) to work on files of your own.");
		}
	}

	/** Refuses a tool that only makes sense with a window. */
	public void requireLive(String tool) {
		if (!live) {
			throw new McpException(tool + " needs a Logisim window; start the server with --with-gui, or use the "
					+ "headless tools on a project of your own.");
		}
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	public boolean isOpen() {
		return file != null && project != null;
	}

	public void requireOpen() {
		refresh();
		if (!isOpen()) {
			throw new McpException(live
					? "no Logisim window with a project is open; the user has to open one"
					: "no file is open; call logisim_file_new or logisim_file_open first");
		}
	}

	/** Creates a new, empty in memory file. Nothing is written to disk. */
	public void newFile() {
		Loader newLoader = new Loader(null);
		LogisimFile loaded = null;
		InputStream template = null;
		try {
			template = AppPreferences.getTemplate().createStream();
			loaded = newLoader.openLogisimFile(template);
		} catch (Throwable t) {
			loaded = null;
		} finally {
			closeQuietly(template);
		}
		if (loaded == null) {
			// Fall back to a hand built file. The plain template normally provides
			// the component libraries, so make sure they are present either way.
			loaded = LogisimFile.createNew(newLoader);
			for (Library lib : newLoader.getBuiltin().getLibraries()) {
				if (!loaded.getLibraries().contains(lib)) {
					loaded.addLibrary(lib);
				}
			}
		}
		install(newLoader, loaded, null);
	}

	/** Opens a Logisim file with Logisim's own reader. */
	public void open(File source) {
		if (source == null) {
			throw new McpException("a file path is required");
		}
		if (!source.isFile()) {
			throw new McpException("file not found: " + source.getAbsolutePath());
		}
		requireOpenable(source);
		Loader newLoader = new Loader(null);
		LogisimFile loaded;
		try {
			loaded = newLoader.openLogisimFile(source);
		} catch (Throwable t) {
			throw new McpException("Logisim could not open '" + source.getAbsolutePath() + "': " + message(t), t);
		}
		if (loaded == null) {
			throw new McpException("Logisim could not open '" + source.getAbsolutePath() + "'");
		}
		install(newLoader, loaded, source);
	}

	/**
	 * Refuses to let Logisim load a file that contains a wire whose ends are not axis
	 * aligned. Logisim reads a file inside a transaction, and the wire repair at the end
	 * of that transaction walks every point of every wire; a wire that cannot be walked
	 * to its end makes that walk run forever, so loading such a file would hang and then
	 * die with 'Java heap space' rather than fail. Since that is the one failure a caller
	 * cannot escape, the file is scanned as text first: this is read only, changes
	 * nothing, and turns the hang into an explanation of exactly which line to fix.
	 */
	public void requireOpenable(File source) {
		List<String> broken = scanForSkewedWires(source);
		if (broken.isEmpty()) {
			return;
		}
		StringBuilder buf = new StringBuilder();
		for (int i = 0; i < broken.size() && i < 3; i++) {
			if (i > 0) {
				buf.append("; ");
			}
			buf.append(broken.get(i));
		}
		if (broken.size() > 3) {
			buf.append("; ...");
		}
		throw new McpException("refusing to open '" + source.getName() + "': it contains " + broken.size()
				+ " wire(s) whose ends are not axis aligned (" + buf.toString() + "). Logisim cannot open such a "
				+ "file: loading runs its wire repair, which walks every point of every wire, and such a wire can "
				+ "never be walked to its end, so Logisim would spin until the Java heap is exhausted (this is the "
				+ "look of a hang followed by 'Java heap space'). Nothing was changed. Call logisim_file_recover "
				+ "instead, which opens the file with just those wires put back as axis aligned segments, and then "
				+ "save it with logisim_file_save_as to make the repair permanent; or replace each such wire in a "
				+ "text editor with the L shaped route given above, which preserves the connections and leaves every "
				+ "other line untouched.");
	}

	/**
	 * Opens a file that {@link #requireOpenable} would have refused, by rewriting the
	 * damaged wire elements in memory on the way in and handing the result to Logisim's
	 * own reader. It never writes to disk: the caller decides what to do next, normally
	 * {@code logisim_file_save_as}, so the file that ends up saved is written entirely by
	 * Logisim. Each damaged wire is replaced by its horizontal and vertical parts, which
	 * keeps both ends connected.
	 */
	public void recover(File source) {
		if (source == null) {
			throw new McpException("a file path is required");
		}
		if (!source.isFile()) {
			throw new McpException("file not found: " + source.getAbsolutePath());
		}
		String text;
		try {
			text = readText(source);
		} catch (Exception e) {
			throw new McpException("cannot read '" + source.getAbsolutePath() + "': " + message(e), e);
		}

		JsonArray rewrites = new JsonArray();
		java.util.regex.Matcher m = WIRE_ELEMENT.matcher(text);
		StringBuffer out = new StringBuffer(text.length() + 64);
		while (m.find()) {
			int x0 = Integer.parseInt(m.group(1));
			int y0 = Integer.parseInt(m.group(2));
			int x1 = Integer.parseInt(m.group(3));
			int y1 = Integer.parseInt(m.group(4));
			if (x0 == x1 || y0 == y1) {
				continue;
			}
			String horizontal = "<wire from=\"(" + x0 + "," + y0 + ")\" to=\"(" + x1 + "," + y0 + ")\"/>";
			String vertical = "<wire from=\"(" + x1 + "," + y0 + ")\" to=\"(" + x1 + "," + y1 + ")\"/>";
			m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(horizontal + "\n" + vertical));
			JsonObject item = new JsonObject();
			item.addProperty("was", "(" + x0 + "," + y0 + ") -> (" + x1 + "," + y1 + ")");
			item.addProperty("now", "(" + x0 + "," + y0 + ") -> (" + x1 + "," + y0 + ") and (" + x1 + "," + y0
					+ ") -> (" + x1 + "," + y1 + ")");
			rewrites.add(item);
		}
		m.appendTail(out);
		this.lastRecovery = rewrites;

		Loader newLoader = new Loader(null);
		LogisimFile loaded;
		try {
			loaded = newLoader.openLogisimFile(new java.io.ByteArrayInputStream(out.toString().getBytes("UTF-8")));
		} catch (Exception e) {
			throw new McpException("Logisim could not open '" + source.getAbsolutePath() + "' even after "
					+ "rewriting its non-axis-aligned wires: " + message(e), e);
		}
		if (loaded == null) {
			throw new McpException("Logisim could not open '" + source.getAbsolutePath() + "' even after "
					+ "rewriting its non-axis-aligned wires");
		}
		install(newLoader, loaded, null);
		for (Circuit circuit : loaded.getCircuits()) {
			List<Wire> stillBroken = skewedWires(circuit);
			if (!stillBroken.isEmpty()) {
				repairSkewedWires(circuit, false);
			}
		}
	}

	/** What the most recent {@link #recover} changed, or null if it has not been used. */
	public JsonArray lastRecoveryReport() {
		return lastRecovery;
	}

	private static String readText(File source) throws Exception {
		java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
				new java.io.FileInputStream(source), "UTF-8"));
		try {
			StringBuilder buf = new StringBuilder();
			char[] chunk = new char[8192];
			int got;
			while ((got = in.read(chunk)) > 0) {
				buf.append(chunk, 0, got);
			}
			return buf.toString();
		} finally {
			closeQuietly(in);
		}
	}

	/** Line by line scan for wires that are not axis aligned; read only. */
	public static List<String> scanForSkewedWires(File source) {
		List<String> ret = new ArrayList<String>();
		java.io.BufferedReader in = null;
		try {
			in = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(source),
					"UTF-8"));
			String line;
			int number = 0;
			while ((line = in.readLine()) != null) {
				number++;
				java.util.regex.Matcher m = WIRE_ELEMENT.matcher(line);
				while (m.find()) {
					int x0 = Integer.parseInt(m.group(1));
					int y0 = Integer.parseInt(m.group(2));
					int x1 = Integer.parseInt(m.group(3));
					int y1 = Integer.parseInt(m.group(4));
					if (x0 != x1 && y0 != y1) {
						ret.add("line " + number + ": (" + x0 + "," + y0 + ") -> (" + x1 + "," + y1
								+ ") should become (" + x0 + "," + y0 + ") -> (" + x1 + "," + y0 + ") -> (" + x1 + ","
								+ y1 + ")");
					}
				}
			}
		} catch (Exception e) {
			// if the file cannot be read, let Logisim itself report the problem
			return new ArrayList<String>();
		} finally {
			closeQuietly(in);
		}
		return ret;
	}

	private void install(Loader newLoader, LogisimFile loaded, File source) {
		close();
		Circuit main = loaded.getMainCircuit();
		if (main == null) {
			if (loaded.getCircuits().isEmpty()) {
				main = new Circuit("main");
				loaded.addCircuit(main);
			} else {
				main = loaded.getCircuits().get(0);
				loaded.setMainCircuit(main);
			}
		}

		this.loader = newLoader;
		this.file = loaded;
		this.path = source;
		this.ids.clear();
		this.byId.clear();
		this.nextId = 1;
		invalidateCatalog();

		this.project = new Project(loaded);
		// Keep the simulation under our control: no background propagation, no
		// clock ticking. Tools ask for propagation explicitly.
		this.project.getSimulator().setIsRunning(false);
		this.project.setCurrentCircuit(main);
		this.project.setFileAsClean();
	}

	/** Saves through Logisim's own writer; the MCP layer never writes XML itself. */
	public void save(File destination) {
		requireOpen();
		if (destination == null) {
			throw new McpException("a destination file path is required");
		}
		File parent = destination.getAbsoluteFile().getParentFile();
		if (parent != null && !parent.isDirectory()) {
			throw new McpException("directory does not exist: " + parent.getAbsolutePath());
		}
		// Whatever happens, never write a wire that would make the file impossible to
		// open again: Logisim loads a file inside a transaction, and the wire repair
		// that follows would walk such a wire forever.
		this.lastRepair = null;
		healSkewedWires();
		boolean ok;
		try {
			ok = loader.save(file, destination);
		} catch (Throwable t) {
			// In headless mode Logisim's error reporting would have tried to open a
			// dialog, which is not possible; surface the failure to the agent instead.
			throw new McpException("Logisim could not write '" + destination.getAbsolutePath() + "': " + message(t),
					t);
		}
		if (!ok) {
			throw new McpException("Logisim refused to save '" + destination.getAbsolutePath()
					+ "' (the file may be in use, or a library reference may be circular)");
		}
		this.path = destination;
		project.setFileAsClean();
	}

	@Override
	public void close() {
		if (project != null) {
			try {
				project.getSimulator().setIsRunning(false);
				project.getSimulator().shutDown();
			} catch (Throwable t) {
				// shutting down is best effort only
			}
		}
		loader = null;
		file = null;
		project = null;
		path = null;
		ids.clear();
		byId.clear();
		nextId = 1;
		invalidateCatalog();
	}

	public LogisimFile file() {
		requireOpen();
		return file;
	}

	public Loader loader() {
		requireOpen();
		return loader;
	}

	public Project project() {
		requireOpen();
		return project;
	}

	public File path() {
		return path;
	}

	// ------------------------------------------------------------------
	// circuits
	// ------------------------------------------------------------------

	public Circuit circuit() {
		requireOpen();
		Circuit ret = project.getCurrentCircuit();
		if (ret == null) {
			ret = file.getMainCircuit();
			if (ret == null) {
				throw new McpException("the file has no circuits");
			}
			project.setCurrentCircuit(ret);
		}
		return ret;
	}

	public void setCircuit(Circuit circuit) {
		requireOpen();
		if (circuit == null) {
			throw new McpException("circuit is required");
		}
		if (!file.contains(circuit)) {
			throw new McpException("circuit '" + circuit.getName() + "' is not part of the open file");
		}
		project.setCurrentCircuit(circuit);
	}

	/** Looks a circuit up by name, accepting the main circuit when name is empty. */
	public Circuit circuitByName(String name) {
		requireOpen();
		if (name == null || name.trim().isEmpty()) {
			return circuit();
		}
		Circuit found = file.getCircuit(name.trim());
		if (found == null) {
			throw new McpException("no circuit named '" + name + "'; the file contains: " + circuitNames());
		}
		return found;
	}

	public String circuitNames() {
		StringBuilder buf = new StringBuilder();
		for (Circuit c : file.getCircuits()) {
			if (buf.length() > 0) {
				buf.append(", ");
			}
			buf.append(c.getName());
		}
		return buf.length() == 0 ? "(none)" : buf.toString();
	}

	public CircuitState state() {
		requireOpen();
		return stateFor(circuit());
	}

	/**
	 * The simulation state of any circuit of the project. Logisim keeps one state per
	 * circuit, so values survive switching between circuits, exactly as in the GUI.
	 */
	public CircuitState stateFor(Circuit circuit) {
		requireOpen();
		if (circuit == null) {
			throw new McpException("a circuit is required");
		}
		CircuitState ret = project.getCircuitState(circuit);
		if (ret == null) {
			project.setCurrentCircuit(circuit);
			ret = project.getCircuitState();
		}
		if (ret == null) {
			throw new McpException("could not obtain a simulation state for circuit '" + circuit.getName() + "'");
		}
		return ret;
	}

	/** Forgets every stored signal value, as Logisim's "Reset Simulation" does. */
	public void resetSimulation() {
		resetSimulation(circuit());
	}

	/**
	 * Replaces the simulation state of a circuit with a fresh one. The circuit becomes
	 * the current circuit, because Logisim tracks the "active" state that way.
	 */
	public void resetSimulation(Circuit circuit) {
		requireOpen();
		Circuit target = circuit == null ? circuit() : circuit;
		project.setCircuitState(new CircuitState(project, target));
		project.getSimulator().setIsRunning(false);
	}

	// ------------------------------------------------------------------
	// catalogue
	// ------------------------------------------------------------------

	public void invalidateCatalog() {
		catalogCache = null;
		libraryOfFactory.clear();
	}

	public List<CatalogEntry> catalog() {
		requireOpen();
		if (catalogCache == null) {
			List<CatalogEntry> ret = new ArrayList<CatalogEntry>();
			java.util.HashSet<Object> seen = new java.util.HashSet<Object>();
			collectLibraries(loader.getBuiltin(), ret, seen);
			for (Library lib : file.getLibraries()) {
				collectLibraries(lib, ret, seen);
			}
			for (Circuit c : file.getCircuits()) {
				ComponentFactory factory = c.getSubcircuitFactory();
				ret.add(new CatalogEntry(CIRCUIT_LIBRARY, c.getName(), c.getName(), factory, true));
				libraryOfFactory.put(factory, CIRCUIT_LIBRARY);
			}
			catalogCache = ret;
		}
		return catalogCache;
	}

	private void collectLibraries(Library lib, List<CatalogEntry> dest, java.util.Set<Object> seen) {
		String libName = lib.getName();
		for (Tool tool : lib.getTools()) {
			if (tool instanceof AddTool) {
				ComponentFactory factory = ((AddTool) tool).getFactory();
				if (seen.add(factory)) {
					dest.add(new CatalogEntry(libName, factory.getName(), factory.getDisplayName(), factory, false));
					libraryOfFactory.put(factory, libName);
				}
			}
		}
		for (Library sub : lib.getLibraries()) {
			collectLibraries(sub, dest, seen);
		}
	}

	/** Finds a component factory by library and/or type name, forgivingly. */
	public ComponentFactory factoryFor(String library, String type) {
		if (type == null || type.trim().isEmpty()) {
			throw new McpException("a component type is required");
		}
		String wantedLib = library == null ? null : library.trim().toLowerCase();
		String wantedType = normalize(type);
		ComponentFactory prefixMatch = null;
		int prefixCount = 0;
		for (CatalogEntry entry : catalog()) {
			if (wantedLib != null && !wantedLib.isEmpty()) {
				String entryLib = entry.library == null ? "" : entry.library.toLowerCase();
				if (!entryLib.equals(wantedLib)) {
					continue;
				}
			}
			String name = normalize(entry.name);
			String display = normalize(entry.display);
			if (name.equals(wantedType) || display.equals(wantedType)) {
				return entry.factory;
			}
			if (name.startsWith(wantedType) || display.startsWith(wantedType)) {
				prefixMatch = entry.factory;
				prefixCount++;
			}
		}
		if (prefixCount == 1) {
			return prefixMatch;
		}
		String suffix = (wantedLib == null || wantedLib.isEmpty()) ? "" : " in library '" + library + "'";
		throw new McpException("unknown component type '" + type + "'" + suffix
				+ "; use logisim_component_catalog to list the available components");
	}

	private static String normalize(String text) {
		StringBuilder buf = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = Character.toLowerCase(text.charAt(i));
			if (Character.isLetterOrDigit(c)) {
				buf.append(c);
			}
		}
		return buf.toString();
	}

	public String libraryOf(Component comp) {
		if (comp instanceof Wire) {
			return "Wiring";
		}
		String cached = libraryOfFactory.get(comp.getFactory());
		if (cached != null) {
			return cached;
		}
		catalog();
		String ret = libraryOfFactory.get(comp.getFactory());
		if (ret != null) {
			return ret;
		}
		if (comp.getFactory() instanceof Pin) {
			return "Wiring";
		}
		return null;
	}

	// ------------------------------------------------------------------
	// component identity
	// ------------------------------------------------------------------

	public String idOf(Component comp) {
		String existing = ids.get(comp);
		if (existing != null) {
			return existing;
		}
		String created = "c" + (nextId++);
		ids.put(comp, created);
		byId.put(created, comp);
		return created;
	}

	/**
	 * Moves an id from one component to another, so that the id an agent already
	 * knows keeps referring to the same logical component after a replacement such as
	 * a move.
	 */
	/**
	 * Wires whose two ends differ in both coordinates. Logisim should never contain
	 * one, but its own wire repair can synthesise them, and it cannot iterate such a
	 * wire: {@code WireIterator} only stops when its diagonal walk lands exactly on the
	 * far end, so iterating one never terminates. Because {@code WireRepair} iterates
	 * every point of every wire on every edit, a single such wire makes each later edit
	 * spin until the Java heap is exhausted.
	 *
	 * <p>
	 * Only the two endpoints are inspected, so this check is safe (and cheap) to run
	 * even on a circuit that already contains a broken wire.
	 * </p>
	 */
	public List<Wire> skewedWires(Circuit circuit) {		List<Wire> ret = new ArrayList<Wire>();
		for (Wire wire : circuit.getWires()) {
			Location a = wire.getEnd0();
			Location b = wire.getEnd1();
			if (a.getX() != b.getX() && a.getY() != b.getY()) {
				ret.add(wire);
			}
		}
		return ret;
	}

	/**
	 * Refuses to try to edit a circuit that contains a non-axis-aligned wire, because
	 * the attempt would never terminate. Turning a hang into an error is the whole
	 * point: the agent can then call logisim_circuit_repair_wires.
	 */
	public void requireHealthyWires(Circuit circuit) {
		List<Wire> broken = skewedWires(circuit);
		if (broken.isEmpty()) {
			return;
		}
		StringBuilder buf = new StringBuilder();
		int shown = 0;
		for (Wire wire : broken) {
			if (shown++ == 3) {
				buf.append(", ...");
				break;
			}
			if (shown > 1) {
				buf.append(", ");
			}
			buf.append(wire.getEnd0()).append(" -> ").append(wire.getEnd1());
		}
		throw new McpException("circuit '" + circuit.getName() + "' contains " + broken.size()
				+ " wire(s) whose ends are not axis aligned (" + buf.toString() + "). Logisim cannot iterate such a "
				+ "wire, so any edit to this circuit would spin until the Java heap is exhausted. Call "
				+ "logisim_circuit_repair_wires to replace them with axis aligned segments first.");
	}

	/**
	 * Replaces every non-axis-aligned wire of a circuit with two axis aligned
	 * segments through a corner, which keeps the two ends connected. Safe to call on a
	 * circuit that already contains such a wire, because the transaction applies the
	 * removal before Logisim repairs the circuit's wires, so the broken wire is never
	 * walked.
	 */
	public JsonObject repairSkewedWires(Circuit circuit, boolean dryRun) {
		List<Wire> broken = skewedWires(circuit);
		JsonObject ret = new JsonObject();
		ret.addProperty("circuit", circuit.getName());
		ret.addProperty("broken_wires", broken.size());
		ret.addProperty("repaired", dryRun || broken.isEmpty() ? 0 : broken.size());
		if (broken.isEmpty()) {
			return ret;
		}

		java.util.Set<Location> ports = portLocations(circuit);
		CircuitMutation mutation = new CircuitMutation(circuit);
		JsonArray repairs = new JsonArray();
		int junctions = 0;
		for (Wire wire : broken) {
			Location a = wire.getEnd0();
			Location b = wire.getEnd1();
			Location horizontalFirst = Location.create(b.getX(), a.getY());
			Location verticalFirst = Location.create(a.getX(), b.getY());
			boolean horizontalOk = isClear(a, horizontalFirst, ports) && isClear(horizontalFirst, b, ports);
			boolean verticalOk = isClear(a, verticalFirst, ports) && isClear(verticalFirst, b, ports);
			Location corner = horizontalOk || !verticalOk ? horizontalFirst : verticalFirst;
			if (!horizontalOk && !verticalOk) {
				junctions++;
			}
			JsonObject item = new JsonObject();
			item.add("from", point(a));
			item.add("to", point(b));
			item.add("corner", point(corner));
			if (!horizontalOk && !verticalOk) {
				item.addProperty("warning", "the corner lands on another component's port, so the repair creates a "
						+ "junction there; check the net afterwards");
			}
			repairs.add(item);
			if (!dryRun) {
				mutation.remove(wire);
				if (!a.equals(corner)) {
					mutation.add(Wire.create(a, corner));
				}
				if (!corner.equals(b)) {
					mutation.add(Wire.create(corner, b));
				}
			}
		}

		ret.addProperty("dry_run", Boolean.valueOf(dryRun));
		if (junctions > 0) {
			ret.addProperty("corners_on_a_port", junctions);
		}
		ret.add("repairs", repairs);
		if (!dryRun) {
			commit(mutation, "repair " + broken.size() + " non-axis-aligned wire(s)");
		}
		return ret;
	}

	/**
	 * Repairs any non-axis-aligned wire in every circuit. Called after each edit and
	 * before each save: Logisim's own wire repair can introduce such a wire, and if one
	 * is ever written to disk then no Logisim process can open the file again, because
	 * loading it runs WireRepair, which walks every point of every wire.
	 */
	public JsonArray healSkewedWires() {
		JsonArray healed = new JsonArray();
		if (file == null) {
			return healed;
		}
		for (Circuit circuit : file.getCircuits()) {
			if (!skewedWires(circuit).isEmpty()) {
				JsonObject report = repairSkewedWires(circuit, false);
				healed.add(report);
				lastRepair = healed;
			}
		}
		return healed;
	}

	/** The most recent self-heal, or null if none has happened. */
	public JsonArray lastRepairReport() {
		return lastRepair;
	}

	/** True when nothing about a segment would connect two unintended nets. */
	private static boolean isClear(Location from, Location to, java.util.Set<Location> ports) {
		return firstPortOnSegment(from, to, ports) == null;
	}

	/**
	 * The first location on a segment that holds a component port, excluding the two
	 * ends (a wire is meant to end on a port). The step is 1 because a component may sit
	 * off the 10 pixel grid; the loop is bounded so that this can never run away.
	 */
	private static Location firstPortOnSegment(Location from, Location to, java.util.Set<Location> ports) {
		int dx = Integer.signum(to.getX() - from.getX());
		int dy = Integer.signum(to.getY() - from.getY());
		if (dx != 0 && dy != 0) {
			return null; // only axis aligned segments are ever checked
		}
		int x = from.getX();
		int y = from.getY();
		for (int step = 0; step < 2000; step++) {
			if (x == to.getX() && y == to.getY()) {
				return null;
			}
			if (step > 0 && ports.contains(Location.create(x, y))) {
				return Location.create(x, y);
			}
			x += dx;
			y += dy;
		}
		return null;
	}

	/** Heap usage, so that memory pressure is visible before it becomes fatal. */
	public static JsonObject memoryReport() {
		Runtime runtime = Runtime.getRuntime();
		long total = runtime.totalMemory();
		long free = runtime.freeMemory();
		long max = runtime.maxMemory();
		JsonObject ret = new JsonObject();
		ret.addProperty("heap_used_bytes", Long.valueOf(total - free));
		ret.addProperty("heap_total_bytes", Long.valueOf(total));
		ret.addProperty("heap_max_bytes", Long.valueOf(max));
		ret.addProperty("heap_used_mb", Long.valueOf((total - free) / (1024L * 1024L)));
		ret.addProperty("heap_max_mb", Long.valueOf(max / (1024L * 1024L)));
		ret.addProperty("hint", "pass -Xmx to the server if a large circuit needs more room; a "
				+ "non-axis-aligned wire makes every edit exhaust the heap (see logisim_circuit_repair_wires)");
		return ret;
	}

	/** Every location at which a non-wire component has a port. */
	public java.util.Set<Location> portLocations(Circuit circuit) {		java.util.Set<Location> ret = new java.util.HashSet<Location>();
		for (Component comp : circuit.getNonWires()) {
			for (EndData end : comp.getEnds()) {
				if (end != null) {
					ret.add(end.getLocation());
				}
			}
		}
		return ret;
	}

	public void transferId(Component from, Component to) {
		if (from == null || to == null) {
			return;
		}
		String id = ids.get(from);
		if (id != null) {
			ids.remove(from);
			ids.put(to, id);
			byId.put(id, to);
		}
	}

	/** Assigns ids to every component of a circuit, in a stable order. */
	public void indexComponents(Circuit circuit) {
		for (Component comp : circuit.getNonWires()) {
			idOf(comp);
		}
		for (Wire wire : circuit.getWires()) {
			idOf(wire);
		}
	}

	public Component requireComponent(Circuit circuit, JsonElement reference) {
		if (reference == null || reference.isJsonNull()) {
			throw new McpException("a component reference is required");
		}
		if (reference.isJsonPrimitive()) {
			String text = reference.getAsString().trim();
			Component found = findById(circuit, text);
			if (found == null) {
				found = findByLabel(circuit, text);
			}
			if (found == null) {
				Location loc = tryParseLocation(text);
				if (loc != null) {
					found = componentAt(circuit, loc);
				}
			}
			if (found == null) {
				throw unknownComponent(text, circuit);
			}
			return found;
		}
		if (reference.isJsonObject()) {
			JsonObject obj = reference.getAsJsonObject();
			String id = Json.stringOrNull(obj, "id");
			if (id != null) {
				Component found = findById(circuit, id.trim());
				if (found == null) {
					throw unknownComponent(id, circuit);
				}
				return found;
			}
			String label = Json.stringOrNull(obj, "label");
			if (label != null) {
				Component found = findByLabel(circuit, label);
				if (found == null) {
					throw new McpException("no component labelled '" + label + "' in circuit '" + circuit.getName()
							+ "'");
				}
				return found;
			}
			if (Json.isPresent(obj, "loc")) {
				Location loc = parseLocation(obj.get("loc"));
				Component found = componentAt(circuit, loc);
				if (found == null) {
					throw new McpException("no component at " + loc + " in circuit '" + circuit.getName() + "'");
				}
				return found;
			}
			throw new McpException("component reference must have an 'id', 'label' or 'loc' member");
		}
		throw new McpException("component reference must be an id, a label, or an object");
	}

	private McpException unknownComponent(String text, Circuit circuit) {
		return new McpException("no component '" + text + "' in circuit '" + circuit.getName()
				+ "'; call logisim_circuit_describe to list ids and labels");
	}

	private Component findById(Circuit circuit, String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		Component found = byId.get(id);
		if (found != null && circuit.contains(found)) {
			return found;
		}
		return null;
	}

	private Component findByLabel(Circuit circuit, String label) {
		if (label == null || label.isEmpty()) {
			return null;
		}
		for (Component comp : circuit.getNonWires()) {
			String value = comp.getAttributeSet().getValue(StdAttr.LABEL);
			if (label.equals(value)) {
				return comp;
			}
		}
		return null;
	}

	private Component componentAt(Circuit circuit, Location loc) {
		Component best = null;
		for (Component comp : circuit.getNonWires()) {
			if (comp.contains(loc)) {
				// an exact end match wins over a bounding box hit
				for (EndData end : comp.getEnds()) {
					if (end != null && end.getLocation().equals(loc)) {
						return comp;
					}
				}
				if (best == null) {
					best = comp;
				}
			}
		}
		if (best != null) {
			return best;
		}
		for (Wire wire : circuit.getWires()) {
			if (wire.contains(loc)) {
				return wire;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// pins
	// ------------------------------------------------------------------

	/** All pins of a circuit, ordered top to bottom then left to right. */
	public List<Instance> pins(Circuit circuit) {
		List<Instance> ret = new ArrayList<Instance>();
		for (Component comp : circuit.getNonWires()) {
			if (comp.getFactory() instanceof Pin) {
				Instance instance = Instance.getInstanceFor(comp);
				if (instance != null) {
					ret.add(instance);
				}
			}
		}
		java.util.Collections.sort(ret, PIN_ORDER);
		return ret;
	}

	public List<Instance> inputPins(Circuit circuit) {
		List<Instance> ret = new ArrayList<Instance>();
		for (Instance pin : pins(circuit)) {
			if (Pin.FACTORY.isInputPin(pin)) {
				ret.add(pin);
			}
		}
		return ret;
	}

	public List<Instance> outputPins(Circuit circuit) {
		List<Instance> ret = new ArrayList<Instance>();
		for (Instance pin : pins(circuit)) {
			if (!Pin.FACTORY.isInputPin(pin)) {
				ret.add(pin);
			}
		}
		return ret;
	}

	public String pinName(Instance pin) {
		String label = pin.getAttributeValue(StdAttr.LABEL);
		if (label != null && !label.trim().isEmpty()) {
			return label.trim();
		}
		return pin.getFactory().getName() + pin.getLocation().toString();
	}

	/** Finds an input pin by label, falling back to its location. */
	public Instance inputPinByName(Circuit circuit, String name) {
		List<Instance> inputs = inputPins(circuit);
		for (Instance pin : inputs) {
			if (pinName(pin).equals(name)) {
				return pin;
			}
		}
		Location loc = tryParseLocation(name);
		if (loc != null) {
			for (Instance pin : inputs) {
				if (pin.getLocation().equals(loc)) {
					return pin;
				}
			}
		}
		StringBuilder available = new StringBuilder();
		for (Instance pin : inputs) {
			if (available.length() > 0) {
				available.append(", ");
			}
			available.append(pinName(pin));
		}
		throw new McpException("no input pin named '" + name + "' in circuit '" + circuit.getName()
				+ "'; available inputs: " + (available.length() == 0 ? "(none)" : available.toString()));
	}

	/** Finds any pin (input or output) by label, falling back to its location. */
	public Instance pinByName(Circuit circuit, String name) {
		for (Instance pin : pins(circuit)) {
			if (pinName(pin).equals(name)) {
				return pin;
			}
		}
		Location loc = tryParseLocation(name);
		if (loc != null) {
			for (Instance pin : pins(circuit)) {
				if (pin.getLocation().equals(loc)) {
					return pin;
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// mutation and undo
	// ------------------------------------------------------------------

	/** Applies a mutation as a single undoable action. */
	public void commit(CircuitMutation mutation, String actionName) {
		requireOpen();
		if (mutation == null || mutation.isEmpty()) {
			return;
		}
		final String description = actionName == null ? "MCP change" : actionName;
		Action action = mutation.toAction(new StringGetter() {
			@Override
			public String get() {
				return description;
			}
		});
		project.doAction(action);
		// Logisim's own wire repair can leave behind a wire that Logisim cannot
		// iterate; anything done afterwards would spin until the heap is exhausted,
		// so heal it right away rather than let the damage spread or be saved.
		healSkewedWires();
	}

	public void undo() {
		requireOpen();
		project.undoAction();
	}

	// ------------------------------------------------------------------
	// JSON describing Logisim objects
	// ------------------------------------------------------------------

	public JsonObject describeCircuit(Circuit circuit, boolean includeWires, boolean includeAttributes) {
		indexComponents(circuit);
		JsonObject ret = new JsonObject();
		ret.addProperty("name", circuit.getName());
		ret.addProperty("is_main", Boolean.valueOf(circuit == file.getMainCircuit()));
		Bounds bounds = circuit.getBounds();
		ret.add("bounds", Json.array(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight()));
		ret.addProperty("component_count", circuit.getNonWires().size());
		ret.addProperty("wire_count", circuit.getWires().size());
		java.util.Set<com.cburch.logisim.circuit.WidthIncompatibilityData> problems = circuit
				.getWidthIncompatibilityData();
		ret.addProperty("width_mismatch_count", problems == null ? 0 : problems.size());

		JsonArray components = new JsonArray();
		List<Component> ordered = new ArrayList<Component>(circuit.getNonWires());
		java.util.Collections.sort(ordered, new Comparator<Component>() {
			@Override
			public int compare(Component a, Component b) {
				Location la = a.getLocation();
				Location lb = b.getLocation();
				if (la.getY() != lb.getY()) {
					return la.getY() - lb.getY();
				}
				if (la.getX() != lb.getX()) {
					return la.getX() - lb.getX();
				}
				return a.getFactory().getName().compareTo(b.getFactory().getName());
			}
		});
		for (Component comp : ordered) {
			components.add(describeComponent(comp, includeAttributes, true));
		}
		ret.add("components", components);

		if (includeWires) {
			JsonArray wires = new JsonArray();
			for (Wire wire : circuit.getWires()) {
				JsonObject w = new JsonObject();
				w.addProperty("id", idOf(wire));
				w.add("from", point(wire.getEnd0()));
				w.add("to", point(wire.getEnd1()));
				if (wire.getEnd0().getX() != wire.getEnd1().getX()
						&& wire.getEnd0().getY() != wire.getEnd1().getY()) {
					w.addProperty("broken", Boolean.TRUE);
				}
				wires.add(w);
			}
			ret.add("wires", wires);
		}
		ret.addProperty("skewed_wire_count", skewedWires(circuit).size());

		JsonArray pins = new JsonArray();
		for (Instance pin : pins(circuit)) {
			pins.add(describePin(pin));
		}
		ret.add("pins", pins);
		return ret;
	}

	// ------------------------------------------------------------------
	// nets
	// ------------------------------------------------------------------

	/** A component port, remembered with the index it has in its component. */
	private static final class PortRef {
		final Component component;
		final EndData end;
		final int index;

		PortRef(Component component, EndData end, int index) {
			this.component = component;
			this.end = end;
			this.index = index;
		}
	}

	/** Everything gathered for one net while the circuit is being walked. */
	private static final class NetBucket {
		final List<Wire> wires = new ArrayList<Wire>();
		final List<PortRef> ports = new ArrayList<PortRef>();
		final java.util.TreeSet<String> labels = new java.util.TreeSet<String>();
		final java.util.TreeSet<String> tunnels = new java.util.TreeSet<String>();
		boolean sharedLocation;
	}

	/**
	 * Groups the wires and the component ports of a circuit into nets: the sets of
	 * things that are the same electrical node. An agent cannot see the screen, so this
	 * is how it finds the two causes of a puzzling read: a port that lands nowhere
	 * (which reads 'x', "undriven"), and a net with more than one driver (which reads
	 * 'e', "conflicting drivers").
	 *
	 * <p>
	 * The circuit is walked directly instead of asking Logisim for its own bundle map
	 * (which is package private, so using it would mean adding classes inside
	 * com.cburch.logisim.circuit). The rules are the ones the editor shows: a wire
	 * conducts between its own two ends, a wire end that lands on another wire joins
	 * that wire, and wires that merely cross do not join. Logisim has already split
	 * wires where ports meet them and merged collinear overlaps by the time this runs,
	 * so a port that touches a wire does so at a wire end or at a junction found here.
	 * </p>
	 *
	 * @param netFilter
	 *            report only this net, by the id a previous call returned, or null
	 * @param portFilter
	 *            report only the net this location belongs to, or null
	 */
	public JsonObject describeNets(Circuit circuit, String netFilter, Location portFilter, boolean includeUnconnected,
			int limit) {
		indexComponents(circuit);
		List<Wire> wires = new ArrayList<Wire>(circuit.getWires());
		Map<Location, Location> parent = new HashMap<Location, Location>();

		for (Wire wire : wires) {
			union(parent, wire.getEnd0(), wire.getEnd1());
		}
		for (Wire wire : wires) {
			Location[] ends = { wire.getEnd0(), wire.getEnd1() };
			for (Location end : ends) {
				for (Wire other : wires) {
					if (!other.equals(wire) && other.contains(end)) {
						union(parent, end, other.getEnd0());
					}
				}
			}
		}

		Map<Location, NetBucket> buckets = new LinkedHashMap<Location, NetBucket>();
		for (Wire wire : wires) {
			bucketFor(buckets, find(parent, wire.getEnd0())).wires.add(wire);
		}

		List<PortRef> unconnected = new ArrayList<PortRef>();
		Map<Location, List<PortRef>> withoutWire = new LinkedHashMap<Location, List<PortRef>>();
		for (Component comp : circuit.getNonWires()) {
			int index = 0;
			for (EndData end : comp.getEnds()) {
				if (end == null) {
					index++;
					continue;
				}
				Location loc = end.getLocation();
				Location root = parent.containsKey(loc) ? find(parent, loc) : null;
				if (root == null) {
					// a port can also sit inside a wire whose point set has not been
					// split since it was placed; count that as a junction
					for (Wire wire : wires) {
						if (wire.contains(loc)) {
							root = find(parent, wire.getEnd0());
							break;
						}
					}
				}
				PortRef ref = new PortRef(comp, end, index);
				if (root == null) {
					List<PortRef> shareHere = withoutWire.get(loc);
					if (shareHere == null) {
						shareHere = new ArrayList<PortRef>();
						withoutWire.put(loc, shareHere);
					}
					shareHere.add(ref);
				} else {
					addPort(bucketFor(buckets, root), ref);
				}
				index++;
			}
		}
		// ports that share a location are one node to Logisim even without a wire, so
		// they are reported as a net with no wires; a port that sits alone touches
		// nothing at all and is what makes a read come back 'x'
		for (Map.Entry<Location, List<PortRef>> entry : withoutWire.entrySet()) {
			List<PortRef> shareHere = entry.getValue();
			if (shareHere.size() == 1) {
				unconnected.add(shareHere.get(0));
			} else {
				NetBucket bucket = bucketFor(buckets, entry.getKey());
				for (PortRef ref : shareHere) {
					addPort(bucket, ref);
				}
				bucket.sharedLocation = true;
			}
		}

		// the nets worth reading first are the ones with the most connections
		List<Map.Entry<Location, NetBucket>> ordered = new ArrayList<Map.Entry<Location, NetBucket>>(
				buckets.entrySet());
		java.util.Collections.sort(ordered, new java.util.Comparator<Map.Entry<Location, NetBucket>>() {
			@Override
			public int compare(Map.Entry<Location, NetBucket> a, Map.Entry<Location, NetBucket> b) {
				int scoreA = a.getValue().ports.size() * 1000 + a.getValue().wires.size();
				int scoreB = b.getValue().ports.size() * 1000 + b.getValue().wires.size();
				if (scoreA != scoreB) {
					return scoreB - scoreA;
				}
				return a.getKey().compareTo(b.getKey());
			}
		});

		// tunnel labels that appear on more than one net: Logisim joins those nets by
		// name, without any wire, which no net walk can see
		Map<String, Integer> tunnelNets = new HashMap<String, Integer>();
		for (Map.Entry<Location, NetBucket> entry : ordered) {
			for (String label : entry.getValue().tunnels) {
				Integer seen = tunnelNets.get(label);
				tunnelNets.put(label, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
			}
		}

		JsonArray problems = new JsonArray();
		JsonArray nets = new JsonArray();
		int index = 0;
		int shown = 0;
		boolean truncated = false;
		for (Map.Entry<Location, NetBucket> entry : ordered) {
			index++;
			String id = "n" + index;
			NetBucket bucket = entry.getValue();
			boolean selected = true;
			if (netFilter != null) {
				selected = netFilter.equalsIgnoreCase(id) || netFilter.equals(String.valueOf(index));
			}
			if (selected && portFilter != null) {
				selected = false;
				for (PortRef ref : bucket.ports) {
					if (ref.end.getLocation().equals(portFilter)) {
						selected = true;
						break;
					}
				}
			}
			if (!selected) {
				continue;
			}
			if (shown >= limit) {
				truncated = true;
				break;
			}
			shown++;

			JsonObject net = new JsonObject();
			net.addProperty("id", id);
			int length = 0;
			for (Wire wire : bucket.wires) {
				length += wire.getLength();
			}
			net.addProperty("wire_count", bucket.wires.size());
			net.addProperty("wire_length", length);
			net.addProperty("port_count", bucket.ports.size());
			if (bucket.labels.size() > 0) {
				net.add("labels", toJsonArray(bucket.labels));
			}
			int drivers = 0;
			int bidirectional = 0;
			JsonArray pins = new JsonArray();
			for (PortRef ref : bucket.ports) {
				JsonObject port = describePort(ref.component, ref);
				pins.add(port);
				// What drives a net is the raw end type, not the direction an agent sees:
				// Logisim stores an input pin as OUTPUT_ONLY because inside the circuit it
				// is the source of the wire, while an output pin only reads it.
				int endType = ref.end.getType();
				if (endType == EndData.OUTPUT_ONLY) {
					drivers++;
				} else if (endType != EndData.INPUT_ONLY) {
					bidirectional++;
				}
			}
			net.addProperty("drivers", drivers);
			if (bidirectional > 0) {
				net.addProperty("bidirectional", bidirectional);
			}
			net.add("ports", pins);

			// per net notes: the two things that make a read come back 'x' or 'e'
			JsonArray notes = new JsonArray();
			if (drivers > 1) {
				notes.add("this net has " + drivers + " drivers, so it reads 'e' (conflicting drivers)");
				problems.add("net " + id + " has " + drivers + " drivers, which reads as 'e'");
			}
			if (bucket.wires.isEmpty() && (bucket.ports.size() > 1 || bucket.sharedLocation)) {
				notes.add("these ports share a location with no wire between them");
			}
			if (drivers == 0 && bidirectional == 0 && bucket.ports.size() > 0) {
				notes.add("nothing drives this net, so it reads 'x' (undriven)");
				problems.add("net " + id + " has no driver, so it reads as 'x'");
			}
			if (bidirectional > 0) {
				notes.add(bidirectional + " port(s) here can drive as well when enabled (tristate or bus), so this "
						+ "net reads 'x' while nothing is driving it");
			}
			for (String label : bucket.tunnels) {
				Integer count = tunnelNets.get(label);
				if (count != null && count.intValue() > 1) {
					notes.add("tunnel '" + label + "' also appears on " + (count.intValue() - 1)
							+ " other net(s); Logisim joins tunnels by name, with no wire needed");
				}
			}
			if (notes.size() > 0) {
				net.add("notes", notes);
			}
			nets.add(net);
		}

		JsonObject ret = new JsonObject();
		ret.addProperty("circuit", circuit.getName());
		ret.addProperty("wire_count", wires.size());
		ret.addProperty("net_count", buckets.size());
		ret.addProperty("nets_returned", nets.size());
		if (truncated) {
			ret.addProperty("truncated", Boolean.TRUE);
		}
		ret.add("nets", nets);
		if (includeUnconnected && unconnected.size() > 0) {
			JsonArray ports = new JsonArray();
			for (PortRef ref : unconnected) {
				ports.add(describePort(ref.component, ref));
			}
			ret.addProperty("unconnected_port_count", unconnected.size());
			ret.add("unconnected_ports", ports);
			problems.add(unconnected.size() + " port(s) touch no wire, so they read 'x'");
		}
		if (problems.size() > 0) {
			ret.add("problems", problems);
			ret.add("hint", Json.element("a net that reads 'x' has no driver, a net that reads 'e' has two; "
					+ "connect the port to a wire (their positions are given above) or remove one of the drivers"));
		}
		return ret;
	}

	private static NetBucket bucketFor(Map<Location, NetBucket> buckets, Location root) {
		NetBucket bucket = buckets.get(root);
		if (bucket == null) {
			bucket = new NetBucket();
			buckets.put(root, bucket);
		}
		return bucket;
	}

	/** Adds a port to a net and remembers its label, and a tunnel's label. */
	private static void addPort(NetBucket bucket, PortRef ref) {
		bucket.ports.add(ref);
		String label = ref.component.getAttributeSet().getValue(StdAttr.LABEL);
		if (label != null && !label.isEmpty()) {
			bucket.labels.add(label);
			if ("Tunnel".equals(ref.component.getFactory().getName())) {
				bucket.tunnels.add(label);
			}
		}
	}

	private static JsonArray toJsonArray(java.util.Collection<String> values) {
		JsonArray ret = new JsonArray();
		for (String value : values) {
			ret.add(new com.google.gson.JsonPrimitive(value));
		}
		return ret;
	}

	/** One port, described the same way logisim_component_add describes its ports. */
	private JsonObject describePort(Component comp, PortRef ref) {
		JsonObject port = new JsonObject();
		port.addProperty("component", idOf(comp));
		port.addProperty("type", comp.getFactory().getName());
		port.addProperty("port", ref.index);
		List<String> pinLabels = subcircuitPinLabels(comp);
		if (pinLabels != null && ref.index < pinLabels.size() && !pinLabels.get(ref.index).isEmpty()) {
			port.addProperty("pin", pinLabels.get(ref.index));
		}
		String label = comp.getAttributeSet().getValue(StdAttr.LABEL);
		if (label != null && !label.isEmpty()) {
			port.addProperty("label", label);
		}
		port.addProperty("direction", portDirection(comp.getFactory(), ref.end));
		port.addProperty("width", ref.end.getWidth().getWidth());
		port.add("loc", point(ref.end.getLocation()));
		return port;
	}

	/** Union-find over locations; a location that is not known yet becomes its own set. */
	private static Location find(Map<Location, Location> parent, Location loc) {
		Location root = parent.get(loc);
		if (root == null) {
			parent.put(loc, loc);
			return loc;
		}
		while (!root.equals(loc)) {
			loc = root;
			root = parent.get(loc);
			if (root == null) {
				parent.put(loc, loc);
				return loc;
			}
		}
		return loc;
	}

	private static void union(Map<Location, Location> parent, Location a, Location b) {
		Location rootA = find(parent, a);
		Location rootB = find(parent, b);
		if (!rootA.equals(rootB)) {
			parent.put(rootB, rootA);
		}
	}

	public JsonObject describePin(Instance pin) {
		JsonObject ret = new JsonObject();
		ret.addProperty("name", pinName(pin));
		ret.addProperty("direction", Pin.FACTORY.isInputPin(pin) ? "input" : "output");
		ret.add("loc", point(pin.getLocation()));
		BitWidth width = pin.getAttributeValue(StdAttr.WIDTH);
		ret.addProperty("width", width == null ? 1 : width.getWidth());
		ret.addProperty("component_id", idOf(Instance.getComponentFor(pin)));
		return ret;
	}

	public JsonObject describeComponent(Component comp, boolean includeAttributes, boolean includePorts) {
		JsonObject ret = new JsonObject();
		ret.addProperty("id", idOf(comp));
		if (comp instanceof Wire) {
			ret.addProperty("type", "Wire");
			ret.addProperty("lib", "Wiring");
			ret.add("from", point(((Wire) comp).getEnd0()));
			ret.add("to", point(((Wire) comp).getEnd1()));
			return ret;
		}
		ret.addProperty("type", comp.getFactory().getName());
		String lib = libraryOf(comp);
		if (lib != null) {
			ret.addProperty("lib", lib);
		}
		if (comp.getFactory() instanceof com.cburch.logisim.circuit.SubcircuitFactory) {
			com.cburch.logisim.circuit.SubcircuitFactory factory = (com.cburch.logisim.circuit.SubcircuitFactory) comp
					.getFactory();
			ret.addProperty("subcircuit", factory.getSubcircuit().getName());
		}
		String label = comp.getAttributeSet().getValue(StdAttr.LABEL);
		if (label != null && !label.isEmpty()) {
			ret.addProperty("label", label);
		}
		Location loc = comp.getLocation();
		ret.add("loc", point(loc));
		Bounds bounds = comp.getBounds();
		ret.add("bounds", Json.array(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight()));
		if (includeAttributes) {
			ret.add("attributes", describeAttributes(comp.getAttributeSet()));
		}
		if (includePorts) {
			JsonArray ports = new JsonArray();
			List<String> pinLabels = subcircuitPinLabels(comp);
			int index = 0;
			for (EndData end : comp.getEnds()) {
				if (end == null) {
					continue;
				}
				JsonObject port = new JsonObject();
				port.addProperty("index", index);
				port.add("loc", point(end.getLocation()));
				port.addProperty("width", end.getWidth().getWidth());
				port.addProperty("direction", portDirection(comp.getFactory(), end));
				if (pinLabels != null && index < pinLabels.size()) {
					port.addProperty("pin", pinLabels.get(index));
				}
				ports.add(port);
				index++;
			}
			ret.add("ports", ports);
		}
		return ret;
	}

	/**
	 * The direction of a port as an agent should think about it: an "input" port
	 * receives the value carried by the wire, an "output" port drives it.
	 *
	 * <p>
	 * Pins are special: Logisim inverts the port type for them, because an input pin
	 * of a circuit is the source of the internal wire while an output pin is a sink
	 * that reads it. Reporting the raw type would tell an agent the opposite of what
	 * it sees on screen, so the pin's user facing role is reported instead.
	 * </p>
	 */
	private static String portDirection(ComponentFactory factory, EndData end) {
		int type = end.getType();
		if (factory instanceof Pin) {
			return type == EndData.OUTPUT_ONLY ? "input" : "output";
		}
		return endType(type);
	}

	/**
	 * For a subcircuit instance, the label of the subcircuit pin behind each port, in
	 * port index order, or null for anything else. SubcircuitFactory creates the ports
	 * in exactly this order, so the two line up.
	 */
	private static List<String> subcircuitPinLabels(Component comp) {
		if (!(comp.getFactory() instanceof SubcircuitFactory)) {
			return null;
		}
		SubcircuitFactory factory = (SubcircuitFactory) comp.getFactory();
		Direction facing = comp.getAttributeSet().getValue(StdAttr.FACING);
		java.util.SortedMap<Location, Instance> offsets = factory.getSubcircuit().getAppearance()
				.getPortOffsets(facing == null ? Direction.EAST : facing);
		List<String> ret = new ArrayList<String>();
		for (java.util.Map.Entry<Location, Instance> entry : offsets.entrySet()) {
			String label = entry.getValue().getAttributeValue(StdAttr.LABEL);
			ret.add(label == null ? "" : label);
		}
		return ret;
	}

	private static String endType(int type) {
		if (type == EndData.INPUT_ONLY) {
			return "input";
		}
		if (type == EndData.OUTPUT_ONLY) {
			return "output";
		}
		return "input_output";
	}

	/**
	 * A short explanation to attach whenever a read returned an unknown or error
	 * value, so that an agent understands the two reasons it usually happens.
	 */
	public static String explainOddValues(JsonObject signals) {
		if (signals == null) {
			return null;
		}
		boolean unknown = false;
		boolean error = false;
		for (Map.Entry<String, JsonElement> entry : signals.entrySet()) {
			String text = entry.getValue().getAsString();
			if (text.indexOf('e') >= 0) {
				error = true;
			}
			if (text.indexOf('x') >= 0) {
				unknown = true;
			}
		}
		if (!unknown && !error) {
			return null;
		}
		StringBuilder buf = new StringBuilder();
		if (unknown) {
			buf.append("'x' is an unknown value, which normally means an input pin has not been driven yet "
					+ "(a freshly added pin is tristate); drive it with logisim_sim_set_input or "
					+ "logisim_sim_set_inputs.");
		}
		if (error) {
			if (buf.length() > 0) {
				buf.append(' ');
			}
			buf.append("'e' is an error value. Logisim reports it for an undriven wire (nothing is driving it), "
					+ "for conflicting drivers on one net, and by default also for a gate whose input is "
					+ "undefined.");
		}
		return buf.toString();
	}

	public JsonObject describeAttributes(AttributeSet attrs) {
		JsonObject ret = new JsonObject();
		for (Attribute<?> attr : attrs.getAttributes()) {
			if (!attrs.isToSave(attr)) {
				continue;
			}
			Object value = attrs.getValue(attr);
			ret.add(attr.getName(), attributeToJson(attr, value));
		}
		return ret;
	}

	@SuppressWarnings("unchecked")
	private static JsonElement attributeToJson(Attribute<?> attr, Object value) {
		if (value == null) {
			return com.google.gson.JsonNull.INSTANCE;
		}
		if (value instanceof AttributeOption) {
			return Json.element(((AttributeOption) value).getValue());
		}
		if (value instanceof BitWidth) {
			return new JsonPrimitive(Integer.valueOf(((BitWidth) value).getWidth()));
		}
		if (value instanceof Boolean || value instanceof Number || value instanceof String) {
			return Json.element(value);
		}
		return new JsonPrimitive(((Attribute<Object>) attr).toDisplayString(value));
	}

	/** Machine readable description of one attribute of a factory. */
	@SuppressWarnings("unchecked")
	public JsonObject describeAttributeSchema(Attribute<?> attr, AttributeSet attrs) {
		JsonObject ret = new JsonObject();
		ret.addProperty("name", attr.getName());
		ret.addProperty("display", attr.getDisplayName());
		Object value = attrs.getValue(attr);
		if (value instanceof Boolean) {
			ret.addProperty("type", "boolean");
		} else if (value instanceof BitWidth) {
			ret.addProperty("type", "int");
			ret.addProperty("hint", "bus width in bits");
		} else if (value instanceof Integer) {
			ret.addProperty("type", "int");
		} else if (value instanceof AttributeOption) {
			ret.addProperty("type", "option");
		} else if (value instanceof String) {
			ret.addProperty("type", "string");
		} else {
			ret.addProperty("type", "text");
			ret.addProperty("hint", "free form value; see the Logisim user guide");
		}
		if (value != null) {
			ret.add("default", attributeToJson(attr, value));
		}
		List<String> options = optionStrings(attr, value);
		if (options != null) {
			ret.add("options", Json.array(options));
		}
		return ret;
	}

	/**
	 * Best effort enumeration of the legal values of an attribute.
	 *
	 * <p>
	 * Logisim keeps the allowed values of option attributes in a private array, so
	 * the only complete, generic way to read them is to ask the attribute for its
	 * table cell editor, which is a combo box holding exactly those values. Swing
	 * components can be constructed in a headless JVM, and any failure is caught so
	 * that the catalogue simply omits the option list.
	 * </p>
	 */
	/**
	 * Any type information is lost here on purpose: Logisim's attribute editor takes the
	 * current value as an untyped argument, and this is only used to ask an option
	 * attribute which choices it offers.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Attribute rawAttribute(Attribute<?> attr) {
		return attr;
	}

	public static List<String> optionStrings(Attribute<?> attr, Object current) {
		List<String> ret = new ArrayList<String>();
		if (current instanceof Boolean) {
			ret.add("false");
			ret.add("true");
			return ret;
		}
		if (current instanceof Direction) {
			for (Direction direction : Direction.cardinals) {
				ret.add(direction.toString());
			}
			return ret;
		}
		if (current == null || !(current instanceof AttributeOption)) {
			return null;
		}
		try {
			// The single argument version of getCellEditor is protected, so use the public
			// one and pass no parent window: the attribute only needs it to parent a dialog,
			// which is not needed for simply building the editor.
			java.awt.Component editor = rawAttribute(attr).getCellEditor(null, current);
			if (editor instanceof javax.swing.JComboBox) {
				javax.swing.JComboBox<?> combo = (javax.swing.JComboBox<?>) editor;
				for (int i = 0; i < combo.getItemCount(); i++) {
					Object item = combo.getItemAt(i);
					if (item != null) {
						ret.add(String.valueOf(item));
					}
				}
			}
		} catch (Throwable t) {
			// no option list available: the agent can still try a value and read the
			// error message, which always mentions the current value
		}
		return ret.isEmpty() ? null : ret;
	}

	/**
	 * Converts a JSON value from a tool call into the Java value an attribute
	 * expects, using the attribute's current value to decide how to interpret it.
	 */
	@SuppressWarnings("unchecked")
	public Object attributeFromJson(AttributeSet attrs, Attribute<?> attr, JsonElement value) {
		Object current = attrs.getValue(attr);
		if (current instanceof Boolean) {
			return Boolean.valueOf(asBoolean(attr, value));
		}
		if (current instanceof BitWidth) {
			return BitWidth.create(asInt(attr, value));
		}
		if (current instanceof Integer) {
			return Integer.valueOf(asInt(attr, value));
		}
		if (value == null || value.isJsonNull()) {
			throw new McpException("attribute '" + attr.getName() + "' cannot be null");
		}
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
			return Boolean.valueOf(value.getAsBoolean());
		}
		String text = value.isJsonPrimitive() ? value.getAsString() : value.toString();
		try {
			Object parsed = ((Attribute<Object>) attr).parse(text);
			if (parsed != null) {
				return parsed;
			}
		} catch (RuntimeException e) {
			// fall through to the error below
		}
		if (current instanceof AttributeOption) {
			throw new McpException("cannot interpret '" + text + "' for attribute '" + attr.getName() + "'"
					+ optionHint(attr, current));
		}
		throw new McpException("cannot interpret '" + text + "' for attribute '" + attr.getName() + "'");
	}

	private static String optionHint(Attribute<?> attr, Object current) {
		List<String> options = optionStrings(attr, current);
		if (options == null) {
			return "; the current value is '" + attributeToJson(attr, current) + "'";
		}
		StringBuilder buf = new StringBuilder("; valid values are: ");
		for (int i = 0; i < options.size(); i++) {
			if (i > 0) {
				buf.append(", ");
			}
			buf.append(options.get(i));
		}
		return buf.toString();
	}

	private static boolean asBoolean(Attribute<?> attr, JsonElement value) {
		if (value != null && value.isJsonPrimitive()) {
			if (value.getAsJsonPrimitive().isBoolean()) {
				return value.getAsBoolean();
			}
			String text = value.getAsString().trim().toLowerCase();
			if (text.equals("true") || text.equals("1") || text.equals("yes") || text.equals("output")
					|| text.equals("out")) {
				return true;
			}
			if (text.equals("false") || text.equals("0") || text.equals("no") || text.equals("input")
					|| text.equals("in")) {
				return false;
			}
		}
		throw new McpException("attribute '" + attr.getName() + "' expects true/false"
				+ (attr.getName().equals("output") ? " (true = output pin, false = input pin)" : ""));
	}

	private static int asInt(Attribute<?> attr, JsonElement value) {
		if (value != null && value.isJsonPrimitive()) {
			JsonPrimitive primitive = value.getAsJsonPrimitive();
			if (primitive.isNumber()) {
				return primitive.getAsInt();
			}
			try {
				return Integer.parseInt(primitive.getAsString().trim());
			} catch (NumberFormatException e) {
				// handled below
			}
		}
		throw new McpException("attribute '" + attr.getName() + "' expects an integer");
	}

	public static JsonArray point(Location loc) {
		return Json.array(loc.getX(), loc.getY());
	}

	// ------------------------------------------------------------------
	// locations
	// ------------------------------------------------------------------

	/**
	 * Resolves a wire endpoint: either a coordinate, or a symbolic reference to
	 * something that has a position. Accepted text forms are
	 * <ul>
	 * <li>{@code "c3.1"} - port 1 of the component with session id c3,</li>
	 * <li>{@code "c3"} - the component with session id c3 (its only port, or its
	 * position),</li>
	 * <li>{@code "A"} - the pin or component labelled A,</li>
	 * <li>{@code "120,80"} - an explicit location.</li>
	 * </ul>
	 * This lets an agent connect ports without doing coordinate arithmetic, which is
	 * where most wiring mistakes would otherwise come from.
	 */
	public Location resolvePoint(Circuit circuit, JsonElement element) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return parseLocation(element);
		}
		String text = element.getAsString().trim();
		if (text.isEmpty()) {
			throw new McpException("a wire endpoint must not be empty");
		}

		int dot = text.indexOf('.');
		if (dot > 0) {
			String ownerText = text.substring(0, dot);
			Component owner = findById(circuit, ownerText);
			if (owner == null) {
				owner = findByLabel(circuit, ownerText);
			}
			if (owner != null) {
				String portText = text.substring(dot + 1);
				int index = -1;
				try {
					index = Integer.parseInt(portText);
				} catch (NumberFormatException e) {
					index = -1;
				}
				if (index >= 0) {
					List<EndData> ends = owner.getEnds();
					if (index >= ends.size() || ends.get(index) == null) {
						throw new McpException("component " + ownerText + " has " + ends.size()
								+ " port(s), so there is no port " + index);
					}
					return ends.get(index).getLocation();
				}
				return subcircuitPort(owner, portText);
			}
		}

		Instance pin = pinByName(circuit, text);
		if (pin != null) {
			return pin.getLocation();
		}
		Component labelled = findByLabel(circuit, text);
		if (labelled != null) {
			return singlePointOf(labelled);
		}
		Component byId = findById(circuit, text);
		if (byId != null) {
			return singlePointOf(byId);
		}
		Location loc = tryParseLocation(text);
		if (loc != null) {
			return loc;
		}
		throw new McpException("'" + text + "' is not a pin label, a component label, a component id, "
				+ "a '<id>.<port>' reference or an \"x,y\" location in circuit '" + circuit.getName() + "'");
	}

	/**
	 * Resolves {@code "<instance>.<pin label>"} for a subcircuit instance: a
	 * subcircuit's pins become the instance's ports in the order the appearance
	 * reports them, which is the same order {@code SubcircuitFactory} uses when it
	 * creates the ports.
	 */
	private static Location subcircuitPort(Component owner, String pinLabel) {
		if (!(owner.getFactory() instanceof SubcircuitFactory)) {
			throw new McpException("'".concat(pinLabel)
					+ "' is neither a port index nor a pin label, and the referenced component is not a subcircuit");
		}
		SubcircuitFactory factory = (SubcircuitFactory) owner.getFactory();
		Circuit subcircuit = factory.getSubcircuit();
		Direction facing = owner.getAttributeSet().getValue(StdAttr.FACING);
		java.util.SortedMap<Location, Instance> offsets = subcircuit.getAppearance()
				.getPortOffsets(facing == null ? Direction.EAST : facing);
		List<EndData> ends = owner.getEnds();
		StringBuilder available = new StringBuilder();
		int index = -1;
		for (java.util.Map.Entry<Location, Instance> entry : offsets.entrySet()) {
			index++;
			String label = entry.getValue().getAttributeValue(StdAttr.LABEL);
			if (label == null || label.isEmpty()) {
				label = "port " + index;
			}
			if (available.length() > 0) {
				available.append(", ");
			}
			available.append(label);
			if (label.equals(pinLabel)) {
				if (index < ends.size() && ends.get(index) != null) {
					return ends.get(index).getLocation();
				}
				Location base = owner.getLocation();
				return Location.create(base.getX() + entry.getKey().getX(), base.getY() + entry.getKey().getY());
			}
		}
		throw new McpException("subcircuit '" + subcircuit.getName() + "' has no pin labelled '" + pinLabel
				+ "'; its pins are: " + available);
	}

	private static Location singlePointOf(Component comp) {		List<EndData> ends = comp.getEnds();
		if (ends.size() == 1 && ends.get(0) != null) {
			return ends.get(0).getLocation();
		}
		return comp.getLocation();
	}

	public static Location parseLocation(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			throw new McpException("a coordinate is required, given as [x, y]");
		}
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			if (array.size() < 2) {
				throw new McpException("a coordinate must have two members, [x, y]");
			}
			return Location.create(array.get(0).getAsInt(), array.get(1).getAsInt());
		}
		if (element.isJsonObject()) {
			JsonObject obj = element.getAsJsonObject();
			if (!Json.isPresent(obj, "x") || !Json.isPresent(obj, "y")) {
				throw new McpException("a coordinate must have 'x' and 'y' members");
			}
			return Location.create(obj.get("x").getAsInt(), obj.get("y").getAsInt());
		}
		if (element.isJsonPrimitive()) {
			Location ret = tryParseLocation(element.getAsString());
			if (ret != null) {
				return ret;
			}
		}
		throw new McpException("a coordinate must be given as [x, y], for example [120, 80]");
	}

	public static Location tryParseLocation(String text) {
		if (text == null) {
			return null;
		}
		String trimmed = text.trim();
		int comma = trimmed.indexOf(',');
		if (comma < 0) {
			return null;
		}
		try {
			int x = Integer.parseInt(trimmed.substring(0, comma).trim());
			int y = Integer.parseInt(trimmed.substring(comma + 1).trim());
			return Location.create(x, y);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------
	// small helpers
	// ------------------------------------------------------------------

	public String defaultSaveLocation() {
		return path == null ? null : path.getAbsolutePath();
	}

	public static JsonObject errorResult(String message) {
		return Json.object("error", message);
	}

	public static String message(Throwable t) {
		StringBuilder buf = new StringBuilder();
		Throwable current = t;
		int guard = 0;
		while (current != null && guard++ < 5) {
			String part = current.getMessage();
			if (part != null && !part.isEmpty()) {
				if (buf.length() > 0) {
					buf.append(" -> ");
				}
				buf.append(part);
			}
			current = current.getCause();
		}
		if (buf.length() == 0) {
			buf.append(t.getClass().getName());
		}
		return buf.toString();
	}

	private static void closeQuietly(InputStream in) {
		if (in != null) {
			try {
				in.close();
			} catch (Exception e) {
				// nothing useful to do
			}
		}
	}

	private static void closeQuietly(Closeable in) {
		if (in != null) {
			try {
				in.close();
			} catch (Exception e) {
				// nothing useful to do
			}
		}
	}

	public Value readValue(Location loc) {
		return state().getValue(loc);
	}
}
