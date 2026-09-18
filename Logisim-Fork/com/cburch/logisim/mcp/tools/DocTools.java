/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.cburch.logisim.mcp.DocLibrary;
import com.cburch.logisim.mcp.McpException;
import com.cburch.logisim.mcp.McpInstructions;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The tool that lets an agent read Logisim's manual.
 *
 * <p>
 * It exists because the alternative is an agent guessing what a pin does. Logisim's own
 * documentation is the only place the meaning of every port is written down - the library
 * reference describes each component's inputs and outputs, which pin is the clock, what an
 * enable does and how the outputs behave - and it ships with the program, so there is no
 * reason for an agent not to have it. Three modes, chosen by the arguments:
 * </p>
 *
 * <ul>
 * <li>no arguments: an index of the manual, grouped by section;</li>
 * <li>{@code query}: every page that mentions all the words, best match first, each with
 * the paragraph it matched in;</li>
 * <li>{@code topic}: one page, as text, with the pages beside it listed.</li>
 * </ul>
 *
 * <p>
 * It needs no open project: looking something up is allowed before anything else happens.
 * </p>
 */
public final class DocTools {
	/** Pages listed per section in the index, to keep a reply a reasonable size. */
	private static final int PAGES_PER_SECTION = 40;
	/** Related pages reported beside a page. */
	private static final int RELATED_PAGES = 12;

	private DocTools() {
	}

	public static McpTool help() {
		return new AbstractTool("logisim_help", McpInstructions.HELP,
				Schemas.object()
						.opt("topic", Schemas.string("The page to read: a path such as 'libs/mem/flipflops.html', "
								+ "a part of one such as 'flipflops', a guide section such as 'analyze', or a "
								+ "component's name such as 'D Flip-Flop'"))
						.opt("query", Schemas.string("Words to search the whole manual for; every word has to "
								+ "appear on a page for it to match"))
						.opt("lang", Schemas.string("Manual language, 'en' by default; the index lists what is "
								+ "available"))
						.opt("limit", Schemas.integer("Maximum number of search results (default 5)"))
						.opt("max_chars", Schemas.integer("Cut a page's text after this many characters "
								+ "(default 8000)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				if (!DocLibrary.available()) {
					throw new McpException(McpInstructions.DOCS_MISSING);
				}
				String lang = ctx.optString("lang", DocLibrary.DEFAULT_LANGUAGE);
				if (lang == null || lang.trim().isEmpty()) {
					lang = DocLibrary.DEFAULT_LANGUAGE;
				}
				String topic = ctx.optString("topic", null);
				String query = ctx.optString("query", null);
				JsonObject ret = new JsonObject();
				ret.addProperty("docs_source", DocLibrary.source());
				if (query != null && !query.trim().isEmpty()) {
					search(ctx, ret, lang, query);
				} else if (topic != null && !topic.trim().isEmpty()) {
					read(ctx, ret, lang, topic);
				} else {
					index(ret, lang);
				}
				return ret;
			}
		};
	}

	private static void search(ToolContext ctx, JsonObject ret, String lang, String query) {
		int limit = ctx.optInt("limit", Integer.valueOf(5)).intValue();
		List<DocLibrary.Match> matches = DocLibrary.search(lang, query, limit);
		JsonArray array = new JsonArray();
		for (DocLibrary.Match match : matches) {
			JsonObject item = new JsonObject();
			item.addProperty("page", match.page);
			item.addProperty("title", match.title);
			item.addProperty("score", Integer.valueOf(match.score));
			item.addProperty("excerpt", match.excerpt);
			array.add(item);
		}
		ret.addProperty("language", lang);
		ret.addProperty("query", query);
		ret.addProperty("match_count", Integer.valueOf(matches.size()));
		ret.add("matches", array);
		ret.addProperty("hint", matches.isEmpty()
				? "no page of the manual mentions every one of those words; try fewer words, different words, or "
						+ "call logisim_help with no arguments to see the contents"
				: "call logisim_help again with 'topic' set to one of these 'page' values to read the whole page");
	}

	private static void read(ToolContext ctx, JsonObject ret, String lang, String topic) {
		String page = resolvePage(lang, topic);
		if (page == null) {
			// The manual is named after components only loosely: a flip-flop lives in
			// 'libs/mem/flipflops.html', not in a page of its own. So an unresolved topic is
			// treated as a search rather than as a mistake - that is what the caller meant.
			search(ctx, ret, lang, topic);
			if (ret.get("match_count").getAsInt() == 0) {
				throw new McpException("nothing in the manual is called or mentions '" + topic
						+ "'. Call logisim_help with no arguments to see the contents, or with 'query' to search for "
						+ "other words; a topic may also be a page path such as 'guide/analyze/index.html'.");
			}
			ret.addProperty("note", "no page is named '" + topic + "', so this is a search instead: read one of "
					+ "the matches by calling logisim_help again with 'topic' set to its 'page'");
			return;
		}
		String text = DocLibrary.text(lang, page);
		int max = ctx.optInt("max_chars", Integer.valueOf(DocLibrary.DEFAULT_MAX_CHARS)).intValue();
		boolean truncated = max > 0 && text.length() > max;
		ret.addProperty("language", lang);
		ret.addProperty("page", page);
		ret.addProperty("title", DocLibrary.title(lang, page));
		ret.addProperty("char_count", Integer.valueOf(text.length()));
		ret.addProperty("text", truncated ? text.substring(0, max) : text);
		ret.addProperty("truncated", Boolean.valueOf(truncated));
		JsonArray related = new JsonArray();
		for (String sibling : siblings(lang, page)) {
			related.add(sibling);
		}
		ret.add("related_pages", related);
		ret.addProperty("hint", "the library reference ('libs/...') describes every component, including what each "
				+ "of its pins does; logisim_component_catalog reports the same pins for a component you are about "
				+ "to place");
	}

