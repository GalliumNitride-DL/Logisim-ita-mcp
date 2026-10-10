/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Proves that this server works, in this build and in this packaging.
 *
 * <p>
 * Every failure this project has had was found by hand, with a throwaway script that drove
 * the server and read the answers. Those scripts are gone; this is the same idea kept where
 * it can run again: one call that builds a small circuit of its own in a scratch project,
 * drives it through the same tools an agent uses, and reports each check as pass or fail
 * with the detail needed to see why.
 * </p>
 *
 * <p>
 * Two rules shape it. First, <b>nothing of the caller's is touched</b>: the checks run in a
 * second {@link McpSession}, so the project the agent is working on is not opened, closed,
 * changed or saved. Second, <b>a failing check must not stop the others</b>, because the
 * interesting question is usually how much else is broken - so every check catches its own
 * failure and the report lists them all.
 * </p>
 */
public final class SelfTest {
	/** The tools a build of this server is supposed to have. */
	private static final String[] EXPECTED_TOOLS = { "logisim_status", "logisim_undo", "logisim_circuit_info",
			"logisim_file_new", "logisim_file_open", "logisim_file_recover", "logisim_file_save",
			"logisim_file_save_as", "logisim_file_close", "logisim_file_info", "logisim_library_list",
			"logisim_component_catalog", "logisim_help", "logisim_circuit_list", "logisim_circuit_describe",
			"logisim_circuit_create", "logisim_circuit_delete", "logisim_circuit_set_main",
			"logisim_circuit_select", "logisim_circuit_stats", "logisim_circuit_nets", "logisim_circuit_lint",
			"logisim_component_add", "logisim_component_add_many", "logisim_component_duplicate",
			"logisim_component_remove", "logisim_component_move", "logisim_component_set_attributes",
			"logisim_wire_add", "logisim_wire_remove", "logisim_component_memory", "logisim_sim_reset",
			"logisim_sim_set_input", "logisim_sim_set_inputs", "logisim_sim_read", "logisim_sim_read_all",
			"logisim_sim_propagate", "logisim_sim_tick", "logisim_sim_trace", "logisim_sim_status",
			"logisim_sim_run_vectors", "logisim_analyze_truth_table", "logisim_analyze_expression",
			"logisim_circuit_build", "logisim_screenshot", "logisim_circuit_repair_wires" };

	private final McpSession session = new McpSession();
	private final ToolRegistry tools;
	private final File tempDir;
	private final JsonArray results = new JsonArray();

	private List<String> selected;

	// what the build check produced, so that a later check can use it even when it runs on
	// its own (a subset of checks is allowed to be run)
	private boolean adderBuilt;
	private boolean logicBuilt;
	private boolean lintBuilt;
	private String sumRef;
	private String carryRef;
	private String savedFile;

	private interface Body {
		void run() throws Exception;
	}

	private SelfTest(File tempDir) {
		this.tempDir = tempDir;
		this.tools = McpMain.registry(session, "self-test");
	}

	/** Runs the checks and returns the report; never throws for a failed check. */
	public static JsonObject run(JsonObject options) {
		boolean verbose = flag(options, "verbose", false);
		boolean keepFiles = flag(options, "keep_files", false);
		File tempDir = tempDirectory(options);
		SelfTest test = new SelfTest(tempDir);
		test.selected = names(options);
		long started = System.currentTimeMillis();
		try {
			test.session.newFile();
			test.all();
		} catch (Throwable t) {
			test.results.add(Json.object("name", "setup", "ok", Boolean.FALSE, "what",
					"a scratch project that the checks can build in", "detail", McpSession.message(t)));
		} finally {
			test.session.close();
		}
		JsonObject report = test.report(verbose, System.currentTimeMillis() - started);
		boolean failed = report.get("failed").getAsInt() > 0;
		// A failed run keeps its files by itself: the point of the detail is to be looked at.
		if (!keepFiles && !failed) {
			delete(tempDir);
		} else {
			report.addProperty("kept_files", Boolean.TRUE);
		}
		return report;
	}

	// ------------------------------------------------------------------ the checks

