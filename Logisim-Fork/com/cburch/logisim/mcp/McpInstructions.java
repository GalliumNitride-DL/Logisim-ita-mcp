/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

/**
 * Every word this server says to the agent, in one place.
 *
 * <p>
 * The descriptions of the tools and the instructions returned by {@code initialize} are
 * how the agent is taught to use Logisim, and they are the part of this feature that
 * changes most often - a rule learned from a bad session belongs here. They used to be
 * spread over {@link McpServer} and every class in {@code com.cburch.logisim.mcp.tools};
 * now they live together so that the whole of the agent's briefing can be reviewed and
 * reworded by reading one file.
 * </p>
 *
 * <p>
 * Rule of thumb for editing: instructions are advice, and they must stay true of the
 * implementation. Anything stated here as a hard rule (a coordinate grid, a forbidden
 * shape, a refusal) is enforced by the tool that does the work - see for example
 * {@link #NEGATIVE_COORDINATES}, which the edit tools throw. Do not put a "never do X"
 * here that nothing refuses, and do not add a refusal whose wording is not here.
 * </p>
 *
 * <p>
 * Strings are plain English literals rather than resource-bundle entries, exactly as the
 * MCP window's strings are: this text is addressed to a language model, not to a user
 * interface, so it is not translated and Logisim's bundles stay untouched.
 * </p>
 */
public final class McpInstructions {
	private McpInstructions() {
	}

	/**
	 * The {@code instructions} field of the {@code initialize} result: the agent's whole
	 * briefing, and often the only thing it reads before starting.
	 */
	public static final String SERVER = "Controls the Logisim digital circuit simulator. "
			+ "Circuits are edited in memory: nothing is written to disk unless logisim_file_save or "
			+ "logisim_file_save_as is called. Typical workflow: logisim_file_new or logisim_file_open, "
			+ "logisim_component_catalog to learn the available components, logisim_component_add and "
			+ "logisim_wire_add to build, logisim_sim_set_input / logisim_sim_set_inputs plus logisim_sim_read / "
			+ "logisim_sim_read_all to test, logisim_analyze_truth_table for exhaustive verification, then "
			+ "logisim_file_save. Coordinates are integers.\n"
			+ "\n"
			+ "READ THIS FIRST - ignoring these rules can make a circuit, a whole session and the saved file "
			+ "unusable, with a minutes long hang ending in 'OutOfMemoryError: Java heap space':\n"
			+ "1. NEVER create a wire whose two ends differ in BOTH x and y (a diagonal wire). Logisim cannot "
			+ "iterate such a wire, so every later edit - and every attempt to load the saved file - spins until "
			+ "the Java heap is exhausted. Let logisim_wire_add do the routing: give it symbolic endpoints "
			+ "(\"A\", \"c3\", \"c3.1\", \"FA0.Cout\") or a 'from'/'to' pair, and it inserts a right angled corner; "
			+ "if a pair of points is diagonal, it routes or refuses - it never emits a diagonal. If it refuses, "
			+ "pass an explicit 'points' list with a steering point instead of forcing coordinates through.\n"
			+ "2. Keep every coordinate a multiple of 10, for components and for wire points. Logisim itself "
			+ "corrupts wires whose geometry is off this grid: it accepts a point up to 2 units off a wire's line "
			+ "and then splits the wire there, producing the diagonal wire of rule 1. A component port that lands "
			+ "a few units off an existing wire is enough to trigger it.\n"
			+ "3. NEVER use a negative coordinate: x and y are 0 or more, for a component's position and for every "
			+ "wire point. Logisim's canvas starts at the origin and extends down and right, so anything at a "
			+ "negative coordinate is off the visible area - the circuit may be perfectly correct and still look "
			+ "empty to the user. The edit tools refuse negative coordinates. Start at a comfortable point such as "
			+ "(100, 100) and grow right and down; logisim_circuit_info reports the 'bounds' of what is already "
			+ "there, so place new work clear of it rather than on top of it.\n"
			+ "4. Prefer ONE batched edit over many small ones: logisim_wire_add's 'paths' draws many wires as a "
			+ "single undoable edit, and Logisim repairs the whole circuit's wiring on every edit, so n calls cost "
			+ "n repairs and real time. Do not fire edits in parallel; they are serialised by per circuit locks.\n"
			+ "5. Watch for the symptom and act on it: if any tool replies 'contains N wire(s) whose ends are not "
			+ "axis aligned', call logisim_circuit_repair_wires for that circuit (it is safe in memory); if a file "
			+ "on disk has that problem, logisim_file_open refuses to hang on it - use logisim_file_recover, then "
			+ "logisim_file_save_as to write the repaired file.\n"
			+ "6. Check logisim_status before long builds and before saving: it reports heap usage under 'memory', "
			+ "'last_wire_repair', and which circuit is current. logisim_circuit_describe reports "
			+ "'skewed_wire_count' and marks each bad wire 'broken'. Save early, and keep circuits modest.\n"
			+ "7. Verify electrically after wiring: logisim_sim_propagate then logisim_sim_read_all. A net that "
			+ "reads 'x' (undriven, floating) or 'e' (error, conflicting drivers) usually means a wire endpoint "
			+ "missed a port, or two nets got joined. Component placement does not connect anything: a connection "
			+ "exists only where a wire end coincides exactly with a port, or where a wire end lands on another "
			+ "wire (that junction shorts them together).\n"
			+ "8. Do not guess what a component's pins mean. logisim_component_catalog with a 'name' filter lists "
			+ "the ports of a component with their names, directions and positions, and logisim_help reads "
			+ "Logisim's own documentation, which describes what each pin does. Getting the pins wrong (an "
			+ "enable treated as a clock, a clear treated as data) is the most common way a circuit silently "
			+ "misbehaves. Every port comes back with a 'ref' you can hand straight to logisim_wire_add, so there "
			+ "is never a reason to work a port position out by hand.\n"
			+ "\n"
			+ "WIRING STYLE - what keeps a Logisim schematic readable, which is what the user looks at afterwards:\n"
			+ "- Connect neighbouring components - a few segments - with real wires. Anything that travels further, "
			+ "crosses other wiring, or has to reach several places uses TUNNELS named after the signal instead: one "
			+ "tunnel connects to every tunnel with the same label, anywhere in the circuit, with no wire needed. "
			+ "Mixing the two styles on one net is legal but looks wrong.\n"
			+ "- Face things the conventional way: tunnels east or west so their label reads along the flow, ground "
			+ "and power pointing away from the wire they feed, pins facing the wire they join. Logisim will not "
			+ "complain about an unusual orientation; it just makes the circuit harder to read.\n"
			+ "- Place every component before wiring, so no wire has to pass something that does not exist yet. Two "
			+ "wires crossing is harmless. A wire that ENDS on another wire joins it, and a wire that passes through "
			+ "a port joins that net: logisim_wire_add warns about that case and names the wire, and "
			+ "logisim_wire_remove {loc: [x, y]} removes just that segment.";

