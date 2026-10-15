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
import java.util.Map;
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
	/** The seven JSON Schema type names, as one comma-separated list to search in. */
	private static final String TYPE_NAMES = "object,array,string,number,integer,boolean,null";

	/**
	 * The keywords this server is willing to publish. Everything here is a keyword every client
	 * this has been tried with accepts; anything else has to be added deliberately, with the client
	 * that was tried written down beside it.
	 */
	private static final String ALLOWED_KEYWORDS = "type,description,properties,required,items,enum,"
			+ "additionalProperties";

	/** The keywords that hold a list of schemas. */
	private static final String[] UNION_KEYWORDS = { "anyOf", "oneOf", "allOf" };

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
			"logisim_circuit_build", "logisim_screenshot", "logisim_circuit_repair_wires",
			"logisim_circuit_appearance", "logisim_circuit_appearance_edit" };

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
		check("schemas", "every tool's argument schema is one even the strictest client accepts", new Body() {
			@Override
			public void run() {
				checkSchemas();
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
		check("sim_determinism", "a simulator that is running is stopped for the call, and one vector that cannot "
				+ "be evaluated costs a row instead of the batch", new Body() {
			@Override
			public void run() throws Exception {
				checkSimulatorDeterminism();
			}
		});
		check("wiring", "a branch that lands on a wire the same call drew is a T-joint, not an endpoint that "
				+ "connects nothing", new Body() {
			@Override
			public void run() throws Exception {
				checkWiring();
			}
		});
		check("splitter", "a splitter's per-bit attributes take, and the reply reports the split that came out",
				new Body() {
					@Override
					public void run() throws Exception {
						checkSplitter();
					}
				});
		check("appearance", "a subcircuit's symbol can be read, arranged, pictured, taken back with one undo "
				+ "and reset to Logisim's own layout", new Body() {
			@Override
			public void run() throws Exception {
				checkAppearance();
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

	/**
	 * Every tool's argument schema, checked for the shape the strictest clients insist on.
	 *
	 * <p>
	 * Claude's API validates the whole tool list before it will use it, and refuses the request
	 * when one schema falls outside its subset of JSON Schema. A tuple at {@code items} - a list
	 * of schemas where JSON Schema allows one - is one such case, and it cost the entire tool
	 * list, with an error that names a tool of ours and nothing else: {@code Invalid schema for
	 * function 'mcp__logisim__logisim_sim_read': [{"type":"integer","description":"coordinate"}] is
	 * not of types "boolean", "object"}. These are the rules that subset implies, checked here
	 * rather than discovered by a user whose client will not connect.
	 * </p>
	 */
	private void checkSchemas() {
		List<String> problems = new ArrayList<String>();
		for (JsonElement element : tools.definitions()) {
			JsonObject definition = element.getAsJsonObject();
			String name = text(definition, "name");
			if (text(definition, "description") == null) {
				problems.add(name + ": no description");
			}
			JsonElement schema = definition.get("inputSchema");
			if (schema == null || !schema.isJsonObject()) {
				problems.add(name + ": inputSchema is not an object");
				continue;
			}
			if (!"object".equals(text(schema.getAsJsonObject(), "type"))) {
				problems.add(name + ": the schema's type is " + Json.compact(schema.getAsJsonObject().get("type"))
						+ ", not \"object\"");
			}
			schemaProblems(schema.getAsJsonObject(), name, problems);
		}
		expect(problems.isEmpty(), problems.size() + " tool schema problem(s), and a client that checks its "
				+ "schemas refuses the whole tool list for any one of them: " + problems);
	}

	/**
	 * Collects what is wrong with one schema and everything below it. A schema is an object or a
	 * boolean, so wherever a schema belongs - a property, {@code items}, an entry of
	 * {@code anyOf} - a list of schemas is a defect however legal JSON Schema considers it;
	 * {@code type} is a type name or a list of them; {@code required} has to name declared
	 * properties; and every property says what it is for, because these descriptions are all an
	 * agent has to go on.
	 */
	private static void schemaProblems(JsonObject schema, String where, List<String> problems) {
		for (Map.Entry<String, JsonElement> entry : schema.entrySet()) {
			if (("," + ALLOWED_KEYWORDS + ",").indexOf("," + entry.getKey() + ",") < 0) {
				problems.add(where + "." + entry.getKey() + ": is a keyword outside the small set every client is "
						+ "known to accept (" + ALLOWED_KEYWORDS + "); a keyword a client does not know costs it the "
						+ "whole tool list");
			}
		}
		JsonElement type = schema.get("type");
		if (type != null) {
			if (type.isJsonArray()) {
				for (JsonElement one : type.getAsJsonArray()) {
					if (!isTypeName(one)) {
						problems.add(where + ": type " + Json.compact(one) + " is not a JSON Schema type name "
								+ "(one of " + TYPE_NAMES + ")");
					}
				}
			} else if (!isTypeName(type)) {
				problems.add(where + ": type is " + Json.compact(type) + ", not a type name or a list of names "
						+ "(one of " + TYPE_NAMES + ")");
			}
		}

		JsonElement items = schema.get("items");
		if (items != null) {
			if (items.isJsonArray()) {
				problems.add(where + ".items: is a list of schemas, which the strictest clients refuse as invalid "
						+ "(write one schema; minItems and maxItems are what fix a length)");
			} else if (items.isJsonObject()) {
				schemaProblems(items.getAsJsonObject(), where + ".items", problems);
			} else {
				problems.add(where + ".items: is " + Json.compact(items) + ", which is not a schema");
			}
		}

		for (int i = 0; i < UNION_KEYWORDS.length; i++) {
			JsonElement options = schema.get(UNION_KEYWORDS[i]);
			if (options == null) {
				continue;
			}
			if (!options.isJsonArray()) {
				problems.add(where + "." + UNION_KEYWORDS[i] + ": is " + Json.compact(options)
						+ ", not a list of schemas");
				continue;
			}
			int index = 0;
			for (JsonElement option : options.getAsJsonArray()) {
				String here = where + "." + UNION_KEYWORDS[i] + "[" + index + "]";
				if (option.isJsonObject()) {
					schemaProblems(option.getAsJsonObject(), here, problems);
				} else {
					problems.add(here + ": is " + Json.compact(option) + ", which is not a schema");
				}
				index++;
			}
		}

		JsonObject properties = schema.get("properties") != null && schema.get("properties").isJsonObject()
				? schema.getAsJsonObject("properties")
				: null;
		JsonElement required = schema.get("required");
		if (required != null) {
			if (!required.isJsonArray()) {
				problems.add(where + ".required: is " + Json.compact(required) + ", not a list of names");
			} else {
				for (JsonElement one : required.getAsJsonArray()) {
					if (!isString(one) || properties == null || properties.get(one.getAsString()) == null) {
						problems.add(where + ".required: names " + Json.compact(one) + ", which the properties do "
								+ "not declare");
					}
				}
			}
		}

		if (properties == null) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
			String name = where + "." + entry.getKey();
			if (!entry.getValue().isJsonObject()) {
				problems.add(name + ": the schema of a property is " + Json.compact(entry.getValue())
						+ ", which is not an object");
				continue;
			}
			if (entry.getValue().getAsJsonObject().get("description") == null) {
				problems.add(name + ": no description");
			}
			schemaProblems(entry.getValue().getAsJsonObject(), name, problems);
		}
	}

	/** True for a JSON Schema type name given as a string. */
	private static boolean isTypeName(JsonElement element) {
		return isString(element) && ("," + TYPE_NAMES + ",").indexOf("," + element.getAsString() + ",") >= 0;
	}

	/** True for a JSON string. */
	private static boolean isString(JsonElement element) {
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
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

	/**
	 * A subcircuit's symbol: reading it, arranging it, looking at it, and taking the whole
	 * arrangement back with the one undo that the tool promises.
	 */
	private void checkAppearance() throws Exception {
		ensureSymbol();
		JsonObject before = call("logisim_circuit_appearance",
				Json.object("circuit", "sym", "include_shapes", Boolean.TRUE));
		expect(before.get("is_default").getAsBoolean(), "a symbol nobody has touched should still be laid out by "
				+ "Logisim itself, but 'is_default' reads " + before.get("is_default"));
		JsonArray ports = array(before, "ports");
		expect(ports != null && ports.size() == 2, "two Pins make two ports in the symbol, but the answer reports "
				+ (ports == null ? "none" : String.valueOf(ports.size())));
		expect("A".equals(text(ports.get(0).getAsJsonObject(), "pin"))
				&& "B".equals(text(ports.get(1).getAsJsonObject(), "pin")),
				"the ports should come back in the order their Pins were placed (A, then B), because that order is "
						+ "what \"<instance>.0\" and \"<instance>.1\" refer to; the answer was "
						+ Json.compact(ports));
		expect(ports.get(0).getAsJsonObject().get("index").getAsInt() == 0
				&& ports.get(1).getAsJsonObject().get("index").getAsInt() == 1,
				"the two ports should be numbered 0 and 1, but they report "
						+ ports.get(0).getAsJsonObject().get("index") + " and "
						+ ports.get(1).getAsJsonObject().get("index"));

		JsonObject edit = call("logisim_circuit_appearance_edit", Json.object("circuit", "sym",
				"move", Json.array(Json.object("pin", "A",
						"to", Json.array(Integer.valueOf(-40), Integer.valueOf(50)))),
				"add", Json.array(
						Json.object("kind", "rect", "at", Json.array(Integer.valueOf(-40), Integer.valueOf(0)),
								"size", Json.array(Integer.valueOf(240), Integer.valueOf(100))),
						Json.object("kind", "text", "at", Json.array(Integer.valueOf(60), Integer.valueOf(40)),
								"text", "logic"))));
		expect(edit.get("changed").getAsInt() >= 3, "moving one port and adding two shapes should report at least "
				+ "three changes; the answer was " + Json.compact(edit));
		expect(!edit.get("is_default").getAsBoolean(), "arranging a symbol by hand takes it out of Logisim's "
				+ "automatic layout, so 'is_default' should be false afterwards");
		expect(edit.get("frozen_note") != null, "arranging a symbol that Logisim was still laying out freezes that "
				+ "arrangement, and the caller has to be told: the box Logisim drew round the automatic ports stays "
				+ "behind as an ordinary shape, wherever the ports have since gone. The answer was "
				+ Json.compact(edit));

		JsonObject after = call("logisim_circuit_appearance", Json.object("circuit", "sym"));
		JsonObject moved = portNamed(after, "A");
		expect(moved != null, "the arranged symbol should still carry a port for pin A; it reports "
				+ Json.compact(array(after, "ports")));
		expect(moved.getAsJsonArray("loc").get(0).getAsInt() == -40
				&& moved.getAsJsonArray("loc").get(1).getAsInt() == 50,
				"pin A's port was moved to (-40, 50), but it reports " + moved.get("loc"));
		expect(hasShape(after, "rect") && hasShape(after, "text"), "the rectangle and the text the call added "
				+ "should both be part of the symbol; it reports " + Json.compact(array(after, "shapes")));

		call("logisim_undo", new JsonObject());
		JsonObject reverted = call("logisim_circuit_appearance",
				Json.object("circuit", "sym", "include_shapes", Boolean.TRUE));
		expect(reverted.get("is_default").getAsBoolean(), "one undo should take the whole call back - a moved port "
				+ "and two shapes at once, as the tool promises - and leave the circuit on Logisim's own layout "
				+ "again, so 'is_default' should be true; it reads " + reverted.get("is_default"));
		expect(!hasShape(reverted, "rect"), "the rectangle that call added should be gone after one undo; the "
				+ "symbol still reports " + Json.compact(array(reverted, "shapes")));

		String png = new File(tempDir, "symbol.png").getAbsolutePath();
		JsonObject shot = call("logisim_screenshot", Json.object("circuit", "sym", "appearance", Boolean.TRUE,
				"scale", Integer.valueOf(2), "path", png));
		JsonObject image = shot.getAsJsonObject("image");
		expect(image != null && image.get("bytes").getAsInt() > 0 && "image/png".equals(text(image, "mimeType")),
				"asking for the symbol as a picture should return a PNG, but the answer was " + Json.compact(shot));
		if (image != null && image.get("bytes").getAsInt() > 0) {
			File file = new File(png);
			expect(file.isFile() && file.length() == image.get("bytes").getAsInt(),
					"the picture should be on disk at the size the answer reported: " + file.length() + " bytes "
							+ "on disk against " + image.get("bytes") + " reported");
			expect(isPng(file), "the file the screenshot wrote should start with the PNG signature");
		}

		JsonObject reset = call("logisim_circuit_appearance_edit",
				Json.object("circuit", "sym", "reset", Boolean.TRUE));
		expect(reset.get("is_default").getAsBoolean(), "a reset hands the symbol back to Logisim's own layout, so "
				+ "'is_default' should be true again; the answer was " + Json.compact(reset));

		// The names are written where the ports will be once this call's own moves have been applied.
		// Nothing is applied until the whole call is collected into one action, so on a symbol Logisim
		// was still laying out they used to be written at the automatic ports' place instead - a heap
		// of letters a few units from the origin, nowhere near the ports they name.
		JsonObject labelled = call("logisim_circuit_appearance_edit", Json.object("circuit", "sym",
				"label_ports", Boolean.TRUE,
				"move", Json.array(
						Json.object("pin", "A", "to", Json.array(Integer.valueOf(0), Integer.valueOf(50))),
						Json.object("pin", "B", "to", Json.array(Integer.valueOf(200), Integer.valueOf(50))))));
		JsonArray labels = array(labelled, "labels");
		expect(labels != null && labels.size() == 2, "labelling a two port symbol should write one text shape per "
				+ "port; the answer reports " + (labels == null ? "none" : String.valueOf(labels.size())));
		JsonObject labelledNow = call("logisim_circuit_appearance", Json.object("circuit", "sym",
				"include_shapes", Boolean.TRUE));
		expect(shapeNear(labelledNow, "text", "A", 10, 50), "pin A's port was moved to (0, 50), so its name "
				+ "belongs just inside the symbol beside it, at (10, 50); the shapes are "
				+ Json.compact(array(labelledNow, "shapes")));
		expect(shapeNear(labelledNow, "text", "B", 190, 50), "pin B's port was moved to (200, 50), so its name "
				+ "belongs just inside the symbol beside it, at (190, 50); the shapes are "
				+ Json.compact(array(labelledNow, "shapes")));

		JsonObject again = call("logisim_circuit_appearance_edit",
				Json.object("circuit", "sym", "label_ports", Boolean.TRUE));
		expect(again.get("changed").getAsInt() == 0 && array(again, "labels") == null,
				"asking for the port labels a second time should add nothing, because a symbol whose names are "
						+ "already written would otherwise collect a second copy of each; the answer was "
						+ Json.compact(again));
		expect(again.get("labels_note") != null, "the second 'label_ports' call should say why it added nothing; "
				+ "the answer was " + Json.compact(again));
	}

	/**
	 * Whether the described symbol holds a text of this content at about this point. A text shape
	 * reports its ink, which moves with its alignment and is empty until it is first painted, so the
	 * point is matched with room for that rather than exactly.
	 */
	private static boolean shapeNear(JsonObject described, String kind, String content, int x, int y) {
		JsonArray shapes = array(described, "shapes");
		if (shapes == null) {
			return false;
		}
		for (JsonElement element : shapes) {
			JsonObject shape = element.getAsJsonObject();
			if (!kind.equals(text(shape, "kind")) || !content.equals(text(shape, "text"))) {
				continue;
			}
			JsonArray bounds = shape.getAsJsonArray("bounds");
			if (bounds != null && Math.abs(bounds.get(0).getAsInt() - x) <= 12
					&& Math.abs(bounds.get(1).getAsInt() - y) <= 12) {
				return true;
			}
		}
		return false;
	}

	/** The described port whose Pin label is this one, or null. */
	private static JsonObject portNamed(JsonObject described, String pin) {
		JsonArray ports = array(described, "ports");
		if (ports == null) {
			return null;
		}
		for (JsonElement element : ports) {
			JsonObject port = element.getAsJsonObject();
			if (pin.equals(text(port, "pin"))) {
				return port;
			}
		}
		return null;
	}

	/** Whether the described symbol holds a shape of this kind. */
	private static boolean hasShape(JsonObject described, String kind) {
		JsonArray shapes = array(described, "shapes");
		if (shapes == null) {
			return false;
		}
		for (JsonElement element : shapes) {
			if (kind.equals(text(element.getAsJsonObject(), "kind"))) {
				return true;
			}
		}
		return false;
	}

	/** The PNG signature, read back from the file rather than trusted from the answer. */
	private static boolean isPng(File file) {
		try {
			byte[] head = java.nio.file.Files.readAllBytes(file.toPath());
			return head.length > 8 && head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
		} catch (Exception e) {
			return false;
		}
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

	/**
	 * A simulator that is running is what an agent meets in live mode: Logisim starts its own
	 * ticker by default and it propagates whatever is dirty, so a tool call propagating at the
	 * same moment puts two threads inside one propagator and corrupts its queue. A call that
	 * propagates therefore has to stop it - and a vector that cannot be evaluated has to cost one
	 * row, not the whole batch, which is what used to happen.
	 */
	private void checkSimulatorDeterminism() throws Exception {
		ensureAdder();
		session.project().getSimulator().setIsRunning(true);
		JsonObject answer = call("logisim_sim_run_vectors", Json.object("circuit", "main",
				"values", Json.object("A", "5", "B", "3"), "expect", Json.object("S", "8")));
		expect(!session.project().getSimulator().isRunning(), "a call that propagates while Logisim's own ticker "
				+ "is running corrupts the propagator's queue, so it has to stop it first; the simulator is still "
				+ "running after the call");
		expect(answer.get("passed").getAsInt() == 1, "the vector should still pass, but the reply was "
				+ Json.compact(answer));
		expect(answer.has("simulator_stopped"), "the reply should say that a running simulator was stopped, or the "
				+ "user is left wondering why the clock stopped: " + Json.compact(answer));

		JsonObject reply = call("logisim_sim_run_vectors", Json.object("circuit", "main", "vectors", Json.array(
				Json.object("values", Json.object("A", "5", "B", "3"), "expect", Json.object("S", "8")),
				Json.object("values", Json.object("A", "1", "B", "1"),
						"expect", Json.object("COUT", "9")))));
		JsonArray rows = array(reply, "rows");
		expect(reply.get("vector_count").getAsInt() == 2 && rows != null && rows.size() == 2, "a vector that cannot "
				+ "be evaluated must keep its own row, and the vectors beside it must still be run: the batch used to "
				+ "be lost entirely, so the caller saw nothing but an exception");
		expect(reply.get("errors") != null && reply.get("errors").getAsInt() == 1, "'COUT' is one bit wide, so "
				+ "expecting 9 of it is that one vector's error; the reply was " + Json.compact(reply));
		expect(reply.get("passed") == null || reply.get("passed").getAsInt() == 1, "the good vector in the same "
				+ "call should still have passed, but the reply was " + Json.compact(reply));
	}

	/**
	 * A branch drawn into a trunk by the same call. That is a T-joint, and the checker used to
	 * judge the endpoint against the wires that existed before the call, so whether it warned
	 * depended on which path came first - and a correct batch came back with "it connects
	 * nothing" against wiring that was already joined.
	 */
	private void checkWiring() throws Exception {
		call("logisim_circuit_create", Json.object("name", "wiring"));
		call("logisim_component_add_many", Json.object("circuit", "wiring", "components", Json.array(
				component("Wiring", "Pin", 100, 200, "A", "{\"facing\":\"east\",\"width\":1}"),
				component("Wiring", "Pin", 500, 200, "Y", "{\"facing\":\"west\",\"width\":1,\"output\":true}"),
				component("Wiring", "Pin", 180, 400, "P", "{\"facing\":\"north\",\"width\":1}"))));
		JsonObject answer = call("logisim_wire_add", Json.object("circuit", "wiring", "paths", Json.array(
				Json.array("A", "Y"),
				Json.array("P", "180,200"))));
		JsonArray warnings = array(answer, "warnings");
		expect(warnings == null || warnings.size() == 0, "a branch that lands on a wire drawn by the same call is "
				+ "connected, so warning about it makes a correct batch look wrong; the reply said "
				+ Json.compact(warnings));
		expect(answer.has("joins"), "the T-joint should be reported as a join, naming the wire this same call drew: "
				+ Json.compact(answer));
		JsonObject nets = call("logisim_circuit_nets", Json.object("circuit", "wiring", "compact", Boolean.TRUE));
		expect(nets.get("net_count").getAsInt() == 1, "A, P and Y should now be one net, but the netlist says "
				+ nets.get("net_count") + ": " + Json.compact(nets));
		call("logisim_circuit_select", Json.object("circuit", "main"));
	}

	/**
	 * A splitter's per-bit attributes are recreated whenever its shape changes, so values put on
	 * the attribute set before the component exists are lost to the default even split; and the
	 * reply now reports the split that came out, which is the only way a caller can see that
	 * 5/27 became 16/16.
	 */
	private void checkSplitter() throws Exception {
		call("logisim_circuit_create", Json.object("name", "split"));
		JsonObject placed = call("logisim_component_add", Json.object("circuit", "split", "lib", "Wiring",
				"type", "Splitter", "x", Integer.valueOf(300), "y", Integer.valueOf(200),
				"attributes", Json.object("incoming", Integer.valueOf(32), "fanout", Integer.valueOf(2),
						"bit_ranges", Json.object("0-4", Integer.valueOf(0), "5-31", Integer.valueOf(1)))));
		JsonObject split = placed.getAsJsonObject("split");
		expect(split != null, "the reply should report the split it produced, not only the bit attributes: "
				+ Json.compact(placed));
		expect(split.get("fanout").getAsInt() == 2, "'0-4' as output 0 and '5-31' as output 1 is a two-way split, "
				+ "but the reply says fanout " + split.get("fanout") + ": " + Json.compact(split));
		JsonArray outputs = array(split, "outputs");
		expect(outputs != null && outputs.size() == 2, "two outputs were asked for: " + Json.compact(split));
		expect("0-4".equals(text(outputs.get(0).getAsJsonObject(), "bits"))
				&& "5-31".equals(text(outputs.get(1).getAsJsonObject(), "bits")),
				"the split should be 5/27 - bits 0-4 on the first output, 5-31 on the second - but the bit "
						+ "attributes came out as " + Json.compact(split));

		JsonObject second = call("logisim_component_add", Json.object("circuit", "split", "lib", "Wiring",
				"type", "Splitter", "x", Integer.valueOf(600), "y", Integer.valueOf(200),
				"attributes", Json.object("bit0", Integer.valueOf(1), "bit1", Integer.valueOf(1),
						"bit2", Integer.valueOf(1), "bit3", Integer.valueOf(2),
						"incoming", Integer.valueOf(4), "fanout", Integer.valueOf(2))));
		JsonObject split2 = second.getAsJsonObject("split");
		JsonArray outputs2 = split2 == null ? null : array(split2, "outputs");
		expect(outputs2 != null && outputs2.size() == 2, "a four bit two-way splitter reports two outputs: "
				+ Json.compact(second));
		expect("0-2".equals(text(outputs2.get(0).getAsJsonObject(), "bits"))
				&& "3".equals(text(outputs2.get(1).getAsJsonObject(), "bits")),
				"bit3 on the second output is a 3+1 split, and the bit values were written before the shape: "
						+ Json.compact(split2));
		call("logisim_circuit_select", Json.object("circuit", "main"));
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

	/** A subcircuit with two Pins in it, whose symbol the appearance check arranges. */
	private void ensureSymbol() throws Exception {
		call("logisim_circuit_create", Json.object("name", "sym"));
		call("logisim_component_add_many", Json.object("circuit", "sym", "components", Json.array(
				component("Wiring", "Pin", 100, 100, "A", "{\"facing\":\"east\",\"width\":1}"),
				component("Wiring", "Pin", 100, 200, "B", "{\"facing\":\"east\",\"width\":1}"))));
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
