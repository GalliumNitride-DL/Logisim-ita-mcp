/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.mcp.Json;
import com.cburch.logisim.mcp.McpException;
import com.cburch.logisim.mcp.McpInstructions;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A picture of a circuit, for the one thing text cannot do: let a reader see the schematic.
 *
 * <p>
 * Logisim can already draw a circuit into an image - that is what its "Export As Image"
 * dialog does - and the drawing code needs no window: {@link ComponentDrawContext} takes any
 * {@code java.awt.Component} as the destination and a {@code Graphics} to draw on, so a
 * {@code BufferedImage} is enough. That is why this works in headless mode too, where there
 * is no canvas to copy from.
 * </p>
 *
 * <p>
 * The picture comes back as an MCP image content block, so a client that can show images
 * shows it; everything else about the answer stays text. The base64 is deliberately not
 * repeated in the text or in the structured copy, because it is often a few hundred
 * kilobytes.
 * </p>
 */
public final class ImageTools {
	/** How large a picture one call may produce, so a mistake cannot exhaust the heap. */
	private static final int MAX_PIXELS = 4000000;

	/** Space left around the circuit, in circuit pixels. */
	private static final int MARGIN = 20;

	/**
	 * The destination the drawing code asks for font metrics. Any component will do, and a
	 * plain panel can be created where no window is allowed.
	 */
	private static java.awt.Component destination;

	private ImageTools() {
	}