	/** Added to the instructions when the server runs inside the user's Logisim window. */
	public static final String LIVE_SUFFIX = "\n"
			+ "\n"
			+ "LIVE MODE - this server runs inside the user's Logisim window and the user is watching:\n"
			+ "- You work on the project the user has open, not on one of your own. Tools act on the window the user "
			+ "is looking at; logisim_circuit_select decides which circuit is on screen.\n"
			+ "- Every call is visible: the canvas repaints, the components and wires you touched become the "
			+ "selection, and each call is a named undo step in the Edit menu. Keep edits small and legible, and "
			+ "prefer one batched logisim_wire_add over many single wire calls.\n"
			+ "- Select the circuit you are about to change first, so the user sees the work happen, and check "
			+ "logisim_gui_status for the window, the circuit, the selection, and whether the user has paused you "
			+ "or turned on read-only mode.\n"
			+ "- A call can time out when the user has a dialog open or is in the middle of something; that is not a "
			+ "crash. Retry, or tell the user what to close.\n"
			+ "- Opening a file brings up a new window (an already open file is focused instead). You may not close "
			+ "windows or start a new empty project: that would discard the user's work.";

	/** The instructions for one session, live or headless. */
	public static String server(boolean live) {
		return live ? SERVER + LIVE_SUFFIX : SERVER;
	}

	// ------------------------------------------------------------------
	// messages the agent sees when a rule is enforced
	// ------------------------------------------------------------------