	private void all() {
		check("tools", "every tool this build is supposed to have is registered", new Body() {
			@Override
			public void run() {
				checkTools();
			}
		});
		check("catalogue", "the component catalogue reports what it promises: a splitter's pins, the corrected "
				+ "Carry Out, a probe's width", new Body() {
			@Override
			public void run() throws Exception {
				checkCatalogue();
			}
		});
		check("build", "components and wires can be placed, and an impossible coordinate is refused", new Body() {
			@Override
			public void run() throws Exception {
				checkBuild();
			}
		});
		check("guard", "a negative coordinate, an off-grid point and a circuit inside itself are all refused",
				new Body() {
					@Override
					public void run() throws Exception {
						checkGuards();
					}
				});
		check("dry_run", "a dry run reports what it would do and changes nothing", new Body() {
			@Override
			public void run() throws Exception {
				checkDryRun();
			}
		});
		check("simulate", "an 8 bit adder adds, and its Carry Out is a driver", new Body() {
			@Override
			public void run() throws Exception {
				checkSimulate();
			}
		});
		check("nets", "the netlist agrees that the carry net has exactly one driver", new Body() {
			@Override
			public void run() throws Exception {
				checkNets();
			}
		});
		check("analysis", "the truth table and the minimized expression agree with the circuit that was built",
				new Body() {
					@Override
					public void run() throws Exception {
						checkAnalysis();
					}
				});
		check("lint", "the lint reports a deliberately wrong circuit's faults", new Body() {
			@Override
			public void run() throws Exception {
				checkLint();
			}
		});
		check("memory", "a ROM's contents survive a write, a read back and an undo", new Body() {
			@Override
			public void run() throws Exception {
				checkMemory();
			}
		});
		check("trace", "signals can be recorded over several clock cycles", new Body() {
			@Override
			public void run() throws Exception {
				checkTrace();
			}
		});
		check("save/load", "the project survives being saved and opened again", new Body() {
			@Override
			public void run() throws Exception {
				checkSaveLoad();
			}
		});
		check("recover", "a file containing a non-axis-aligned wire is refused by open and rescued by recover",
				new Body() {
					@Override
					public void run() throws Exception {
						checkRecover();
					}
				});
		check("docs", "the bundled manual can be read, which a jar that lost its 'doc' folder cannot do",
				new Body() {
					@Override
					public void run() throws Exception {
						checkDocs();
					}
				});
	}

	private void checkTools() {
		List<String> missing = new ArrayList<String>();
		for (String name : EXPECTED_TOOLS) {
			if (tools.find(name) == null) {
				missing.add(name);
			}
		}
		expect(missing.isEmpty(), "this build does not register " + missing.size() + " tool(s): " + missing
				+ "; the registered ones number " + tools.size());
	}

	private void checkCatalogue() throws Exception {
		JsonObject splitter = named("logisim_component_catalog",
				Json.object("lib", "Wiring", "name", "Splitter", "verbose", Boolean.TRUE), "Splitter");
		JsonArray ports = array(splitter, "ports");
		expect(ports != null && ports.size() == 5, "a Splitter should report its combined end and four bit ends, "
				+ "so five ports; it reported " + (ports == null ? "none" : String.valueOf(ports.size())));
		int named = 0;
		for (JsonElement port : ports) {
			if (port.getAsJsonObject().has("name")) {
				named++;
			}
		}
		expect(named == ports.size(), "every Splitter port should carry Logisim's own name for it; " + named + " of "
				+ ports.size() + " do");

		JsonObject adder = named("logisim_component_catalog",
				Json.object("lib", "Arithmetic", "name", "Adder", "verbose", Boolean.TRUE), "Adder");
		JsonObject carry = portMatching(adder, "carry out", "carry");
		expect(carry != null, "the Adder should have a port whose name mentions Carry Out; its ports are named "
				+ portNames(adder));
		expect("output".equals(text(carry, "direction")),
				"the Adder's Carry Out should be reported as an output - Logisim declares it as an input "
						+ "(Adder.java:90) and PortFixups corrects it - but it came back as '"
						+ text(carry, "direction") + "'");
		expect(carry.has("direction_note"),
				"the corrected direction should say so in 'direction_note', or the difference is invisible");

		JsonObject probe = named("logisim_component_catalog",
				Json.object("lib", "Wiring", "name", "Probe", "verbose", Boolean.TRUE), "Probe");
		JsonArray probePorts = array(probe, "ports");
		expect(probePorts != null && probePorts.size() > 0, "a Probe should report one port");
		expect(probePorts.get(0).getAsJsonObject().has("width_note"),
				"a Probe's port takes the width of whatever it is attached to, so it should report 'width_note' "
						+ "rather than a width of 0");
	}

	private void checkBuild() throws Exception {
		ensureAdder();
	}

