/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.util.Map;

import com.cburch.hex.HexModel;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.mcp.McpException;
import com.cburch.logisim.mcp.McpInstructions;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;
import com.cburch.logisim.mcp.Values;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The contents of a memory component: what a ROM holds, what a RAM holds, and the one
 * thing a CPU design needs before it can be simulated at all.
 *
 * <p>
 * The two memories keep their data in different places, and the difference is visible in
 * what a write does. A ROM holds its words in a component attribute, and that attribute is
 * the text Logisim writes into a {@code .circ} file, so a write here is an ordinary
 * undoable edit that the file carries with it. A RAM holds its words in the simulation
 * state the simulator attaches to the instance - its contents are runtime data, not part
 * of the drawing - so a write takes effect immediately and is not an undo step. The tool
 * reports which of the two happened instead of pretending they are the same.
 * </p>
 *
 * <p>
 * Either way the words are reached through {@code com.cburch.hex.HexModel}, the interface
 * Logisim's own memory display edits, and nothing in Logisim is modified to do it.
 * </p>
 */
public final class MemoryTools {
	/**
	 * The largest memory this tool will walk word by word. A 24-bit address space is 16
	 * million words, and building the text a ROM attribute needs would cost more memory than
	 * the whole server has; a design that big is better loaded by the component's own
	 * attribute, which is a single value the user edits in Logisim's hex window.
	 */
	private static final int MAX_WORDS = 1 << 20;

	private static final int DEFAULT_LIMIT = 64;
	private static final int MAX_LIMIT = 4096;

	private MemoryTools() {
	}