	/**
	 * The description of {@code logisim_help}. It is the one tool that is about reading
	 * rather than doing, so it says plainly what it is for: the manual, and the library
	 * reference in which every component's pins are described.
	 */
	public static final String HELP = "Reads Logisim's own documentation, offline: the user guide and the library "
			+ "reference, which is where each component's pins are described (which input is the clock, which is the "
			+ "clear, what an enable does, what the outputs mean). Use it whenever a component's behaviour is not "
			+ "obvious, and before building anything whose correctness depends on a pin you are guessing at. With no "
			+ "arguments it lists the manual; with 'query' it searches every page and returns an excerpt of each hit; "
			+ "with 'topic' it returns one page as text. A topic may be a page path ('libs/mem/flipflops.html'), a part "
			+ "of one ('flipflops'), or a component's name ('D Flip-Flop'); a topic that names no page is searched for "
			+ "instead of refused, since the manual's page names do not always match a component's name. Also try "
			+ "logisim_component_catalog with a 'name' filter: it reports the ports of a component with their names "
			+ "and directions, straight from the library.";

	/**
	 * Reported when the manual cannot be found. This is worth a message of its own because
	 * it is a packaging fault rather than a mistake by the agent, and because the fix is
	 * for the user: a build that did not put {@code doc/} into the jar (older build scripts)
	 * has no documentation in it at all.
	 */
	public static final String DOCS_MISSING = "Logisim's documentation is not available to this build. It lives in "
			+ "the 'doc' directory of the source tree and is normally bundled into the jar and the exe by "
			+ "build-mcp.ps1; a build made with an older script, or one running outside the source tree, has no "
			+ "documentation in it. Tell the user, and carry on without it: the pins of a component can still be "
			+ "learned from logisim_component_catalog, which reports each port's name and direction from the library "
			+ "itself.";

	/**
	 * Thrown by the edit tools when a coordinate is negative, the rule stated as number 3
	 * of {@link #SERVER}. It explains where the canvas is instead of only saying no,
	 * because an agent that wrote a negative coordinate was usually thinking of its own
	 * layout rather than of Logisim's.
	 */
	public static String negativeCoordinate(String what, int x, int y) {
		return negativeCoordinate(what, x, y, null);
	}

	/**
	 * The same, with a sentence saying what to do instead - the part an agent actually
	 * needs, because it has the numbers to work it out.
	 */
	public static String negativeCoordinate(String what, int x, int y, String fix) {
		return what + " (" + x + ", " + y + ") has a negative coordinate, and negative coordinates are off "
				+ "Logisim's canvas: it starts at the origin and grows right and down, so anything above or left of "
				+ "it is invisible to the user even though the circuit is valid. Use x >= 0 and y >= 0 (multiples of "
				+ "10), starting at something like (100, 100); logisim_circuit_info reports the 'bounds' of the "
				+ "existing content so you can place new work clear of it."
				+ (fix == null ? "" : " " + fix);
	}

	// ------------------------------------------------------------------
	// the tools, in registration order
	// ------------------------------------------------------------------

	public static final String ANALYZE_TRUTH_TABLE = "Computes the complete truth table of a combinational circuit by driving every input pin through "
					+ "every combination and reading every output pin, using Logisim's own analysis code. This is "
					+ "the strongest way to verify a circuit an agent has just built. Cell values are 0, 1, x "
					+ "(unknown/floating) or e (error/conflicting drivers); an oscillating circuit reports "
					+ "'!!' for the affected rows. The number of rows is 2^inputs, so keep 'max_inputs' modest.";

	public static final String ANALYZE_EXPRESSION = "Reports the boolean expression of every output pin of a combinational circuit, derived from the "
					+ "circuit's truth table by prime-implicant minimisation (the same code behind the Expression, "
					+ "Minimized and Karnaugh-map tabs of Logisim's Analyze Circuit window). Also returns the prime "
					+ "implicants, as the minterms each one covers, which is what a Karnaugh map draws as a group.";

	public static final String CIRCUIT_BUILD = "Generates a gate level circuit from a truth table, using Logisim's own circuit builder. Supply "
					+ "either 'from_circuit' (reuse the behaviour of an existing circuit) or 'inputs' plus "
					+ "'outputs' to specify the table directly. The destination circuit is cleared first, so name a "
					+ "fresh or empty circuit, or pass replace = true.";

	public static final String LIBRARY_LIST = "Lists the component libraries available in the current project, with the number of components in "
					+ "each. Subcircuits of the project itself appear under the pseudo library 'Circuits'.";