	public static McpTool screenshot(final McpSession session) {
		return new AbstractTool("logisim_screenshot", McpInstructions.SCREENSHOT,
				Schemas.object()
						.opt("circuit", Schemas.string("Circuit to draw; defaults to the current circuit"))
						.opt("region", Schemas.any("The part of the circuit to draw, as [x, y, width, height]. "
								+ "Defaults to everything the circuit uses, plus a small margin"))
						.opt("scale", Schemas.number("Pixels per circuit pixel (default 1; up to 4 for a "
								+ "readable picture of a large circuit, down to 0.2 for an overview)"))
						.opt("max_pixels", Schemas.integer("Refuse to draw more than this many pixels (default "
								+ MAX_PIXELS + "), which is what stops one call from exhausting the heap"))
						.opt("print_view", Schemas.bool("Draw the print view instead: no state colours and no "
								+ "grey backgrounds, as the export dialog's option of the same name"))
						.opt("transparent", Schemas.bool("Leave the background transparent instead of white, "
								+ "which is what a dark client theme needs"))
						.opt("path", Schemas.string("Also write the PNG to this file, so the user can look at "
								+ "it without an image-capable client"))
						.opt("circuit_bounds", Schemas.bool("Report the rectangle that was drawn, so the next "
								+ "call can ask for a part of it (default true)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				double scale = ctx.optDouble("scale", Double.valueOf(1)).doubleValue();
				if (scale <= 0 || scale > 4) {
					throw new McpException("'scale' is pixels per circuit pixel; it must be above 0 and at most 4, "
							+ "but was " + scale);
				}
				int maxPixels = ctx.optInt("max_pixels", Integer.valueOf(MAX_PIXELS)).intValue();
				boolean printView = ctx.optBool("print_view", false);
				boolean transparent = ctx.optBool("transparent", false);

				Bounds wanted = circuitBounds(circuit);
				if (wanted.getWidth() <= 0 || wanted.getHeight() <= 0) {
					throw new McpException("circuit '" + circuit.getName() + "' is empty, so there is nothing to "
							+ "draw: place something in it first");
				}
				JsonElement region = ctx.optElement("region");
				if (region != null && !region.isJsonNull()) {
					wanted = region(region);
				} else {
					wanted = wanted.expand(MARGIN);
				}

				int width = (int) Math.round(wanted.getWidth() * scale);
				int height = (int) Math.round(wanted.getHeight() * scale);
				if (width <= 0 || height <= 0) {
					throw new McpException("that region and scale come to a picture with no size; use a larger "
							+ "scale or a bigger region");
				}
				long pixels = (long) width * (long) height;
				if (pixels > maxPixels) {
					throw new McpException("that would be " + width + " by " + height + " pixels (" + pixels
							+ "), which is more than max_pixels (" + maxPixels + "). Lower 'scale', give a "
							+ "'region', or raise 'max_pixels' deliberately");
				}

				byte[] png = render(session, circuit, wanted, scale, printView, transparent, width, height);
				String base64 = Base64.getEncoder().encodeToString(png);

				JsonObject image = new JsonObject();
				image.addProperty("data", base64);
				image.addProperty("mimeType", "image/png");
				image.addProperty("width", Integer.valueOf(width));
				image.addProperty("height", Integer.valueOf(height));
				image.addProperty("bytes", Integer.valueOf(png.length));

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.add("image", image);
				if (ctx.optBool("circuit_bounds", true)) {
					ret.add("drawn", Json.array(Integer.valueOf(wanted.getX()), Integer.valueOf(wanted.getY()),
							Integer.valueOf(wanted.getWidth()), Integer.valueOf(wanted.getHeight())));
				}
				JsonElement path = ctx.optElement("path");
				if (path != null && !path.isJsonNull()) {
					File file = new File(path.getAsString());
					write(png, file);
					ret.addProperty("saved", file.getAbsolutePath());
					ret.addProperty("saved_bytes", Integer.valueOf(png.length));
				}
				ret.addProperty("hint", "this is a picture of the circuit as the user sees it. Use it to check "
						+ "that the layout reads well, and logisim_circuit_nets to check what is connected to "
						+ "what: a wire that looks joined on screen and a wire that is joined are not the same "
						+ "thing. Pass 'region' and 'scale' to look closely at one part");
				return ret;
			}
		};
	}

	/** The rectangle the circuit's components and wires occupy. */
	private static Bounds circuitBounds(Circuit circuit) {
		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		Graphics g = probe.getGraphics();
		try {
			return circuit.getBounds(g);
		} finally {
			g.dispose();
		}
	}

	/** Reads a [x, y, width, height] region. */
	private static Bounds region(JsonElement element) {
		if (!element.isJsonArray() || element.getAsJsonArray().size() != 4) {
			throw new McpException("'region' is [x, y, width, height], for example [0, 0, 400, 300]");
		}
		JsonArray values = element.getAsJsonArray();
		try {
			return Bounds.create(values.get(0).getAsInt(), values.get(1).getAsInt(), values.get(2).getAsInt(),
					values.get(3).getAsInt());
		} catch (RuntimeException e) {
			throw new McpException("'region' is four whole numbers: [x, y, width, height]");
		}
	}

	/**
	 * Draws a circuit into a PNG.
	 *
	 * <p>
	 * This is the same thing Logisim's export dialog does, minus the dialog: the image's own
	 * graphics are scaled and translated to the region, and the circuit paints itself through
	 * a {@link ComponentDrawContext}. The state is read from the project, so an unpropagated
	 * circuit draws as it is rather than being simulated behind the agent's back.
	 * </p>
	 */
	private static byte[] render(McpSession session, Circuit circuit, Bounds area, double scale, boolean printView,
			boolean transparent, int width, int height) {
		BufferedImage img = new BufferedImage(width, height,
				transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics base = img.getGraphics();
		Graphics g = base.create();
		try {
			if (!transparent) {
				g.setColor(Color.WHITE);
				g.fillRect(0, 0, width, height);
			}
			g.setColor(Color.BLACK);
			if (g instanceof Graphics2D) {
				((Graphics2D) g).scale(scale, scale);
				((Graphics2D) g).translate(-area.getX(), -area.getY());
			}
			CircuitState state = session.stateFor(circuit);
			ComponentDrawContext context = new ComponentDrawContext(destination(), circuit, state, base, g, printView);
			circuit.draw(context, null);
		} finally {
			g.dispose();
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(img, "PNG", out)) {
				throw new McpException("this build cannot write PNG images");
			}
			return out.toByteArray();
		} catch (java.io.IOException e) {
			throw new McpException("could not encode the picture: " + e);
		}
	}

	private static java.awt.Component destination() {
		if (destination == null) {
			destination = new javax.swing.JPanel();
		}
		return destination;
	}

	private static void write(byte[] png, File file) {
		FileOutputStream out = null;
		try {
			out = new FileOutputStream(file);
			out.write(png);
		} catch (java.io.IOException e) {
			throw new McpException("could not write " + file.getAbsolutePath() + ": " + e);
		} finally {
			if (out != null) {
				try {
					out.close();
				} catch (java.io.IOException e) {
					// nothing useful to do about a failure to close
				}
			}
		}
	}
}