	private void checkGuards() throws Exception {
		ensureAdder();
		String negative = refusal("logisim_component_add",
				Json.object("lib", "Wiring", "type", "Tunnel", "x", Integer.valueOf(-10), "y", Integer.valueOf(100)));
		expect(negative.toLowerCase().contains("negative"),
				"placing a component at a negative coordinate should be refused as off canvas, but the answer was: "
						+ negative);

		String offGrid = refusal("logisim_component_add", Json.object("lib", "Wiring", "type", "Tunnel",
				"x", Integer.valueOf(725), "y", Integer.valueOf(725), "on_off_grid", "fail"));
		expect(offGrid.toLowerCase().contains("grid"), "an off-grid coordinate should be refused when asked, but "
				+ "the answer was: " + offGrid);

		String circular = refusal("logisim_component_add",
				Json.object("lib", "Circuits", "type", "main", "x", Integer.valueOf(900), "y", Integer.valueOf(900)));
		expect(circular.toLowerCase().contains("itself") || circular.toLowerCase().contains("circular"),
				"placing a circuit inside itself should be refused, but the answer was: " + circular);
	}

	private void checkDryRun() throws Exception {
		ensureAdder();
		JsonObject before = call("logisim_circuit_stats", Json.object("circuit", "main"));
		call("logisim_component_add", Json.object("lib", "Wiring", "type", "Tunnel", "x", Integer.valueOf(900),
				"y", Integer.valueOf(900), "dry_run", Boolean.TRUE));
		call("logisim_wire_add", Json.object("points",
				Json.array("900,900", "1000,900"), "dry_run", Boolean.TRUE));
		JsonObject after = call("logisim_circuit_stats", Json.object("circuit", "main"));
		expect(String.valueOf(before.get("counts")).equals(String.valueOf(after.get("counts"))),
				"a dry run must not change the circuit, but the component counts went from " + before.get("counts")
						+ " to " + after.get("counts"));
	}

	private void checkSimulate() throws Exception {
		ensureAdder();
		JsonObject answer = call("logisim_sim_run_vectors", Json.object("circuit", "main", "vectors", Json.array(
				vector("5+7", "5", "7", "12", "0"),
				vector("255+1", "255", "1", "0", "1"),
				vector("255+255", "255", "255", "254", "1")),
				"include_signals", Boolean.FALSE));
		expect(answer.get("failed").getAsInt() == 0, "the adder's vectors should all pass, but the reply was "
				+ Json.compact(answer));
		expect(answer.get("passed").getAsInt() == 3, "three vectors were given, so three should pass");
	}

	private void checkNets() throws Exception {
		ensureAdder();
		JsonObject nets = call("logisim_circuit_nets",
				Json.object("circuit", "main", "compact", Boolean.TRUE));
		JsonArray found = array(nets, "nets");
		expect(found != null, "the netlist should report nets");
		int drivers = -1;
		for (JsonElement element : found) {
			JsonObject net = element.getAsJsonObject();
			JsonArray refs = array(net, "port_refs");
			if (refs == null || carryRef == null) {
				continue;
			}
			for (JsonElement ref : refs) {
				if (carryRef.equals(ref.getAsString())) {
					drivers = net.get("drivers").getAsInt();
				}
			}
		}
		expect(drivers >= 0, "the carry port " + carryRef + " should appear on a net");
		expect(drivers == 1, "the carry net should have exactly one driver (the Adder's Carry Out, which Logisim "
				+ "declares as an input); it reported " + drivers);
	}

	private void checkAnalysis() throws Exception {
		ensureLogic();
		JsonObject table = call("logisim_analyze_truth_table",
				Json.object("circuit", "logic", "include_rows", Boolean.TRUE));
		expect(table.get("row_count").getAsInt() == 4, "two input pins make four rows, not "
				+ table.get("row_count").getAsInt());
		expectXorRows(table, "logic");
		JsonObject expression = call("logisim_analyze_expression", Json.object("circuit", "logic"));
		String text = Json.compact(expression);
		expect(text.contains("A") && text.contains("B"), "the expression for an XOR of A and B should mention both "
				+ "inputs; it read " + text);
	}