	public static final String COMPONENT_CATALOG = "Lists the components that can be placed in a circuit, optionally filtered by library and by a "
					+ "name substring, together with each component's attributes (name, type, default, and the "
					+ "legal values where Logisim can report them), its port count, and - for a small result set or with "
					+ "'verbose' - each port's name, direction, width and position. The names are the library's own "
					+ "(\"clock\", \"clear\", \"D\", \"Q\", \"carry out\"), and they are the answer to \"what does each pin do\": "
					+ "check them before wiring a component up, because guessing which input is the clock is how a "
					+ "circuit ends up silently wrong. Call this before "
					+ "logisim_component_add so that attribute names and values are correct; for example a Pin has "
					+ "a boolean attribute 'output' (true makes it an output pin) and a 'facing' attribute with "
					+ "values north/east/south/west.";

	public static final String CIRCUIT_LIST = "Lists every circuit in the project with its size. A Logisim project is a set of circuits; one of "
					+ "them is the main circuit, and the others can be placed as components inside each other.";

	public static final String CIRCUIT_DESCRIBE = "Returns the full contents of one circuit: every component with its session id, type, position, "
					+ "attributes and ports, every wire with its endpoints, and the circuit's pins. This is the "
					+ "main way to inspect what has been built so far. Wires are the only things that connect "
					+ "components: a wire endpoint must land exactly on a port position.";

	public static final String CIRCUIT_CREATE = "Creates a new empty circuit (a subcircuit sheet). It can then be placed inside other circuits as a "
					+ "component: add one with logisim_component_add using lib='Circuits' and type=<circuit name>. "
					+ "Its pins become the subcircuit's ports.";

	public static final String CIRCUIT_DELETE = "Deletes a circuit from the project. Refuses to delete the main circuit or any circuit that is "
					+ "still used as a subcircuit somewhere, so that a project can never be left inconsistent.";

	public static final String CIRCUIT_SET_MAIN = "Designates which circuit is the project's main circuit. The main circuit is the one Logisim "
					+ "simulates and shows when the file is opened.";

	public static final String CIRCUIT_SELECT = "Switches which circuit subsequent tools act on and which circuit's simulation state is used. "
					+ "Just an editing convenience: it changes nothing in the project itself.";

	public static final String CIRCUIT_STATS = "Counts how many components of each type a circuit uses, including those pulled in through "
					+ "subcircuits. Useful to check that a generated design uses the components you intended.";

	public static final String CIRCUIT_REPAIR_WIRES = "Repairs wires whose two ends differ in both x and y. Logisim should never contain such a wire, but "
					+ "its own wire repair can create one, and it cannot iterate one: WireIterator walks from end to "
					+ "end diagonally and only stops when it lands exactly on the far end, which never happens, so "
					+ "iteration never terminates. Since every edit makes Logisim walk every point of every wire "
					+ "(and so does opening a file), a single such wire makes the next edit, and every later load "
					+ "of the saved file, spin until the Java heap is exhausted - 'Java heap space' after a long "
					+ "pause is the classic symptom. This tool replaces each such wire with two axis aligned "
					+ "segments through a corner, keeping both ends connected. It is safe to run on a damaged "
					+ "circuit because the broken wire is removed before Logisim repairs the circuit's wires, so it "
					+ "is never walked. Every edit and every save already repairs such wires automatically, so this "
					+ "tool is normally only needed to inspect a circuit or to force a repair.";


	public static final String CIRCUIT_LINT = "Checks a circuit and reports what is wrong with it, changing nothing: a wire that runs "
			+ "through a port (which joins two nets), a net with more than one driver or none, a port that touches no "
			+ "wire, a tunnel with no label (they all join each other), a tunnel facing north or south, a ground or "
			+ "power whose facing differs from the rest of the circuit, a component or wire end off the 10 pixel "
			+ "grid, a component at a negative coordinate the user cannot see, and components drawn on top of one "
			+ "another. Each finding is graded error (the circuit will not behave as drawn), warning (it works but "
			+ "reads against the conventions) or info, and names the tool that fixes it. Run it after wiring and "
			+ "before saving or handing the circuit over; 'severity' narrows the report to errors.";

	public static final String SIM_RUN_VECTORS = "Applies many input vectors in one call and returns a table, which is the shape a verification "
			+ "pass actually has. 'vectors' is a list of {values: {pin: value}, expect: {pin: value}, name: \"...\"}, "
			+ "where 'expect' is optional: with it, each vector comes back as pass or fail and the mismatching pins "
			+ "are named. A single test may be given as 'values' with 'expect' beside it. Values take the same forms "
			+ "as logisim_sim_set_input, including hex such as 0xFF; pass 'include_signals: false' to keep the answer "
			+ "small when only the expected pins matter.";

