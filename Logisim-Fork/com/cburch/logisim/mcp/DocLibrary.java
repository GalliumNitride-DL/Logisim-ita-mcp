/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Logisim's own documentation, made readable by an agent.
 *
 * <p>
 * The manual ships as HTML under {@code doc/} (the same tree the GUI's Help menu feeds
 * to JavaHelp), so this class is the thin layer that finds that tree, lists it, reads a
 * page and turns it into text. It deliberately does not care how Logisim is running:
 * </p>
 *
 * <ul>
 * <li>beside the classes - a development run from {@code build/classes}, where
 * {@code doc/} is the sibling directory the launcher script puts on the classpath;</li>
 * <li>inside a jar - a packaged build, where {@code build-mcp.ps1} puts the whole
 * {@code doc/} tree next to the classes so that both the Help menu and this class find
 * it. Before that was done, the packaged exe genuinely had no documentation in it, which
 * is why an agent could not look anything up there;</li>
 * <li>wherever {@code -Dlogisim.mcp.docs=<dir>} points, for a build that keeps the docs
 * somewhere else.</li>
 * </ul>
 *
 * <p>
 * Everything is read only, bounded and cached per page: 261 English pages are about
 * 900 kB of HTML, which is small enough to hold as text once a search has touched them.
 * </p>
 */
public final class DocLibrary {
	/** Language used when the caller does not ask for one: the manual's source language. */
	public static final String DEFAULT_LANGUAGE = "en";

	/** Pages longer than this are cut, with {@code truncated} set in the reply. */
	public static final int DEFAULT_MAX_CHARS = 8000;

	/** A page and why it matched, for search results. */
	public static final class Match {
		public final String page;
		public final String title;
		public final int score;
		public final String excerpt;

		Match(String page, String title, int score, String excerpt) {
			this.page = page;
			this.title = title;
			this.score = score;
			this.excerpt = excerpt;
		}
	}

	private static boolean located;
	private static File directory;
	private static JarFile jar;
	private static String source = "not found";

	private static final Map<String, String> pageText = new LinkedHashMap<String, String>();
	private static List<String> cachedPages;
	private static String cachedPagesLang;

	private DocLibrary() {
	}

	// ------------------------------------------------------------------
	// finding the manual
	// ------------------------------------------------------------------

	private static synchronized void locate() {
		if (located) {
			return;
		}
		located = true;

		String override = System.getProperty("logisim.mcp.docs");
		if (override != null && !override.trim().isEmpty()) {
			File candidate = new File(override.trim());
			if (isDocsDirectory(candidate)) {
				directory = candidate;
				source = "directory " + candidate.getAbsolutePath();
				return;
			}
		}

		File code = codeSource();
		if (code != null && code.isFile()) {
			JarFile opened = null;
			try {
				opened = new JarFile(code);
				if (opened.getJarEntry("doc/" + DEFAULT_LANGUAGE + "/html/index.html") != null) {
					jar = opened;
					source = "bundled in " + code.getAbsolutePath();
					return;
				}
			} catch (Throwable t) {
				// not a readable jar: fall through to the directory search
			}
			if (opened != null) {
				try {
					opened.close();
				} catch (IOException e) {
					// nothing useful to do
				}
			}
		}

		List<File> candidates = new ArrayList<File>();
		if (code != null) {
			File base = code.isDirectory() ? code : code.getParentFile();
			addCandidate(candidates, base, "doc");
			if (base != null) {
				File up = base.getParentFile();
				addCandidate(candidates, up, "doc");
				if (up != null) {
					addCandidate(candidates, up.getParentFile(), "doc");
				}
			}
		}
		URL fromClasspath = DocLibrary.class.getClassLoader().getResource("doc/" + DEFAULT_LANGUAGE + "/html/index.html");
		if (fromClasspath != null && "file".equals(fromClasspath.getProtocol())) {
			try {
				File index = new File(fromClasspath.toURI());
				File html = index.getParentFile();          // .../html
				File lang = html == null ? null : html.getParentFile();   // .../<lang>
				File doc = lang == null ? null : lang.getParentFile();    // .../doc
				if (doc != null) {
					candidates.add(doc);
				}
			} catch (Throwable t) {
				// a URL that will not convert: the plain paths below still apply
			}
		}
		candidates.add(new File("doc"));
		candidates.add(new File(".." + File.separator + "doc"));
		candidates.add(new File(".." + File.separator + "Logisim-Fork" + File.separator + "doc"));
		candidates.add(new File("resources" + File.separator + "doc"));

		for (File candidate : candidates) {
			if (isDocsDirectory(candidate)) {
				directory = candidate;
				source = "directory " + candidate.getAbsolutePath();
				return;
			}
		}
	}