	private void checkLint() throws Exception {
		ensureLintCircuit();
		JsonObject lint = call("logisim_circuit_lint", Json.object("circuit", "lint"));
		JsonArray findings = array(lint, "findings");
		expect(findings != null, "the lint should report a list of findings, even when it is empty");
		boolean defaultLabel = false;
		boolean unconnected = false;
		for (JsonElement element : findings) {
			String kind = text(element.getAsJsonObject(), "kind");
			if ("tunnel_label_default".equals(kind)) {
				defaultLabel = true;
			}
			if ("unconnected_port".equals(kind)) {
				unconnected = true;
			}
		}
		expect(defaultLabel, "a tunnel left on Logisim's default label should be reported as tunnel_label_default; "
				+ "the lint said " + Json.compact(findings));
		expect(unconnected, "a port that touches no wire should be reported as unconnected_port");
	}

	private void checkMemory() throws Exception {
		ensureAdder();
		JsonObject write = call("logisim_component_memory", Json.object("circuit", "main", "component", "rom",
				"action", "write", "words", Json.array("0x12", "0x34", "0xff")));
		expect(write.get("changed").getAsInt() == 3, "three words were written, so three should change; the reply "
				+ "was " + Json.compact(write));
		JsonObject read = call("logisim_component_memory",
				Json.object("circuit", "main", "component", "rom", "action", "read", "limit", Integer.valueOf(4)));
		JsonObject values = read.getAsJsonObject("values");
		expect("0x12".equals(text(values, "0")) && "0x34".equals(text(values, "1")) && "0xff".equals(text(values, "2")),
				"the ROM should read back what was written, but it reads " + Json.compact(values));
		call("logisim_undo", new JsonObject());
		JsonObject after = call("logisim_component_memory",
				Json.object("circuit", "main", "component", "rom", "action", "read", "limit", Integer.valueOf(2)));
		expect("0x00".equals(text(after.getAsJsonObject("values"), "0")),
				"a ROM write is an ordinary undoable edit, so logisim_undo should have put the first word back to "
						+ "0x00; it reads " + Json.compact(after.getAsJsonObject("values")));
	}

	private void checkTrace() throws Exception {
		ensureCounter();
		JsonObject trace = call("logisim_sim_trace", Json.object("circuit", "clocked", "signals",
				Json.array("Q"), "cycles", Integer.valueOf(6), "only_changes", Boolean.TRUE));
		JsonArray rows = array(trace, "rows");
		expect(rows != null && rows.size() >= 3, "a toggling flip-flop should change several times over six "
				+ "cycles; the trace reported " + (rows == null ? "no rows" : String.valueOf(rows.size()) + " rows"));
		expect(trace.get("changes").getAsInt() >= 2, "a flip-flop whose complement feeds its data input should "
				+ "change at least twice in six cycles, but 'changes' reads " + trace.get("changes").getAsInt());
	}

	private void checkSaveLoad() throws Exception {
		ensureAdder();
		ensureLogic();
		savedFile = new File(tempDir, "selftest.circ").getAbsolutePath();
		call("logisim_file_save", Json.object("path", savedFile));
		JsonObject before = circuitCounts();
		call("logisim_file_close", new JsonObject());
		call("logisim_file_open", Json.object("path", savedFile));
		JsonObject after = circuitCounts();
		expect(Json.compact(before).equals(Json.compact(after)), "the circuits should be the same after a save and "
				+ "reopen: before " + Json.compact(before) + ", after " + Json.compact(after));
		JsonObject table = call("logisim_analyze_truth_table",
				Json.object("circuit", "logic", "include_rows", Boolean.TRUE));
		expectXorRows(table, "logic (reopened)");
	}

	private void checkRecover() throws Exception {
		ensureAdder();
		expect(savedFile != null, "the save/load check has to run before this one, so that there is a file to damage");
		String damagedFile = new File(tempDir, "damaged.circ").getAbsolutePath();
		String text = read(new File(savedFile));
		Matcher wire = Pattern.compile("<wire from=\"\\((\\d+),(\\d+)\\)\" to=\"\\((\\d+),\\2\\)\"/>").matcher(text);
		expect(wire.find(), "the saved file should contain an axis aligned wire to damage, and it does not");
		int y = Integer.parseInt(wire.group(2));
		String broken = text.substring(0, wire.start()) + "<wire from=\"(" + wire.group(1) + "," + y + ")\" to=\"("
				+ wire.group(3) + "," + (y + 10) + ")\"/>" + text.substring(wire.end());
		write(new File(damagedFile), broken);

		String answer = refusal("logisim_file_open", Json.object("path", damagedFile));
		expect(answer.contains("axis aligned"), "opening a file with a non-axis-aligned wire must be refused with "
				+ "the reason, or it would hang and die with 'Java heap space'; the answer was: " + answer);
		JsonObject recovered = call("logisim_file_recover", Json.object("path", damagedFile));
		expect(recovered.has("circuits") || recovered.has("recovered") || recovered.has("status"),
				"logisim_file_recover should report what it rescued; it answered " + Json.compact(recovered));
		JsonObject described = call("logisim_circuit_describe",
				Json.object("circuit", "main", "include_wires", Boolean.FALSE));
		expect(described.get("skewed_wire_count").getAsInt() == 0,
				"after a recovery no circuit may still hold a wire Logisim cannot iterate, but 'main' reports "
						+ described.get("skewed_wire_count").getAsInt());
	}