	private static void index(JsonObject ret, String lang) {
		JsonArray languages = new JsonArray();
		for (String available : DocLibrary.languages()) {
			languages.add(available);
		}
		List<String> pages = DocLibrary.pages(lang);
		Map<String, List<String>> bySection = new LinkedHashMap<String, List<String>>();
		for (String page : pages) {
			int slash = page.indexOf('/');
			String section = slash < 0 ? "(top level)" : page.substring(0, slash);
			List<String> into = bySection.get(section);
			if (into == null) {
				into = new ArrayList<String>();
				bySection.put(section, into);
			}
			into.add(page);
		}
		JsonArray sections = new JsonArray();
		for (Map.Entry<String, List<String>> entry : bySection.entrySet()) {
			JsonObject section = new JsonObject();
			section.addProperty("name", entry.getKey());
			section.addProperty("page_count", Integer.valueOf(entry.getValue().size()));
			JsonArray listed = new JsonArray();
			List<String> value = entry.getValue();
			for (int i = 0; i < value.size() && i < PAGES_PER_SECTION; i++) {
				listed.add(value.get(i));
			}
			section.add("pages", listed);
			if (value.size() > PAGES_PER_SECTION) {
				section.addProperty("omitted", Integer.valueOf(value.size() - PAGES_PER_SECTION));
			}
			sections.add(section);
		}
		ret.addProperty("language", lang);
		ret.add("languages", languages);
		ret.addProperty("page_count", Integer.valueOf(pages.size()));
		ret.add("sections", sections);
		ret.addProperty("hint", "read one of these with 'topic', or search the whole manual with 'query'. The "
				+ "'libs' section is the library reference: one page per component, saying what each of its pins "
				+ "does.");
	}

	/**
	 * Finds the page a topic means: an exact path, else a page whose name or directory
	 * matches, comparing only letters and digits so that "D Flip-Flop" reaches
	 * {@code libs/mem/dflipflop.html}.
	 */
	private static String resolvePage(String lang, String topic) {
		List<String> pages = DocLibrary.pages(lang);
		String direct = topic.trim().replace('\\', '/');
		if (direct.startsWith("/")) {
			direct = direct.substring(1);
		}
		if (pages.contains(direct)) {
			return direct;
		}
		if (pages.contains(direct + ".html")) {
			return direct + ".html";
		}
		String needle = normalize(topic);
		if (needle.isEmpty()) {
			return null;
		}
		String best = null;
		for (String page : pages) {
			if (namesOf(page).contains(needle) && preferable(page, best)) {
				best = page;
			}
		}
		if (best != null) {
			return best;
		}
		for (String page : pages) {
			for (String name : namesOf(page)) {
				if (name.contains(needle) && preferable(page, best)) {
					best = page;
					break;
				}
			}
		}
		return best;
	}

	/** The names a page answers to: its file name, and the directory holding it. */
	private static List<String> namesOf(String page) {
		List<String> ret = new ArrayList<String>();
		String path = page.toLowerCase(Locale.ENGLISH);
		int slash = path.lastIndexOf('/');
		String file = slash < 0 ? path : path.substring(slash + 1);
		if (file.endsWith(".html")) {
			file = file.substring(0, file.length() - 5);
		}
		ret.add(normalize(file));
		if (slash > 0) {
			String dir = path.substring(0, slash);
			int dirSlash = dir.lastIndexOf('/');
			ret.add(normalize(dirSlash < 0 ? dir : dir.substring(dirSlash + 1)));
		}
		return ret;
	}

	/** Prefers the library reference, then the shorter path: 'dflipflop' should win. */
	private static boolean preferable(String candidate, String current) {
		if (current == null) {
			return true;
		}
		boolean candidateIsLibrary = candidate.startsWith("libs/");
		boolean currentIsLibrary = current.startsWith("libs/");
		if (candidateIsLibrary != currentIsLibrary) {
			return candidateIsLibrary;
		}
		if (candidate.length() != current.length()) {
			return candidate.length() < current.length();
		}
		return candidate.compareTo(current) < 0;
	}

	private static String normalize(String text) {
		StringBuilder buf = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = Character.toLowerCase(text.charAt(i));
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				buf.append(c);
			}
		}
		return buf.toString();
	}

	/** The other pages in the same directory, which is how the manual groups related ones. */
	private static List<String> siblings(String lang, String page) {
		List<String> ret = new ArrayList<String>();
		int slash = page.lastIndexOf('/');
		if (slash < 0) {
			return ret;
		}
		String dir = page.substring(0, slash + 1);
		for (String other : DocLibrary.pages(lang)) {
			if (other.startsWith(dir) && !other.equals(page) && ret.size() < RELATED_PAGES) {
				ret.add(other);
			}
		}
		return ret;
	}
}