	public static final String COMPONENT_ADD = "Places one component (gate, pin, flip-flop, multiplexer, memory, subcircuit, ...) at a position in "
					+ "a circuit. Returns the new component's session id, its ports and their positions. "
					+ "Components are not connected by placement: add wires with logisim_wire_add so that a wire "
					+ "endpoint lands exactly on a port position. Use 'label' to name pins and signals, "
					+ "'facing' to orient the component, and 'width' for bus width. A new Pin is tristate, so an "
					+ "undriven input reads as x until you set it; pass attributes {\"tristate\": false} if an "
					+ "undriven input should read 0 instead. IMPORTANT: x and y must be 0 or more - a negative "
					+ "coordinate is off the canvas and invisible to the user, and the tool refuses it - and multiples "
					+ "of 10. A port placed "
					+ "within 2 units of an existing wire makes Logisim split that wire into a diagonal wire, which "
					+ "makes every further edit and every later load of the file hang until the Java heap is exhausted. "
					+ "An attribute this component does not have is skipped and listed under 'ignored_attributes' - "
					+ "'label' does not exist on Arithmetic and Plexers components - and pass "
					+ "on_unknown_attribute: \"fail\" if you would rather be refused. The reply gives every port a 'ref' "
					+ "that logisim_wire_add accepts, which is safer than arithmetic on coordinates.";

	public static final String COMPONENT_REMOVE = "Removes a component or a single wire from a circuit. Attached wires are left in place, so they may "
					+ "end up dangling; remove them separately with logisim_wire_remove if that is not wanted.";

	public static final String COMPONENT_MOVE = "Moves a component to a new position, given either absolute 'x'/'y' or a relative 'dx'/'dy'. "
					+ "Logisim does not drag wires along, so any wire that ended on the component's old port "
					+ "positions will be left behind: the result reports how many, and you should re-add those "
					+ "connections with logisim_wire_add. The new position must be at x >= 0 and y >= 0, for the same "
					+ "reason as logisim_component_add. Consider removing and re-adding a component instead if "
					+ "it is already wired.";

	public static final String COMPONENT_SET_ATTRIBUTES = "Changes one or more attributes of an existing component, for example renaming a pin (label), "
					+ "switching a pin between input and output (output = true/false), widening a bus (width), or "
					+ "changing a memory's address size. Attribute names and legal values are listed by "
					+ "logisim_component_catalog.";

	public static final String WIRE_ADD = "Draws wires, which are what actually connect component ports. Give a list of points in 'points'; "
					+ "consecutive points must share either the x or the y coordinate, and a diagonal step between "
					+ "two points is replaced by a right angled route automatically (choosing whichever corner does "
					+ "not run through another component's port). Alternatively give just 'from' and 'to' for a single "
					+ "connection, or 'paths' to draw many connections in one undoable edit. A wire endpoint must "
					+ "coincide exactly with a port position (see the "
					+ "'ports' entries of logisim_component_add / logisim_circuit_describe) for the connection to "
					+ "count; use symbolic endpoints such as \"A\" or \"FA0.Cout\" to avoid coordinate arithmetic. "
					+ "Logisim splits wires automatically where a third wire meets them. IMPORTANT: never try to "
					+ "produce a wire whose ends differ in both x and y - Logisim cannot iterate such a wire and the "
					+ "whole session then hangs until the Java heap is exhausted. Let this tool route, keep coordinates "
					+ "on the 10 pixel grid and never negative (x and y are 0 or more, since anything above or left of "
					+ "the origin is off the user's canvas), and give an explicit steering point rather than forcing an "
					+ "alignment that the router refuses. A start or end that touches no port and no wire is reported back "
					+ "with the nearest port named, which is usually the coordinate that was meant. For anything longer than "
					+ "a couple of segments, or shared by several components, use a tunnel rather than a long wire.";

	public static final String WIRE_REMOVE = "Removes one wire segment, either by its id (as reported by logisim_circuit_describe) or by giving "
					+ "its exact 'from' and 'to' points, or every segment through a point with 'loc'. Use 'loc' when "
					+ "Logisim has split a wire at a port - which is what happens after a wire has run through one by "
					+ "mistake - because then the endpoints you wrote no longer describe a real segment and only the "
					+ "point still identifies it.";