	private void checkDocs() throws Exception {
		JsonObject page = call("logisim_help", Json.object("topic", "libs/mem/flipflops.html"));
		expect(page.has("docs_source"), "logisim_help should say where the manual came from");
		expect(longestText(page) > 500, "a manual page should come back with its text; the longest string in the "
				+ "reply is " + longestText(page) + " characters, so the documentation is not readable in this build");
	}

	// ------------------------------------------------------------------ building the fixtures

	/** A two input adder with its sum and its carry brought out to pins. */
	private void ensureAdder() throws Exception {
		if (adderBuilt) {
			return;
		}
		adderBuilt = true;
		call("logisim_component_add_many", Json.object("components", Json.array(
				component("Wiring", "Pin", 100, 100, "A", "{\"facing\":\"east\",\"width\":8}"),
				component("Wiring", "Pin", 100, 300, "B", "{\"facing\":\"east\",\"width\":8}"),
				component("Arithmetic", "Adder", 300, 200, null, "{\"width\":8}"),
				component("Wiring", "Pin", 500, 200, "S", "{\"facing\":\"west\",\"width\":8,\"output\":true}"),
				component("Wiring", "Pin", 520, 300, "COUT",
						"{\"facing\":\"west\",\"width\":1,\"output\":true}"),
				component("Memory", "ROM", 800, 200, "rom", "{\"addrWidth\":4,\"dataWidth\":8}")),
				"include_ports", Boolean.TRUE));

		JsonObject described = call("logisim_circuit_describe",
				Json.object("circuit", "main", "include_wires", Boolean.FALSE));
		JsonObject adder = componentOfType(described, "Adder");
		expect(adder != null, "the Adder that was just placed should be in the circuit description");
		JsonObject sum = portMatching(adder, "sum");
		JsonObject carry = portMatching(adder, "carry out", "carry");
		expect(sum != null && carry != null, "the Adder should have ports named for its sum and its carry; it "
				+ "reports " + portNames(adder));
		sumRef = text(sum, "ref");
		carryRef = text(carry, "ref");
		String firstInput = text(portAt(adder, 0), "ref");
		String secondInput = text(portAt(adder, 1), "ref");

		call("logisim_wire_add", Json.object("circuit", "main", "paths", Json.array(
				Json.array("A", firstInput),
				Json.array("B", secondInput),
				Json.array(sumRef, "S"),
				Json.array(carryRef, "COUT"))));
	}

	/** A two input XOR with pins, on its own circuit, for the analysis checks. */
	private void ensureLogic() throws Exception {
		if (logicBuilt) {
			return;
		}
		logicBuilt = true;
		call("logisim_circuit_create", Json.object("name", "logic"));
		call("logisim_component_add_many", Json.object("circuit", "logic", "components", Json.array(
				component("Wiring", "Pin", 100, 100, "A", "{\"facing\":\"east\",\"width\":1}"),
				component("Wiring", "Pin", 100, 200, "B", "{\"facing\":\"east\",\"width\":1}"),
				component("Gates", "XOR Gate", 300, 150, null, "{\"width\":1}"),
				component("Wiring", "Pin", 500, 150, "S", "{\"facing\":\"west\",\"width\":1,\"output\":true}"))));
		JsonObject described = call("logisim_circuit_describe",
				Json.object("circuit", "logic", "include_wires", Boolean.FALSE));
		JsonObject gate = componentOfType(described, "XOR Gate");
		expect(gate != null, "the XOR gate that was just placed should be in the circuit description");
		JsonArray ports = array(gate, "ports");
		expect(ports != null && ports.size() >= 3, "a two input gate has three ports; this one reports "
				+ (ports == null ? "none" : String.valueOf(ports.size())));
		// Logisim's gates declare their ports as the output first, then the inputs top to bottom
		call("logisim_wire_add", Json.object("circuit", "logic", "paths", Json.array(
				Json.array("A", text(ports.get(1).getAsJsonObject(), "ref")),
				Json.array("B", text(ports.get(2).getAsJsonObject(), "ref")),
				Json.array(text(ports.get(0).getAsJsonObject(), "ref"), "S"))));
		call("logisim_circuit_select", Json.object("circuit", "main"));
	}