	private static void addCandidate(List<File> candidates, File base, String name) {
		if (base != null) {
			candidates.add(new File(base, name));
		}
	}

	private static boolean isDocsDirectory(File candidate) {
		if (candidate == null || !candidate.isDirectory()) {
			return false;
		}
		File marker = new File(candidate, DEFAULT_LANGUAGE + File.separator + "html");
		return marker.isDirectory();
	}

	/** The jar or directory this class was loaded from, or null when unknown. */
	private static File codeSource() {
		try {
			java.security.CodeSource cs = DocLibrary.class.getProtectionDomain().getCodeSource();
			if (cs == null || cs.getLocation() == null) {
				return null;
			}
			return new File(cs.getLocation().toURI());
		} catch (Throwable t) {
			return null;
		}
	}

	/** True when the manual was found, either beside the classes or inside the jar. */
	public static boolean available() {
		locate();
		return directory != null || jar != null;
	}

	/** Where the manual was found, for the tool's reply and for diagnosing a packaged build. */
	public static String source() {
		locate();
		return available() ? source : "not found";
	}

	/** Directory the manual is read from, or null when it comes from the jar. */
	public static File root() {
		locate();
		return directory;
	}

	// ------------------------------------------------------------------
	// listing and reading
	// ------------------------------------------------------------------