	public static final String FILE_NEW = "Starts a new, empty Logisim project in memory, with a single circuit called 'main' and the "
					+ "standard component libraries available. Nothing is written to disk until logisim_file_save "
					+ "is called.";

	public static final String FILE_OPEN = "Opens an existing Logisim .circ file with Logisim's own reader. The file on disk is not modified. "
					+ "If a circuit in the file refers to a library that cannot be found, opening fails with an "
					+ "error instead of prompting for a file (this server is headless). This tool refuses to open a "
					+ "file that contains a wire whose two ends differ in both x and y: Logisim cannot load such a "
					+ "file at all (it would hang and then fail with 'Java heap space'), so the reply names the line "
					+ "and the exact axis aligned replacement. For such a file, use logisim_file_recover and then "
					+ "logisim_file_save_as.";

	public static final String FILE_SAVE = "Writes the project to disk using Logisim's own writer, typically overwriting the file it was "
					+ "opened from. Omit 'path' to save back to the file this session was opened from; use "
					+ "logisim_file_save_as to write somewhere else. Any wire whose ends differ in both x and y "
					+ "is repaired into axis aligned segments before writing, because a file containing one "
					+ "could never be opened again; the repair is reported as 'wire_repair' in the reply when it "
					+ "happens.";

	public static final String FILE_RECOVER = "Opens a file that logisim_file_open refuses because it contains wires whose two ends differ in "
					+ "both x and y. Logisim cannot load such a file at all (loading runs its wire repair, which "
					+ "walks every point of every wire, and such a wire can never be walked to its end, so any "
					+ "Logisim process would spin until the Java heap is exhausted). This tool replaces each of "
					+ "those wires with its axis aligned parts while the file is being read, keeping both ends "
					+ "connected, and then hands the result to Logisim's own reader. The file on disk is not "
					+ "modified: inspect what was rewritten in the reply, then call logisim_file_save_as to write "
					+ "the repaired file, which Logisim's own writer produces. Use it only as a rescue; an "
					+ "ordinary logisim_file_open is the normal way to open a file.";

	public static final String FILE_SAVE_AS = "Writes the project to a new path using Logisim's own writer, leaving any previously opened file "
					+ "untouched. After this call the session is associated with the new path.";

	public static final String FILE_CLOSE = "Closes the current project and discards unsaved changes, releasing the circuit from memory. "
					+ "The file on disk is never modified by this call.";

	public static final String FILE_INFO = "Reports which file this session came from, whether it has unsaved changes, and the libraries "
					+ "the project uses.";

	public static final String GUI_STATUS = "What the user can see right now, and what state this session is in: whether the server runs inside "
					+ "a Logisim window (live mode) or headless, the transport and its address, every open window "
					+ "with its file, unsaved-changes flag, current circuit and selection, whether the user has "
					+ "paused the agent or turned on read-only mode, and the tail of the activity log (the same "
					+ "lines the user sees). Call this first in a session, and after anything that suggests the "
					+ "user changed something themselves.";

	public static final String GUI_WINDOWS = "Lists the Logisim windows that are open, with the file each one came from, whether it has unsaved "
					+ "changes, which circuit is on screen, and the current selection. Pass one of the returned "
					+ "titles or paths to logisim_gui_focus to bring that window to the front.";

	public static final String GUI_FOCUS = "Brings a window to the front, and switches it to a circuit, so the user is looking at what the "
					+ "agent is about to change. Use it before any burst of edits: watching a circuit change on "
					+ "screen is the whole point of running the server inside the window. Identify the window by a "
					+ "substring of its title or file path (as listed by logisim_gui_windows); omit it for the "
					+ "window the user is already in.";

	public static final String GUI_SELECT = "Highlights components and wires on screen by putting them into the selection, which is how the user "
					+ "sees what a call is about to touch or has just touched. Every mutating tool does this for "
					+ "the parts it changed; call this to point at something without changing it, for example to "
					+ "show the user the two nets that shorted. Any component reference works: an id (\"c3\"), a "
					+ "label (\"A\"), a port (\"c3.1\") or a location ([150,200]).";

	public static final String GUI_DESELECT = "Clears the selection, so the user sees nothing highlighted any more. Useful before handing control "
					+ "back, or after an inspection that was only meant to point at something.";