	/** A circuit that is wrong on purpose, for the lint. */
	private void ensureLintCircuit() throws Exception {
		if (lintBuilt) {
			return;
		}
		lintBuilt = true;
		call("logisim_circuit_create", Json.object("name", "lint"));
		call("logisim_component_add", Json.object("circuit", "lint", "lib", "Wiring", "type", "Tunnel",
				"x", Integer.valueOf(300), "y", Integer.valueOf(200)));
		call("logisim_circuit_select", Json.object("circuit", "main"));
	}

	/** A flip-flop wired to toggle, for the trace. */
	private void ensureCounter() throws Exception {
		call("logisim_circuit_create", Json.object("name", "clocked"));
		call("logisim_component_add_many", Json.object("circuit", "clocked", "components", Json.array(
				component("Memory", "D Flip-Flop", 300, 200, null, null),
				component("Wiring", "Clock", 150, 330, null, null),
				component("Wiring", "Pin", 500, 200, "Q", "{\"facing\":\"west\",\"width\":1,\"output\":true}"))));
		JsonObject described = call("logisim_circuit_describe",
				Json.object("circuit", "clocked", "include_wires", Boolean.FALSE));
		JsonObject flipFlop = componentOfType(described, "D Flip-Flop");
		JsonObject clock = componentOfType(described, "Clock");
		expect(flipFlop != null && clock != null, "the flip-flop and the clock should both be in the circuit");
		// Logisim names the state, the complement and the clock, but not the data input: the
		// catalogue is explicit about that (no name and no ref_by_name for it), so the data
		// input is the one port that has to be taken by index. Port 0 is D, and the trace below
		// is what proves it: a flip-flop whose complement reaches its data input toggles.
		JsonObject state = portMatching(flipFlop, "flip-flop state");
		JsonObject complement = portMatching(flipFlop, "complement");
		JsonObject trigger = portMatching(flipFlop, "clock");
		JsonObject data = portAt(flipFlop, 0);
		expect(state != null && complement != null && trigger != null && data != null,
				"a D flip-flop should report its state, its complement, its clock and a port 0; it reports "
						+ portNames(flipFlop));
		expect(!text(state, "ref").equals(text(complement, "ref"))
				&& !text(data, "ref").equals(text(state, "ref")),
				"the state, the complement and the data input have to be three different ports; the names in this "
						+ "build resolved to " + portNames(flipFlop));
		call("logisim_wire_add", Json.object("circuit", "clocked", "paths", Json.array(
				Json.array(text(portAt(clock, 0), "ref"), text(trigger, "ref")),
				Json.array(text(complement, "ref"), text(data, "ref")),
				Json.array(text(state, "ref"), "Q"))));
		call("logisim_circuit_select", Json.object("circuit", "main"));
	}

	/** One entry of logisim_component_add_many's 'components' list. */
	private static JsonObject component(String lib, String type, int x, int y, String label, String attributes) {
		JsonObject ret = new JsonObject();
		ret.addProperty("lib", lib);
		ret.addProperty("type", type);
		ret.addProperty("x", Integer.valueOf(x));
		ret.addProperty("y", Integer.valueOf(y));
		if (label != null) {
			ret.addProperty("label", label);
		}
		if (attributes != null) {
			ret.add("attributes", Json.parse(attributes).getAsJsonObject());
		}
		return ret;
	}

	/** One logisim_sim_run_vectors entry for the adder. */
	private static JsonObject vector(String name, String a, String b, String sum, String carry) {
		JsonObject values = new JsonObject();
		values.addProperty("A", a);
		values.addProperty("B", b);
		JsonObject expect = new JsonObject();
		expect.addProperty("S", sum);
		expect.addProperty("COUT", carry);
		JsonObject ret = new JsonObject();
		ret.addProperty("name", name);
		ret.add("values", values);
		ret.add("expect", expect);
		return ret;
	}

