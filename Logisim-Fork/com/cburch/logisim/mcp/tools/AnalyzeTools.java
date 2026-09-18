/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.util.ArrayList;
import java.util.List;

import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Entry;
import com.cburch.logisim.analyze.model.Expression;
import com.cburch.logisim.analyze.model.Implicant;
import com.cburch.logisim.analyze.model.OutputExpressions;
import com.cburch.logisim.analyze.model.TruthTable;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.mcp.Json;
import com.cburch.logisim.mcp.McpException;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;
import com.cburch.logisim.std.gates.CircuitBuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Behavioural analysis. The truth table is produced by Logisim's own
 * {@link Analyze#computeTable}, the very same code behind Logisim's "Analyze
 * Circuit" window, so the semantics (including how unknown and oscillating
 * results are reported) are identical to what a Logisim user would see.
 */
public final class AnalyzeTools {
	private static final int DEFAULT_MAX_INPUTS = 10;
	private static final int HARD_MAX_INPUTS = 16;

	private AnalyzeTools() {
	}

	public static McpTool truthTable(final McpSession session) {
		return new AbstractTool("logisim_analyze_truth_table",
				"Computes the complete truth table of a combinational circuit by driving every input pin through "
						+ "every combination and reading every output pin, using Logisim's own analysis code. This is "
						+ "the strongest way to verify a circuit an agent has just built. Cell values are 0, 1, x "
						+ "(unknown/floating) or e (error/conflicting drivers); an oscillating circuit reports "
						+ "'!!' for the affected rows. The number of rows is 2^inputs, so keep 'max_inputs' modest.",
				Schemas.object()
						.opt("circuit", Schemas.string("Circuit to analyse; defaults to the current circuit"))
						.opt("max_inputs", Schemas.integer("Refuse to run when the circuit has more input pins than "
								+ "this (default " + DEFAULT_MAX_INPUTS + ", maximum " + HARD_MAX_INPUTS + ")"))
						.opt("include_rows", Schemas.bool("Include the per-row table (default true)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				int maxInputs = ctx.optInt("max_inputs", Integer.valueOf(DEFAULT_MAX_INPUTS)).intValue();
				if (maxInputs < 1 || maxInputs > HARD_MAX_INPUTS) {
					throw new McpException("'max_inputs' must be between 1 and " + HARD_MAX_INPUTS);
				}

				List<Instance> inputs = session.inputPins(circuit);
				List<Instance> outputs = session.outputPins(circuit);
				if (inputs.isEmpty()) {
					throw new McpException("circuit '" + circuit.getName() + "' has no input pins, so it has no "
							+ "truth table; add Pins from the Wiring library to expose its inputs");
				}
				if (outputs.isEmpty()) {
					throw new McpException("circuit '" + circuit.getName() + "' has no output pins, so there is "
							+ "nothing to tabulate; add output Pins (attribute output = true)");
				}
				if (inputs.size() > maxInputs) {
					throw new McpException("circuit '" + circuit.getName() + "' has " + inputs.size() + " input pins, "
							+ "which would need " + (1 << Math.min(30, inputs.size())) + " rows; raise 'max_inputs' "
							+ "up to " + HARD_MAX_INPUTS + " only if you really need it");
				}

				List<String> notes = new ArrayList<String>();
				for (Instance pin : session.pins(circuit)) {
					BitWidth width = pin.getAttributeValue(StdAttr.WIDTH);
					if (width != null && width.getWidth() > 1) {
						notes.add("pin '" + session.pinName(pin) + "' is " + width.getWidth()
								+ " bits wide but Logisim's analysis treats every pin as a single bit");
						break;
					}
				}

				AnalyzerModel model = analyze(session, circuit);

				TruthTable table = model.getTruthTable();
				int rows = table.getRowCount();
				int inputColumns = table.getInputColumnCount();
				int outputColumns = table.getOutputColumnCount();
				if (rows > 1024) {
					notes.add("this run simulated " + rows + " input combinations and may take a while");
				}

				JsonArray inputHeaders = new JsonArray();
				for (int i = 0; i < inputColumns; i++) {
					inputHeaders.add(table.getInputHeader(i));
				}
				JsonArray outputHeaders = new JsonArray();
				for (int i = 0; i < outputColumns; i++) {
					outputHeaders.add(table.getOutputHeader(i));
				}

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.add("inputs", inputHeaders);
				ret.add("outputs", outputHeaders);
				ret.addProperty("row_count", rows);

				if (ctx.optBool("include_rows", true)) {
					boolean oscillating = false;
					JsonArray rowArray = new JsonArray();
					for (int row = 0; row < rows; row++) {
						JsonObject item = new JsonObject();
						for (int col = 0; col < inputColumns; col++) {
							item.addProperty(table.getInputHeader(col),
									entryText(table.getInputEntry(row, col)));
						}
						for (int col = 0; col < outputColumns; col++) {
							Entry value = table.getOutputColumn(col)[row];
							if (value == Entry.OSCILLATE_ERROR) {
								oscillating = true;
							}
							item.addProperty(table.getOutputHeader(col), entryText(value));
						}
						rowArray.add(item);
					}
					ret.add("rows", rowArray);
					ret.addProperty("oscillating", Boolean.valueOf(oscillating));
				}
				if (!notes.isEmpty()) {
					ret.add("notes", Json.array(notes));
				}
				return ret;
			}
		};
	}

	/**
	 * Canonical pin order, top to bottom and then left to right. This is the order
	 * Logisim itself uses when it builds a truth table, so the tool output matches
	 * what a Logisim user sees in the Analyze Circuit window.
	 */
	static java.util.SortedMap<Instance, String> labelsOf(McpSession session, Circuit circuit) {
		java.util.SortedMap<Instance, String> labels = new java.util.TreeMap<Instance, String>(
				new java.util.Comparator<Instance>() {
					@Override
					public int compare(Instance a, Instance b) {
						int ay = a.getLocation().getY();
						int by = b.getLocation().getY();
						if (ay != by) {
							return ay - by;
						}
						int ax = a.getLocation().getX();
						int bx = b.getLocation().getX();
						if (ax != bx) {
							return ax - bx;
						}
						return System.identityHashCode(a) - System.identityHashCode(b);
					}
				});
		for (Instance pin : session.pins(circuit)) {
			labels.put(pin, session.pinName(pin));
		}
		return labels;
	}

	/** Runs Logisim's own table computation and returns the populated model. */
	static AnalyzerModel analyze(McpSession session, Circuit circuit) {
		AnalyzerModel model = new AnalyzerModel();
		try {
			Analyze.computeTable(model, session.project(), circuit, labelsOf(session, circuit));
		} catch (Throwable t) {
			throw new McpException("Logisim's analysis failed: " + McpSession.message(t), t);
		}
		return model;
	}

	/**
	 * Boolean expressions per output pin.
	 *
	 * <p>
	 * Note where this comes from: Logisim's structural expression synthesis
	 * ({@code Analyze.computeExpression}) is commented out in this fork, but the GUI's
	 * Expression, Minimized and Karnaugh-map tabs do not use it. They derive the
	 * expression from the <em>truth table</em> by prime-implicant minimisation
	 * ({@code OutputExpressions} + {@code Implicant}), which is exactly what this tool
	 * exposes. The result is therefore equivalent to what the Analyze Circuit window
	 * shows, and it needs no change to any existing Logisim code.
	 * </p>
	 */
	public static McpTool expression(final McpSession session) {
		return new AbstractTool("logisim_analyze_expression",
				"Reports the boolean expression of every output pin of a combinational circuit, derived from the "
						+ "circuit's truth table by prime-implicant minimisation (the same code behind the Expression, "
						+ "Minimized and Karnaugh-map tabs of Logisim's Analyze Circuit window). Also returns the prime "
						+ "implicants, as the minterms each one covers, which is what a Karnaugh map draws as a group.",
				Schemas.object()
						.opt("circuit", Schemas.string("Circuit to analyse; defaults to the current circuit"))
						.opt("max_inputs", Schemas.integer("Refuse to run when the circuit has more input pins than "
								+ "this (default " + DEFAULT_MAX_INPUTS + ", maximum " + HARD_MAX_INPUTS + ")"))
						.opt("format", Schemas.stringEnum("Minimisation target: sum of products or product of sums",
								"sop", "pos"))
						.opt("include_implicants", Schemas.bool("Include the prime implicants (default true)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				int maxInputs = ctx.optInt("max_inputs", Integer.valueOf(DEFAULT_MAX_INPUTS)).intValue();
				if (maxInputs < 1 || maxInputs > HARD_MAX_INPUTS) {
					throw new McpException("'max_inputs' must be between 1 and " + HARD_MAX_INPUTS);
				}
				List<Instance> inputs = session.inputPins(circuit);
				List<Instance> outputs = session.outputPins(circuit);
				if (inputs.isEmpty()) {
					throw new McpException("circuit '" + circuit.getName() + "' has no input pins, so it has no "
							+ "expression; add Pins from the Wiring library to expose its inputs");
				}
				if (outputs.isEmpty()) {
					throw new McpException("circuit '" + circuit.getName() + "' has no output pins, so there is "
							+ "nothing to describe; add output Pins (attribute output = true)");
				}
				if (inputs.size() > maxInputs) {
					throw new McpException("circuit '" + circuit.getName() + "' has " + inputs.size() + " input pins, "
							+ "which would need " + (1 << Math.min(30, inputs.size())) + " rows; raise 'max_inputs' "
							+ "up to " + HARD_MAX_INPUTS + " only if you really need it");
				}

				AnalyzerModel model = analyze(session, circuit);
				OutputExpressions expressions = model.getOutputExpressions();
				boolean includeImplicants = ctx.optBool("include_implicants", true);
				int format = "pos".equalsIgnoreCase(ctx.optString("format", "sop"))
						? AnalyzerModel.FORMAT_PRODUCT_OF_SUMS
						: AnalyzerModel.FORMAT_SUM_OF_PRODUCTS;

				JsonArray results = new JsonArray();
				for (int i = 0; i < model.getOutputs().size(); i++) {
					String output = model.getOutputs().get(i);
					expressions.setMinimizedFormat(output, format);
					Expression expression = expressions.getExpression(output);
					JsonObject item = new JsonObject();
					item.addProperty("output", output);
					item.addProperty("expression", expression == null ? null : expression.toString());
					item.addProperty("is_minimal", Boolean.valueOf(expressions.isExpressionMinimal(output)));
					item.addProperty("format", format == AnalyzerModel.FORMAT_PRODUCT_OF_SUMS ? "pos" : "sop");
					if (includeImplicants) {
						item.add("implicants", implicantsOf(expressions, output));
					}
					results.add(item);
				}

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
			ret.add("inputs", Json.array(inputPinNames(session, circuit)));
			ret.add("outputs", results);
			ret.addProperty("row_count", model.getTruthTable().getRowCount());
			ret.add("note", Json.element("expressions come from minimising the truth table, exactly as "
					+ "Logisim's Analyze Circuit window does; the circuit's own structural expression "
					+ "synthesis is disabled in this fork, so this is the same answer a Logisim user sees"));
			return ret;
			}
		};
	}

	private static JsonArray implicantsOf(OutputExpressions expressions, String output) {
		JsonArray groups = new JsonArray();
		List<Implicant> implicants = expressions.getMinimalImplicants(output);
		if (implicants == null) {
			return groups;
		}
		for (Implicant implicant : implicants) {
			int row = implicant.getRow();
			int unknowns = implicant.getUnknownCount();
			JsonObject group = new JsonObject();
			if (row >= 0) {
				group.add("minterms", Json.array(row));
				group.addProperty("dont_cares", 0);
			} else if (unknowns > 0 && unknowns < 32) {
				JsonArray minterms = new JsonArray();
				for (Implicant term : implicant.getTerms()) {
					minterms.add(term.getRow());
				}
				group.add("minterms", minterms);
				group.addProperty("dont_cares", unknowns);
			} else {
				// the constant 1 case, represented by a special all-don't-care implicant
				group.add("minterms", new JsonArray());
				group.addProperty("constant", "1");
			}
			groups.add(group);
		}
		return groups;
	}

	private static List<String> inputPinNames(McpSession session, Circuit circuit) {
		List<String> ret = new ArrayList<String>();
		for (Instance pin : session.inputPins(circuit)) {
			ret.add(session.pinName(pin));
		}
		return ret;
	}

	/**
	 * Builds a circuit from a truth table, the same operation as the Analyze Circuit
	 * window's "Build Circuit" button ({@code CircuitBuilder}). The table can come
	 * from an existing circuit, or be given explicitly, which is how an agent turns a
	 * specification into gates.
	 */
	public static McpTool buildCircuit(final McpSession session) {
		return new AbstractTool("logisim_circuit_build",
				"Generates a gate level circuit from a truth table, using Logisim's own circuit builder. Supply "
						+ "either 'from_circuit' (reuse the behaviour of an existing circuit) or 'inputs' plus "
						+ "'outputs' to specify the table directly. The destination circuit is cleared first, so name a "
						+ "fresh or empty circuit, or pass replace = true.",
				Schemas.object()
						.req("circuit", Schemas.string("Name of the destination circuit; created when it does not exist"))
						.opt("from_circuit", Schemas.string("Take the truth table from this circuit instead of 'inputs' "
								+ "and 'outputs'"))
						.opt("inputs", Schemas.array(Schemas.string("input variable name"),
								"Input names in order; row i means bit i of the row index"))
						.opt("outputs", Schemas.freeObject("Output name to a list of values, one per row, each "
								+ "\"0\", \"1\" or \"x\" for don't care"))
						.opt("two_inputs", Schemas.bool("Use only two-input gates (default false)"))
						.opt("use_nands", Schemas.bool("Build only from NAND gates (default false)"))
						.opt("replace", Schemas.bool("Allow clearing a destination circuit that already has "
								+ "components (default false)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				String name = ctx.requireString("circuit").trim();
				String source = ctx.optString("from_circuit", null);
				JsonArray inputs = ctx.optArray("inputs");
				JsonObject outputs = ctx.optObject("outputs");
				if (source == null && (inputs == null || outputs == null)) {
					throw new McpException("give either 'from_circuit', or both 'inputs' and 'outputs'");
				}
				if (source != null && (inputs != null || outputs != null)) {
					throw new McpException("give either 'from_circuit' or an explicit 'inputs'/'outputs' table, "
							+ "not both");
				}

				AnalyzerModel model = new AnalyzerModel();
				if (source != null) {
					Circuit sourceCircuit = session.circuitByName(source);
					if (session.inputPins(sourceCircuit).isEmpty()) {
						throw new McpException("circuit '" + sourceCircuit.getName() + "' has no input pins, so its "
								+ "truth table is empty");
					}
					model = analyze(session, sourceCircuit);
				} else {
					List<String> inputNames = new ArrayList<String>();
					for (JsonElement element : inputs) {
						inputNames.add(element.getAsString());
					}
					if (inputNames.isEmpty()) {
						throw new McpException("'inputs' must name at least one variable");
					}
					if (inputNames.size() > HARD_MAX_INPUTS) {
						throw new McpException("at most " + HARD_MAX_INPUTS + " input variables are supported");
					}
					int rows = 1 << inputNames.size();
					List<String> outputNames = new ArrayList<String>(outputs.keySet());
					if (outputNames.isEmpty()) {
						throw new McpException("'outputs' must name at least one output");
					}
					model.setVariables(inputNames, outputNames);
					for (int column = 0; column < outputNames.size(); column++) {
						String output = outputNames.get(column);
						JsonElement raw = outputs.get(output);
						if (!raw.isJsonArray() || raw.getAsJsonArray().size() != rows) {
							throw new McpException("output '" + output + "' needs exactly " + rows
									+ " values, one for each row of the " + inputNames.size() + " input variable table");
						}
						Entry[] entries = new Entry[rows];
						for (int row = 0; row < rows; row++) {
							entries[row] = entryOf(raw.getAsJsonArray().get(row));
						}
						model.getTruthTable().setOutputColumn(column, entries);
					}
				}

				Circuit destination = session.file().getCircuit(name);
				boolean created = false;
				if (destination == null) {
					destination = new Circuit(name);
					session.project().doAction(LogisimFileActions.addCircuit(destination));
					session.invalidateCatalog();
					created = true;
				} else if (!destination.getNonWires().isEmpty() && !ctx.optBool("replace", false)) {
					throw new McpException("circuit '" + name + "' already contains "
							+ destination.getNonWires().size() + " components; pass replace = true to clear it, or "
							+ "name a new circuit");
				}

				CircuitMutation mutation;
				try {
					mutation = CircuitBuilder.build(destination, model, ctx.optBool("two_inputs", false),
							ctx.optBool("use_nands", false));
				} catch (Throwable t) {
					throw new McpException("Logisim's circuit builder failed: " + McpSession.message(t), t);
				}
				session.commit(mutation, "build circuit " + name);

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", name);
				ret.addProperty("created", Boolean.valueOf(created));
				ret.addProperty("component_count", destination.getNonWires().size());
				ret.addProperty("wire_count", destination.getWires().size());
				ret.add("inputs", Json.array(inputPinNames(session, destination)));
				List<String> outputNames = new ArrayList<String>();
				for (Instance pin : session.outputPins(destination)) {
					outputNames.add(session.pinName(pin));
				}
				ret.add("outputs", Json.array(outputNames));
				ret.add("hint", Json.element("call logisim_analyze_truth_table or logisim_analyze_expression on '"
						+ name + "' to check the generated circuit"));
				return ret;
			}
		};
	}

	private static Entry entryOf(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			throw new McpException("a truth table value must be 0, 1 or x");
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			int value = element.getAsInt();
			if (value == 0) {
				return Entry.ZERO;
			}
			if (value == 1) {
				return Entry.ONE;
			}
			throw new McpException("truth table values must be 0, 1 or x");
		}
		String text = element.getAsString().trim().toLowerCase();
		if (text.equals("0") || text.equals("false")) {
			return Entry.ZERO;
		}
		if (text.equals("1") || text.equals("true")) {
			return Entry.ONE;
		}
		if (text.equals("x") || text.equals("-") || text.equals("d") || text.equals("dontcare")
				|| text.equals("don't care")) {
			return Entry.DONT_CARE;
		}
		throw new McpException("truth table value '" + text + "' is not 0, 1 or x");
	}

	private static String entryText(Entry entry) {
		if (entry == null) {
			return "?";
		}
		if (entry.isError()) {
			return "!!";
		}
		return entry.getDescription();
	}
}