	/** Languages that have a manual, read from the directory names under {@code doc/}. */
	public static synchronized List<String> languages() {
		locate();
		List<String> ret = new ArrayList<String>();
		if (directory != null) {
			File[] children = directory.listFiles();
			if (children != null) {
				for (File child : children) {
					if (child.isDirectory() && isDocsDirectory(child)) {
						ret.add(child.getName());
					}
				}
			}
		} else if (jar != null) {
			java.util.Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				String prefix = "doc/";
				if (!name.startsWith(prefix) || !name.endsWith("/html/index.html")) {
					continue;
				}
				String middle = name.substring(prefix.length(), name.length() - "/html/index.html".length());
				if (middle.indexOf('/') < 0 && !middle.isEmpty() && !ret.contains(middle)) {
					ret.add(middle);
				}
			}
		}
		Collections.sort(ret);
		return ret;
	}

	/**
	 * Every page of one language, as paths relative to its {@code html} directory, for
	 * example {@code libs/mem/flipflops.html}.
	 */
	public static synchronized List<String> pages(String language) {
		String lang = language == null || language.trim().isEmpty() ? DEFAULT_LANGUAGE : language.trim();
		if (cachedPages != null && lang.equals(cachedPagesLang)) {
			return cachedPages;
		}
		List<String> ret = new ArrayList<String>();
		if (directory != null) {
			File html = new File(directory, lang + File.separator + "html");
			collectPages(html, html, ret);
		} else if (jar != null) {
			String prefix = "doc/" + lang + "/html/";
			java.util.Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				if (name.startsWith(prefix) && name.endsWith(".html")) {
					ret.add(name.substring(prefix.length()));
				}
			}
		}
		Collections.sort(ret);
		cachedPages = ret;
		cachedPagesLang = lang;
		return ret;
	}

	private static void collectPages(File htmlRoot, File current, List<String> into) {
		File[] children = current.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			if (child.isDirectory()) {
				collectPages(htmlRoot, child, into);
			} else if (child.getName().endsWith(".html")) {
				String path = child.getAbsolutePath().substring(htmlRoot.getAbsolutePath().length() + 1);
				into.add(path.replace(File.separatorChar, '/'));
			}
		}
	}

	/** One page as raw HTML, or null when there is no such page. */
	public static synchronized String html(String language, String page) {
		locate();
		if (!available() || page == null || page.isEmpty()) {
			return null;
		}
		String lang = language == null || language.trim().isEmpty() ? DEFAULT_LANGUAGE : language.trim();
		String rel = page.replace('\\', '/');
		if (rel.startsWith("/")) {
			rel = rel.substring(1);
		}
		if (rel.contains("..")) {
			return null;
		}
		if (directory != null) {
			File target = new File(new File(directory, lang + File.separator + "html"), rel);
			try {
				String root = new File(directory, lang + File.separator + "html").getCanonicalPath();
				if (!target.getCanonicalPath().startsWith(root)) {
					return null;
				}
			} catch (IOException e) {
				return null;
			}
			if (!target.isFile()) {
				return null;
			}
			try {
				InputStream in = new FileInputStream(target);
				try {
					return readText(in);
				} finally {
					in.close();
				}
			} catch (IOException e) {
				return null;
			}
		}
		JarEntry entry = jar.getJarEntry("doc/" + lang + "/html/" + rel);
		if (entry == null) {
			return null;
		}
		try {
			InputStream in = jar.getInputStream(entry);
			try {
				return readText(in);
			} finally {
				in.close();
			}
		} catch (IOException e) {
			return null;
		}
	}

	private static String readText(InputStream in) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
		StringBuilder buf = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			buf.append(line).append('\n');
		}
		String text = buf.toString();
		if (text.indexOf('\uFFFD') >= 0) {
			// the page is not UTF-8 after all; the old Logisim pages are Latin-1
			try {
				byte[] raw = text.getBytes("ISO-8859-1");
				text = new String(raw, "ISO-8859-1");
			} catch (UnsupportedEncodingException e) {
				// UTF-8 is always available, and so is ISO-8859-1: this cannot happen
			}
		}
		return text;
	}

	/** One page as plain text, with the HTML taken out; empty when there is no such page. */
	public static synchronized String text(String language, String page) {
		String key = (language == null ? DEFAULT_LANGUAGE : language) + "/" + page;
		String cached = pageText.get(key);
		if (cached != null) {
			return cached;
		}
		String raw = html(language, page);
		if (raw == null) {
			return "";
		}
		String stripped = toText(raw);
		pageText.put(key, stripped);
		return stripped;
	}

	/** The {@code <title>} of a page, or its first heading, or the page name. */
	public static String title(String language, String page) {
		String raw = html(language, page);
		String ret = raw == null ? null : extract(raw, "<title>", "</title>");
		if (ret == null || ret.isEmpty()) {
			String text = text(language, page);
			int end = text.indexOf('\n');
			ret = end < 0 ? text : text.substring(0, end);
		}
		return ret == null ? page : ret.trim();
	}

	// ------------------------------------------------------------------
	// searching
	// ------------------------------------------------------------------

	/**
	 * Pages that mention the query, best first. Every word of the query has to appear, so
	 * a query is narrowed by adding words rather than widened, which suits an agent
	 * looking for one specific behaviour.
	 */
	public static synchronized List<Match> search(String language, String query, int limit) {
		String lang = language == null || language.trim().isEmpty() ? DEFAULT_LANGUAGE : language.trim();
		List<String> tokens = tokenize(query);
		List<Match> ret = new ArrayList<Match>();
		if (tokens.isEmpty()) {
			return ret;
		}
		for (String page : pages(lang)) {
			String body = text(lang, page).toLowerCase(Locale.ENGLISH);
			if (body.isEmpty()) {
				continue;
			}
			String heading = title(lang, page).toLowerCase(Locale.ENGLISH);
			int score = 0;
			boolean all = true;
			for (String token : tokens) {
				int seen = count(body, token);
				if (seen == 0) {
					all = false;
					break;
				}
				score += Math.min(seen, 20);
				if (heading.indexOf(token) >= 0) {
					score += 40;
				}
			}
			if (!all) {
				continue;
			}
			ret.add(new Match(page, title(lang, page), score, excerpt(lang, page, tokens)));
		}
		Collections.sort(ret, new java.util.Comparator<Match>() {
			@Override
			public int compare(Match a, Match b) {
				if (a.score != b.score) {
					return b.score - a.score;
				}
				return a.page.compareTo(b.page);
			}
		});
		if (limit > 0 && ret.size() > limit) {
			return new ArrayList<Match>(ret.subList(0, limit));
		}
		return ret;
	}

	private static List<String> tokenize(String query) {
		List<String> ret = new ArrayList<String>();
		if (query == null) {
			return ret;
		}
		for (String word : query.toLowerCase(Locale.ENGLISH).split("[^a-z0-9+.#-]+")) {
			String token = word.trim();
			if (token.length() >= 2 && !ret.contains(token)) {
				ret.add(token);
			}
		}
		return ret;
	}

	private static int count(String haystack, String needle) {
		int total = 0;
		int from = 0;
		while (true) {
			int at = haystack.indexOf(needle, from);
			if (at < 0) {
				return total;
			}
			total++;
			from = at + needle.length();
		}
	}

	/** The paragraph of a page in which the query words occur most often. */
	private static String excerpt(String language, String page, List<String> tokens) {
		String body = text(language, page);
		String[] paragraphs = body.split("\n\n");
		String best = null;
		int bestScore = 0;
		for (String paragraph : paragraphs) {
			String lower = paragraph.toLowerCase(Locale.ENGLISH);
			int score = 0;
			for (String token : tokens) {
				score += count(lower, token);
			}
			if (score > bestScore) {
				bestScore = score;
				best = paragraph;
			}
		}
		if (best == null) {
			best = paragraphs.length == 0 ? body : paragraphs[0];
		}
		String flat = best.replace('\n', ' ').trim();
		if (flat.length() > 500) {
			flat = flat.substring(0, 500) + "...";
		}
		return flat;
	}

	// ------------------------------------------------------------------
	// HTML to text
	// ------------------------------------------------------------------

	/** Turns one HTML page into readable plain text. */
	public static String toText(String html) {
		if (html == null) {
			return "";
		}
		String body = html;
		body = stripBlocks(body, "script");
		body = stripBlocks(body, "style");
		StringBuilder buf = new StringBuilder(body.length());
		int i = 0;
		while (i < body.length()) {
			int open = body.indexOf('<', i);
			if (open < 0) {
				buf.append(body, i, body.length());
				break;
			}
			buf.append(body, i, open);
			int close = body.indexOf('>', open);
			if (close < 0) {
				break;
			}
			String tag = body.substring(open + 1, close).trim().toLowerCase(Locale.ENGLISH);
			if (tag.startsWith("br") || tag.startsWith("p") || tag.startsWith("/p") || tag.equals("/div")
					|| tag.startsWith("li") || tag.startsWith("/li") || tag.startsWith("tr") || tag.equals("/tr")
					|| tag.startsWith("h1") || tag.startsWith("h2") || tag.startsWith("h3") || tag.startsWith("h4")
					|| tag.startsWith("/h1") || tag.startsWith("/h2") || tag.startsWith("/h3") || tag.startsWith("/h4")
					|| tag.startsWith("table") || tag.startsWith("/table")) {
				buf.append('\n');
			}
			i = close + 1;
		}
		String text = buf.toString();
		text = text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
				.replace("&#39;", "'").replace("&apos;", "'").replace("&#8217;", "'").replace("&mdash;", "-")
				.replace("&ndash;", "-").replace("&amp;", "&");
		StringBuilder cleaned = new StringBuilder(text.length());
		int blank = 0;
		for (String line : text.split("\n")) {
			String trimmed = line.replace('\t', ' ').replaceAll("[ ]{2,}", " ").trim();
			if (trimmed.isEmpty()) {
				blank++;
				if (blank > 1) {
					continue;
				}
			} else {
				blank = 0;
			}
			cleaned.append(trimmed).append('\n');
		}
		return cleaned.toString().trim();
	}

	private static String stripBlocks(String html, String tag) {
		String lower = html.toLowerCase(Locale.ENGLISH);
		StringBuilder buf = new StringBuilder(html.length());
		int i = 0;
		while (true) {
			int start = lower.indexOf("<" + tag, i);
			if (start < 0) {
				buf.append(html, i, html.length());
				return buf.toString();
			}
			buf.append(html, i, start);
			int end = lower.indexOf("</" + tag, start);
			if (end < 0) {
				return buf.toString();
			}
			int after = lower.indexOf('>', end);
			i = after < 0 ? html.length() : after + 1;
		}
	}

	private static String extract(String html, String open, String close) {
		int start = html.indexOf(open);
		if (start < 0) {
			return null;
		}
		int end = html.indexOf(close, start + open.length());
		if (end < 0) {
			return null;
		}
		return html.substring(start + open.length(), end).replace('\n', ' ').trim();
	}
}