	/** Checks a truth table of the two input XOR: A and B in, S = A xor B. */
	private static void expectXorRows(JsonObject table, String what) {
		JsonArray rows = array(table, "rows");
		expect(rows != null && rows.size() > 0, what + ": the truth table should include its rows");
		int checked = 0;
		for (JsonElement element : rows) {
			JsonObject row = element.getAsJsonObject();
			String a = value(row, "A");
			String b = value(row, "B");
			if (a == null || b == null) {
				continue;
			}
			String want = a.equals(b) ? "0" : "1";
			expect(want.equals(value(row, "S")), what + ": for A=" + a + " and B=" + b + " an XOR should read S="
					+ want + ", but the table says S=" + value(row, "S"));
			checked++;
		}
		expect(checked == 4, what + ": four input combinations should be readable, and " + checked + " were");
	}

	// ------------------------------------------------------------------ plumbing

	private void check(String name, String what, Body body) {
		if (selected != null && !selected.contains(name)) {
			return;
		}
		long started = System.currentTimeMillis();
		JsonObject result = Json.object("name", name, "what", what);
		try {
			body.run();
			result.addProperty("ok", Boolean.TRUE);
		} catch (Throwable t) {
			result.addProperty("ok", Boolean.FALSE);
			result.addProperty("detail", McpSession.message(t));
		}
		result.addProperty("ms", Long.valueOf(System.currentTimeMillis() - started));
		results.add(result);
	}

	private JsonObject call(String tool, JsonObject arguments) throws Exception {
		McpTool target = tools.find(tool);
		if (target == null) {
			throw new McpException("this build does not register '" + tool + "'");
		}
		JsonElement result = target.call(new ToolContext(session, tool, arguments));
		if (result == null || !result.isJsonObject()) {
			throw new McpException(tool + " returned no JSON object");
		}
		return result.getAsJsonObject();
	}

	/** Calls a tool that must refuse, and returns why it did. */
	private String refusal(String tool, JsonObject arguments) throws Exception {
		try {
			call(tool, arguments);
		} catch (McpException e) {
			return e.getMessage();
		}
		throw new McpException(tool + " should have refused this call, and it did not");
	}

	private static void expect(boolean condition, String message) {
		if (!condition) {
			throw new McpException(message);
		}
	}

	/** The component of a catalogue query whose name is this one, or the first match as a fallback. */
	private JsonObject named(String tool, JsonObject arguments, String wanted) throws Exception {
		JsonObject answer = call(tool, arguments);
		JsonArray components = array(answer, "components");
		expect(components != null && components.size() > 0, tool + " found nothing for " + arguments.get("name"));
		for (JsonElement element : components) {
			JsonObject item = element.getAsJsonObject();
			if (wanted.equals(text(item, "name"))) {
				return item;
			}
		}
		return components.get(0).getAsJsonObject();
	}

	private static JsonObject componentOfType(JsonObject described, String type) {
		JsonArray components = array(described, "components");
		if (components == null) {
			return null;
		}
		for (JsonElement element : components) {
			JsonObject item = element.getAsJsonObject();
			if (type.equals(text(item, "type"))) {
				return item;
			}
		}
		return null;
	}

	/** The first port whose library name mentions one of these texts, tried in order ("carry out" before "carry"). */
	private static JsonObject portMatching(JsonObject component, String... needles) {
		JsonArray ports = array(component, "ports");
		if (ports == null) {
			return null;
		}
		for (String needle : needles) {
			String wanted = needle.toLowerCase();
			for (JsonElement element : ports) {
				JsonObject port = element.getAsJsonObject();
				String name = text(port, "name");
				if (name != null && name.toLowerCase().contains(wanted)) {
					return port;
				}
			}
		}
		return null;
	}

	private static JsonObject portAt(JsonObject component, int index) {
		JsonArray ports = array(component, "ports");
		return ports == null || index >= ports.size() ? null : ports.get(index).getAsJsonObject();
	}

	private static String portNames(JsonObject component) {
		JsonArray ports = array(component, "ports");
		StringBuilder buf = new StringBuilder();
		if (ports != null) {
			for (JsonElement element : ports) {
				JsonObject port = element.getAsJsonObject();
				if (buf.length() > 0) {
					buf.append(", ");
				}
				buf.append(text(port, "index")).append('=').append(text(port, "name"));
			}
		}
		return buf.toString();
	}

	/** The counts of every circuit, as a comparable object. */
	private JsonObject circuitCounts() throws Exception {
		JsonObject listed = call("logisim_circuit_list", new JsonObject());
		JsonObject ret = new JsonObject();
		JsonArray circuits = array(listed, "circuits");
		if (circuits != null) {
			for (JsonElement element : circuits) {
				JsonObject circuit = element.getAsJsonObject();
				ret.add(circuit.get("name").getAsString(), Json.object("components",
						circuit.get("component_count"), "wires", circuit.get("wire_count")));
			}
		}
		return ret;
	}