	public static McpTool memory(final McpSession session) {
		return new AbstractTool("logisim_component_memory", McpInstructions.COMPONENT_MEMORY,
				Schemas.object()
						.req("component", Schemas.componentRef("The memory component, a ROM or a RAM"))
						.opt("action", Schemas.stringEnum("What to do: 'read' a range (the default), 'write' "
								+ "values, or 'clear' every word to zero", "read", "write", "clear"))
						.opt("from", Schemas.any("First word address for a read or for 'words': a number, or a "
								+ "string such as \"0x20\" (default 0)"))
						.opt("limit", Schemas.integer("Most words a read returns (default " + DEFAULT_LIMIT
								+ ", maximum " + MAX_LIMIT + ")"))
						.opt("words", Schemas.array(Schemas.string("one word, e.g. \"0x12\""),
								"Values to write, starting at 'from'"))
						.opt("values", Schemas.freeObject("Values to write by address, e.g. {\"5\": \"0x12\", "
								+ "\"0x10\": \"255\"}"))
						.opt("fill", Schemas.string("One value to write at every address"))
						.opt("dry_run", Schemas.bool("A write: validate and report, but change nothing "
								+ "(default false)"))
						.opt("circuit", Schemas.string("Circuit holding the component; defaults to the current "
								+ "circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				Component comp = session.requireComponent(circuit, ctx.optElement("component"));
				String id = session.idOf(comp);
				String type = comp.getFactory().getName();
				String action = ctx.optString("action", "read").trim().toLowerCase();
				boolean dryRun = ctx.optBool("dry_run", false);
				if (!"read".equals(action) && !"write".equals(action) && !"clear".equals(action)) {
					throw new McpException("'action' must be 'read', 'write' or 'clear'; '" + action
							+ "' is none of them");
				}

				String kind = kindOf(comp);
				Attribute<?> contents = null;
				HexModel model;
				if ("rom".equals(kind)) {
					contents = contentsAttribute(comp.getAttributeSet());
					model = contents == null ? null : asModel(comp.getAttributeSet().getValue(contents));
				} else if ("ram".equals(kind)) {
					model = ramContents(Instance.getInstanceFor(comp), session.stateFor(circuit));
				} else {
					throw new McpException("logisim_component_memory reads the contents of a ROM or a RAM; " + id
							+ " is a \"" + type + "\". An ordinary attribute is set with "
							+ "logisim_component_set_attributes, and a component's pins are read with "
							+ "logisim_sim_read_all");
				}
				if (model == null) {
					throw new McpException("the contents of " + id + " (" + type + ") could not be read: "
							+ ("ram".equals(kind)
									? "a RAM only has contents once the simulator has built its state; call "
											+ "logisim_sim_propagate on this circuit and try again"
									: "this component has no 'contents' attribute in this build of Logisim"));
				}

				long first = model.getFirstOffset();
				long last = model.getLastOffset();
				long total = last - first + 1;
				int dataBits = model.getValueWidth();
				if (total <= 0) {
					throw new McpException(id + " reports a memory with no words at all");
				}
				if (total > MAX_WORDS) {
					throw new McpException(id + " holds " + total + " words (" + dataBits + " bits each); this "
							+ "tool walks at most " + MAX_WORDS + " of them, because a ROM's contents are an "
							+ "attribute whose whole text has to be rebuilt for every write - set a smaller "
							+ "address width, or edit such a memory in Logisim's own hex window");
				}
				int addrBits = bitsFor(total);

				if ("read".equals(action)) {
					long from = address(ctx.optString("from", null), 0L, first, last, id);
					int limit = ctx.optInt("limit", Integer.valueOf(DEFAULT_LIMIT)).intValue();
					if (limit < 1 || limit > MAX_LIMIT) {
						throw new McpException("'limit' must be between 1 and " + MAX_LIMIT);
					}
					long count = Math.min(limit, last - from + 1);
					JsonObject values = new JsonObject();
					int nonzero = 0;
					for (long i = 0; i < count; i++) {
						int word = model.get(from + i);
						if (word != 0) {
							nonzero++;
						}
						values.addProperty(Long.toString(from + i), hexWord(word, dataBits));
					}
					JsonObject ret = new JsonObject();
					ret.addProperty("component", id);
					ret.addProperty("type", type);
					ret.addProperty("kind", kind);
					ret.addProperty("address_bits", Integer.valueOf(addrBits));
					ret.addProperty("data_bits", Integer.valueOf(dataBits));
					ret.addProperty("words_total", Long.valueOf(total));
					ret.addProperty("from", Long.valueOf(from));
					ret.addProperty("returned", Long.valueOf(count));
					ret.addProperty("truncated", Boolean.valueOf(count < last - from + 1));
					ret.addProperty("nonzero_in_range", Integer.valueOf(nonzero));
					ret.add("values", values);
					ret.addProperty("where_the_contents_live", whereContentsLive(kind, type));
					ret.addProperty("persistence", persistence(kind));
					ret.addProperty("hint", "values are hex, 'data_bits' wide; an address here is a word address, "
							+ "so the word the address pins select is the one at that number. To see what the "
							+ "memory is doing in the running circuit, wire its data output or use "
							+ "logisim_sim_read_all");
					return ret;
				}

				// -------------------------------------------------------------- write / clear
				java.util.TreeMap<Long, Integer> changes = new java.util.TreeMap<Long, Integer>();
				if ("clear".equals(action)) {
					for (long addr = first; addr <= last; addr++) {
						changes.put(Long.valueOf(addr), Integer.valueOf(0));
					}
				} else {
					boolean anything = false;
					if (ctx.has("fill")) {
						int word = wordOf(ctx.requireString("fill"), dataBits, "'fill'");
						for (long addr = first; addr <= last; addr++) {
							changes.put(Long.valueOf(addr), Integer.valueOf(word));
						}
						anything = true;
					}
					JsonObject byAddress = ctx.optObject("values");
					if (byAddress != null) {
						for (Map.Entry<String, JsonElement> entry : byAddress.entrySet()) {
							long addr = address(entry.getKey(), 0L, first, last, id);
							changes.put(Long.valueOf(addr), Integer.valueOf(wordOf(entry.getValue().getAsString(),
									dataBits, "the value for address " + addr)));
						}
						anything = true;
					}
					JsonArray list = ctx.optArray("words");
					if (list != null) {
						long from = address(ctx.optString("from", null), 0L, first, last, id);
						if (from + list.size() - 1 > last) {
							throw new McpException("'words' has " + list.size() + " entries starting at address "
									+ from + ", which runs past the end of " + id + " (last address " + last + ")");
						}
						for (int i = 0; i < list.size(); i++) {
							changes.put(Long.valueOf(from + i), Integer.valueOf(wordOf(list.get(i).getAsString(),
									dataBits, "word " + i + " of 'words'")));
						}
						anything = true;
					}
					if (!anything) {
						throw new McpException("a write needs 'words' (a list, starting at 'from'), 'values' "
								+ "(address to value) or 'fill' (one value for every address); use "
								+ "'action': \"clear\" to zero the whole memory");
					}
				}

				boolean undoable = "rom".equals(kind);
				if (dryRun) {
					JsonObject ret = writeResult(id, type, kind, action, changes.size(), total, addrBits, dataBits,
							undoable);
					ret.addProperty("dry_run", Boolean.TRUE);
					ret.addProperty("dry_run_note", "nothing was changed; remove 'dry_run' to make this edit");
					return ret;
				}

				String description = "clear".equals(action) ? "clear memory contents" : "write memory contents";
				int applied;
				if (undoable) {
					int[] all = new int[(int) total];
					for (int i = 0; i < all.length; i++) {
						all[i] = model.get(first + i);
					}
					applied = 0;
					for (Map.Entry<Long, Integer> entry : changes.entrySet()) {
						int index = (int) (entry.getKey().longValue() - first);
						int word = entry.getValue().intValue();
						if (all[index] != word) {
							applied++;
						}
						all[index] = word;
					}
					if (applied > 0) {
						// The attribute holds the text Logisim reads and writes for a ROM, so the
						// words are put back through the attribute's own parser: what is stored is
						// exactly what the file would carry, and the change is an ordinary
						// undoable edit.
						Object fresh = parse(contents, contentsText(all, addrBits, dataBits));
						if (fresh == null) {
							throw new McpException("Logisim's own reader refused the memory contents this tool "
									+ "built for " + id + "; nothing was changed. This is a defect in "
									+ "logisim_component_memory, not in the circuit");
						}
						CircuitMutation mutation = new CircuitMutation(circuit);
						mutation.set(comp, contents, fresh);
						session.commit(mutation, description);
						session.noteTouched(comp);
					}
				} else {
					applied = 0;
					for (Map.Entry<Long, Integer> entry : changes.entrySet()) {
						long addr = entry.getKey().longValue();
						int word = entry.getValue().intValue();
						if (model.get(addr) != word) {
							applied++;
						}
						model.set(addr, word);
					}
					// the hex model notifies Logisim, which marks the instance invalid; settling
					// the circuit now is what makes the memory's output follow the new contents
					session.stateFor(circuit).getPropagator().propagate();
					session.noteTouched(comp);
				}

				JsonObject ret = writeResult(id, type, kind, action, applied, total, addrBits, dataBits, undoable);
				if (applied == 0) {
					ret.addProperty("note", "every word already held that value, so nothing changed");
				} else {
					ret.addProperty("note", undoable
							? "the ROM keeps its contents in the circuit, so this was an ordinary undoable edit and Logisim "
									+ "saves it with the file"
							: "a RAM keeps its contents in the simulation state rather than in the circuit, so this took "
									+ "effect immediately, is not an undo step, and will NOT be saved: after reloading the file the "
									+ "memory is empty again. A program that has to survive belongs in a ROM");
				}
				ret.addProperty("dirty", Boolean.valueOf(session.file().isDirty()));
				ret.addProperty("hint", "read the words back with logisim_component_memory {action: \"read\"} to "
						+ "confirm what the memory now holds");
				return ret;
			}
		};
	}

	/** What a read or a write says about where the words actually live. */
	private static String whereContentsLive(String kind, String type) {
		return "rom".equals(kind)
				? "the component's 'contents' attribute, which is the text Logisim writes into the .circ file; a "
						+ "write is an ordinary undoable edit that is saved with the file"
				: "the simulation state the simulator attaches to " + type + ", not the circuit drawing; a write "
						+ "takes effect at once, logisim_undo does not restore it, and it is not saved - opening the "
						+ "saved file starts this memory empty again";
	}

	/** Whether the words outlive the session; a RAM's do not. */
	private static String persistence(String kind) {
		return "rom".equals(kind)
				? "saved with the file"
				: "not saved: the contents are runtime state, so a reload starts the memory empty; put anything "
						+ "that has to survive in a ROM";
	}

	private static JsonObject writeResult(String id, String type, String kind, String action, int changed,
			long total, int addrBits, int dataBits, boolean undoable) {
		JsonObject ret = new JsonObject();
		ret.addProperty("component", id);
		ret.addProperty("type", type);
		ret.addProperty("kind", kind);
		ret.addProperty("action", action);
		ret.addProperty("changed", Integer.valueOf(changed));
		ret.addProperty("words_total", Long.valueOf(total));
		ret.addProperty("address_bits", Integer.valueOf(addrBits));
		ret.addProperty("data_bits", Integer.valueOf(dataBits));
		ret.addProperty("undoable", Boolean.valueOf(undoable));
		ret.addProperty("persistence", persistence(kind));
		ret.addProperty("where_the_contents_live", whereContentsLive(kind, type));
		return ret;
	}

	/** "rom" for a ROM, "ram" for a RAM, null for anything else. */
	private static String kindOf(Component comp) {
		String name = comp.getFactory().getClass().getSimpleName();
		if (name.startsWith("Rom")) {
			return "rom";
		}
		if (name.startsWith("Ram")) {
			return "ram";
		}
		return null;
	}

	/** The 'contents' attribute a ROM keeps its memory in; a RAM has none. */
	private static Attribute<?> contentsAttribute(AttributeSet attrs) {
		for (Attribute<?> attr : attrs.getAttributes()) {
			if ("contents".equals(attr.getName())) {
				return attr;
			}
		}
		return null;
	}

	/** The attribute's value as the hex model Logisim's own memory display edits. */
	private static HexModel asModel(Object value) {
		return value instanceof HexModel ? (HexModel) value : null;
	}

	/**
	 * The contents of a RAM, which Logisim keeps in the state object the simulator attaches
	 * to the instance rather than in an attribute. That object's class is not public, so the
	 * hex model inside it - the same one Logisim's hex window edits - is the one public door
	 * into it. Nothing in Logisim is changed by asking, and when the door is not there the
	 * tool reports that instead of guessing at the memory's contents.
	 */
	private static HexModel ramContents(Instance instance, CircuitState state) {
		if (instance == null) {
			return null;
		}
		Object data = instance.getData(state);
		if (data == null) {
			// a RAM that has never been simulated has no state yet; propagating builds it
			state.getPropagator().propagate();
			data = instance.getData(state);
		}
		if (data == null) {
			return null;
		}
		try {
			java.lang.reflect.Method getter = data.getClass().getMethod("getContents");
			getter.setAccessible(true);
			return asModel(getter.invoke(data));
		} catch (Throwable t) {
			return null;
		}
	}

	/** An address as written by the agent, checked against the memory's range. */
	private static long address(String text, long fallback, long first, long last, String id) {
		if (text == null || text.trim().isEmpty()) {
			return fallback;
		}
		String s = text.trim().toLowerCase();
		long ret;
		try {
			if (s.startsWith("0x")) {
				ret = Long.parseLong(s.substring(2), 16);
			} else if (s.startsWith("0b")) {
				ret = Long.parseLong(s.substring(2), 2);
			} else {
				ret = Long.parseLong(s);
			}
		} catch (NumberFormatException e) {
			throw new McpException("'" + text + "' is not an address; use a decimal number or 0x-prefixed hex");
		}
		if (ret < first || ret > last) {
			throw new McpException("address " + ret + " is outside " + id + ", which holds addresses " + first
					+ " to " + last);
		}
		return ret;
	}

	/** One word as the agent wrote it, refused unless every bit is known and fits. */
	private static int wordOf(String text, int dataBits, String where) {
		Value value = Values.parse(text, BitWidth.create(dataBits));
		if (!value.isFullyDefined()) {
			throw new McpException(where + ": '" + text + "' has unknown bits; a memory word has to be known "
					+ "exactly, so 0, 1, x, e and z are not values a word can hold");
		}
		return value.toIntValue();
	}

	/** A word as hex, padded to the width the memory holds. */
	private static String hexWord(int word, int dataBits) {
		int digits = (dataBits + 3) / 4;
		String hex = Long.toHexString(word & 0xFFFFFFFFL);
		StringBuilder buf = new StringBuilder("0x");
		for (int i = hex.length(); i < digits; i++) {
			buf.append('0');
		}
		return buf.append(hex).toString();
	}

	/** The number of address bits a memory of this many words needs. */
	private static int bitsFor(long total) {
		int bits = 0;
		while ((1L << bits) < total) {
			bits++;
		}
		return bits;
	}

	/** An attribute's parser, reachable through the wildcard this layer holds it by. */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static Object parse(Attribute<?> attr, String text) {
		return ((Attribute) attr).parse(text);
	}

	/**
	 * The text a ROM's contents attribute holds: {@code addr/data: <address bits> <data bits>}
	 * followed by the words in hex. This is exactly the shape Logisim writes and reads for a
	 * ROM ({@code Rom.ContentsAttribute.parse} hands the body to {@code HexFile}), so the
	 * value that goes back into the attribute is one Logisim itself produced - which is what
	 * keeps the file format Logisim's business. Runs of equal words are written as
	 * {@code count*value} and trailing zeros are left out, which is what keeps a mostly empty
	 * memory small.
	 */
	private static String contentsText(int[] words, int addrBits, int dataBits) {
		StringBuilder buf = new StringBuilder();
		buf.append("addr/data: ").append(addrBits).append(' ').append(dataBits).append('\n');
		int last = words.length - 1;
		while (last >= 0 && words[last] == 0) {
			last--;
		}
		int tokens = 0;
		int cur = 0;
		while (cur <= last) {
			int value = words[cur];
			int start = cur;
			cur++;
			while (cur <= last && words[cur] == value) {
				cur++;
			}
			int run = cur - start;
			if (run < 4) {
				cur = start + 1;
				run = 1;
			}
			if (tokens > 0) {
				buf.append(tokens % 8 == 0 ? '\n' : ' ');
			}
			if (run > 1) {
				buf.append(run).append('*');
			}
			buf.append(Integer.toHexString(value & 0xFFFFFFFF));
			tokens++;
		}
		return buf.toString();
	}
}