	public static final String GUI_READ_ONLY = "Turns read-only mode on or off for this session. The user owns this switch: while it is on, every "
					+ "tool that would change the project is refused with an error saying so, and only inspection "
					+ "and simulation work. The MCP window in Logisim flips the same switch, so an agent that finds "
					+ "editing refused should tell the user rather than fight it.";

	public static final String GUI_PAUSE = "Pauses or resumes the agent. While paused, every tool call is refused with a message that says the "
					+ "user paused it, so the user can inspect or edit the circuit without the agent moving "
					+ "underneath them. Like read-only mode this belongs to the user; it is exposed as a tool so "
					+ "the mechanism is usable before the MCP window exists.";

	public static final String CIRCUIT_NETS = "Groups the wires and component ports of a circuit into nets (the sets of things that are the same "
					+ "electrical node) and reports, for every net, its wires, its length, its ports, its labels and "
					+ "how many of those ports drive it. This is how wiring is diagnosed without seeing the schematic: "
					+ "a port that appears under 'unconnected_ports' touches no wire, so the net it was meant to reach "
					+ "reads 'x' (undriven); a net whose 'drivers' is greater than one reads 'e' (conflicting drivers) "
					+ "because two things push a value onto it at once. Note that a driver is counted by what the "
					+ "component does electrically, not by the direction shown in 'ports': inside the circuit an input pin "
					+ "is the source of its net and an output pin only reads it. Ports that can drive when enabled "
					+ "(tristate, buses) are counted separately as 'bidirectional'. Nets are numbered largest first, so "
					+ "n1 is the most connected one; pass 'net' or 'port' to look at a single node.";

	public static final String STATUS = "Reports the state of the Logisim session: whether a file is open, where it came from, "
					+ "which circuits exist, which circuit is current, and how the simulation currently stands. "
					+ "Call this first if you are unsure what the session looks like.";

	public static final String UNDO = "Undoes the most recent change to the circuit (component or wire edits, attribute changes, "
					+ "circuit creation). Logisim keeps a bounded undo history and has no redo, so an undone "
					+ "change must be redone by repeating it.";

	public static final String CIRCUIT_INFO = "Describes the circuit that is currently being edited or simulated, including its pins.";

	public static final String SIM_RESET = "Resets the simulation of a circuit: all stored signal values, flip-flop and memory contents are "
					+ "thrown away and the circuit starts from its initial (undefined) state. The circuit's "
					+ "components and wires are not touched.";

	public static final String SIM_SET_INPUT = "Drives one input pin to a value and propagates the circuit, so combinational logic settles "
					+ "immediately. Values may be 0, 1, x (unknown), e (error/conflict), z (high impedance), or an "
					+ "integer for a multi-bit pin (decimal, 0x hex, 0b binary). Identify the pin by its label or "
					+ "by its location \"x,y\".";

	public static final String SIM_SET_INPUTS = "Drives several input pins at once and propagates the circuit once at the end, which is the fast and "
					+ "deterministic way to apply a test vector before reading the outputs.";

	public static final String SIM_READ = "Reads the current value of named signals (pin labels) or of specific locations, without changing "
					+ "anything. Values are those from the most recent propagation; call logisim_sim_propagate or "
					+ "logisim_sim_set_input first if the circuit has just been edited.";

	public static final String SIM_READ_ALL = "Reads every pin of a circuit at once and returns the values both as a simple name-to-value map and "
					+ "as a detailed list including each pin's direction, width, position and the value the pin "
					+ "itself is driving. This is the quickest way to see the result of a test vector.";

	public static final String SIM_PROPAGATE = "Settles all combinational logic and returns the resulting pin values. Clocked components do not "
					+ "advance: use logisim_sim_tick for that. If the circuit never settles, 'oscillating' comes "
					+ "back true, which usually means a combinational feedback loop.";

	public static final String SIM_TICK = "Advances the simulation by whole clock cycles: each cycle fires one edge on every Clock component "
					+ "and then settles the logic, which is how sequential circuits (flip-flops, counters, "
					+ "registers, control units) make progress. Returns the pin values after the last cycle.";

	public static final String SIM_STATUS = "Reports how the simulation of a circuit currently stands: how many clock cycles have elapsed, "
					+ "whether the circuit is oscillating (never settling), and the current pin values.";

}