	private static JsonArray array(JsonObject object, String name) {
		if (object == null) {
			return null;
		}
		JsonElement element = object.get(name);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
	}

	/** A field as text, or null when it is missing or is not a primitive. */
	private static String text(JsonObject object, String name) {
		if (object == null) {
			return null;
		}
		JsonElement element = object.get(name);
		return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
	}

	/** The value of one row's column, whether it was reported as a string or as a bit. */
	private static String value(JsonObject row, String column) {
		return text(row, column);
	}

	/** The longest string anywhere in a reply, used to tell a real manual page from a stub. */
	private static int longestText(JsonElement element) {
		if (element == null) {
			return 0;
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			return element.getAsString().length();
		}
		int best = 0;
		if (element.isJsonObject()) {
			for (java.util.Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				best = Math.max(best, longestText(entry.getValue()));
			}
		} else if (element.isJsonArray()) {
			for (JsonElement item : element.getAsJsonArray()) {
				best = Math.max(best, longestText(item));
			}
		}
		return best;
	}

	private JsonObject report(boolean verbose, long elapsedMs) {
		JsonArray checks = new JsonArray();
		JsonArray failedNames = new JsonArray();
		int passed = 0;
		int failed = 0;
		for (JsonElement element : results) {
			JsonObject item = element.getAsJsonObject();
			boolean ok = item.get("ok").getAsBoolean();
			if (ok) {
				passed++;
			} else {
				failed++;
				failedNames.add(item.get("name").getAsString());
			}
			if (verbose || !ok) {
				checks.add(item);
			}
		}
		JsonObject ret = new JsonObject();
		ret.addProperty("ok", Boolean.valueOf(failed == 0));
		ret.addProperty("check_count", Integer.valueOf(results.size()));
		ret.addProperty("passed", Integer.valueOf(passed));
		ret.addProperty("failed", Integer.valueOf(failed));
		if (failed > 0) {
			ret.add("failed_checks", failedNames);
		}
		ret.add("checks", checks);
		ret.addProperty("elapsed_ms", Long.valueOf(elapsedMs));
		ret.addProperty("tools_available", Integer.valueOf(tools.size()));
		ret.addProperty("temp_directory", tempDir.getAbsolutePath());
		ret.addProperty("note", failed > 0
				? "the failing checks carry the detail of what went wrong, and their scratch project has been kept "
						+ "in 'temp_directory' to be looked at; nothing of the open project was touched"
				: (verbose ? "every check passed" : "every check passed; pass 'verbose' to see them, or 'checks' to "
						+ "run a subset"));
		return ret;
	}

	// ------------------------------------------------------------------ the scratch area

	private static File tempDirectory(JsonObject options) {
		String requested = Json.stringOrNull(options, "temp_dir");
		File base = requested != null && !requested.trim().isEmpty()
				? new File(requested.trim())
				: new File(System.getProperty("java.io.tmpdir", "."));
		File ret = new File(base, "logisim-mcp-selftest-" + System.nanoTime());
		ret.mkdirs();
		return ret;
	}

	private static List<String> names(JsonObject options) {
		JsonElement element = options == null ? null : options.get("checks");
		if (element == null || !element.isJsonArray()) {
			return null;
		}
		List<String> ret = new ArrayList<String>();
		for (JsonElement item : element.getAsJsonArray()) {
			ret.add(item.getAsString());
		}
		return ret;
	}

	private static boolean flag(JsonObject options, String name, boolean fallback) {
		JsonElement element = options == null ? null : options.get(name);
		return element == null || !element.isJsonPrimitive() ? fallback : element.getAsBoolean();
	}

	private static String read(File file) throws Exception {
		Reader in = new InputStreamReader(new FileInputStream(file), "UTF-8");
		try {
			StringBuilder buf = new StringBuilder();
			char[] chunk = new char[8192];
			int got;
			while ((got = in.read(chunk)) > 0) {
				buf.append(chunk, 0, got);
			}
			return buf.toString();
		} finally {
			in.close();
		}
	}

	private static void write(File file, String text) throws Exception {
		Writer out = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
		try {
			out.write(text);
		} finally {
			out.close();
		}
	}

	private static void delete(File file) {
		try {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					delete(child);
				}
			}
			file.delete();
		} catch (Throwable t) {
			// leaving a scratch directory behind is not worth reporting
		}
	}
}
