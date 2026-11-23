package org.bloqqi.web;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON messages exchanged over the WebSocket.
 *
 * <pre>
 * server -> client  {"type":"workspace", "files":[...]}                  (on connect)
 * client -> server  {"type":"open", "file":"PLib.dia", "diagramType":"PTrack"}   (diagramType optional)
 * client -> server  {"type":"move", "file", "diagramType", "nodes":[{id,x,y}]}
 * client -> server  {"type":"connect", "file", "diagramType",
 *                    "source":{node,handle}, "target":{node,handle}}
 * client -> server  {"type":"delete", "file", "diagramType", "edges":[id,...], "nodes":[id,...]}
 *                   (nodes: blocks; optional)
 * client -> server  {"type":"addBlock", "file", "diagramType", "typeName", "x", "y"}
 * client -> server  {"type":"addLiteral", "file", "diagramType", "target":{node,handle}, "value"}
 * client -> server  {"type":"addVariable", "file", "diagramType", "kind", "name", "typeName", "x", "y"}
 *                   (kind: var, input or output)
 * client -> server  {"type":"addParameter", "file", "diagramType", "direction", "name", "typeName", "x", "y"}
 *                   (direction: in or out)
 *                   (edits write the file and are answered with the new "diagram",
 *                    whose "change" is {before, after}: the file's text before and
 *                    after the edit; or with an "error" and the file unchanged)
 * client -> server  {"type":"restore", "file", "diagramType", "text", "expected"}
 *                   (undo/redo: writes "text" if the file still contains "expected";
 *                    answered with the "diagram" and its "change", or an "error")
 * server -> client  {"type":"diagram", "file", "diagramType", "diagramTypes":[...],
 *                    "nodes":[{id,kind,label,typeName,inherited,inlined,error,x,y,width,height,
 *                              inPorts:[{name,typeName,y,connectable}], outPorts:[...],
 *                              inConnectable,outConnectable,deletable}],
 *                    "edges":[{id,source,sourceHandle,target,targetHandle,
 *                              inherited,deletable,error,fromLiteral,intercept,largeMarker}],
 *                    "problems":[{line,column,message}],
 *                    "readOnly": null or why the diagram cannot be edited,
 *                    "palette":[{name,group,kind,doc}],
 *                    "change": null, or {before, after} if the request changed the file}
 * server -> client  {"type":"error", "message"}
 * </pre>
 *
 * Gson 2.6.2 (the version the compiler uses) does not know records, so the
 * JSON is built explicitly.
 */
final class Protocol {
	/** Explicit nulls: e.g. "diagramType": null when the file has no diagram type. */
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();

	private Protocol() {
	}

	/** A client -> server message. */
	sealed interface Request
			permits OpenRequest, MoveRequest, ConnectRequest, DeleteRequest, AddBlockRequest, AddLiteralRequest,
			AddVariableRequest, AddParameterRequest, RestoreRequest {
		String file();
	}

	/** Show a diagram type of a file. */
	record OpenRequest(String file, String diagramType) implements Request {
	}

	/** Move nodes of a diagram type and save the file. */
	record MoveRequest(String file, String diagramType, List<DiagramEdits.Move> moves) implements Request {
	}

	/** Connect two handles and save the file. */
	record ConnectRequest(String file, String diagramType, DiagramBuilder.Endpoint source,
			DiagramBuilder.Endpoint target) implements Request {
	}

	/** Delete connections (edge ids) and blocks (node ids) and save the file. */
	record DeleteRequest(String file, String diagramType, List<String> edges, List<String> nodes)
			implements Request {
	}

	/** Add a block of a palette type at (x, y) and save the file. */
	record AddBlockRequest(String file, String diagramType, String typeName, int x, int y) implements Request {
	}

	/** Connect a literal to a free target and save the file. */
	record AddLiteralRequest(String file, String diagramType, DiagramBuilder.Endpoint target, String value)
			implements Request {
	}

	/** Add a variable at (x, y) and save the file. */
	record AddVariableRequest(String file, String diagramType, String kind, String name, String typeName,
			int x, int y) implements Request {
	}

	/** Add an in- or out-parameter at (x, y) and save the file. */
	record AddParameterRequest(String file, String diagramType, boolean in, String name, String typeName,
			int x, int y) implements Request {
	}

	/** Undo/redo: replace the file's text with {@code text} if it is still {@code expected}. */
	record RestoreRequest(String file, String diagramType, String text, String expected) implements Request {
	}

	/** How a request changed a file: its text before and after. */
	record Change(String before, String after) {
	}

	/**
	 * Parses a client message; returns null for unknown message types.
	 *
	 * @throws IllegalArgumentException for malformed messages
	 */
	static Request parseRequest(String text) {
		JsonElement element;
		try {
			element = new JsonParser().parse(text);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Malformed JSON: " + e.getMessage());
		}
		if (!element.isJsonObject()) {
			throw new IllegalArgumentException("Expected a JSON object");
		}
		JsonObject o = element.getAsJsonObject();
		String type = string(o, "type");
		if ("open".equals(type)) {
			return new OpenRequest(string(o, "file"), string(o, "diagramType"));
		}
		if ("move".equals(type)) {
			String diagramType = string(o, "diagramType");
			JsonElement nodes = o.get("nodes");
			if (diagramType == null || nodes == null || !nodes.isJsonArray()) {
				throw new IllegalArgumentException("move needs diagramType and nodes");
			}
			List<DiagramEdits.Move> moves = new ArrayList<>();
			for (JsonElement e : nodes.getAsJsonArray()) {
				JsonObject n = e.getAsJsonObject();
				moves.add(new DiagramEdits.Move(string(n, "id"), integer(n, "x"), integer(n, "y")));
			}
			return new MoveRequest(string(o, "file"), diagramType, moves);
		}
		if ("connect".equals(type)) {
			return new ConnectRequest(string(o, "file"), required(o, "diagramType"),
					endpoint(o, "source"), endpoint(o, "target"));
		}
		if ("delete".equals(type)) {
			return new DeleteRequest(string(o, "file"), required(o, "diagramType"),
					strings(o, "edges"), strings(o, "nodes"));
		}
		if ("addBlock".equals(type)) {
			return new AddBlockRequest(string(o, "file"), required(o, "diagramType"), required(o, "typeName"),
					integer(o, "x"), integer(o, "y"));
		}
		if ("addLiteral".equals(type)) {
			return new AddLiteralRequest(string(o, "file"), required(o, "diagramType"), endpoint(o, "target"),
					required(o, "value"));
		}
		if ("addVariable".equals(type)) {
			return new AddVariableRequest(string(o, "file"), required(o, "diagramType"), required(o, "kind"),
					required(o, "name"), required(o, "typeName"), integer(o, "x"), integer(o, "y"));
		}
		if ("addParameter".equals(type)) {
			String direction = required(o, "direction");
			if (!direction.equals("in") && !direction.equals("out")) {
				throw new IllegalArgumentException("direction must be in or out");
			}
			return new AddParameterRequest(string(o, "file"), required(o, "diagramType"), direction.equals("in"),
					required(o, "name"), required(o, "typeName"), integer(o, "x"), integer(o, "y"));
		}
		if ("restore".equals(type)) {
			return new RestoreRequest(string(o, "file"), string(o, "diagramType"),
					required(o, "text"), required(o, "expected"));
		}
		return null;
	}

	static String workspace(List<String> files) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "workspace");
		o.add("files", strings(files));
		return GSON.toJson(o);
	}

	static String diagram(Diagram d) {
		return diagram(d, null);
	}

	/** A diagram, with the change that the request made to the file (or null). */
	static String diagram(Diagram d, Change change) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "diagram");
		o.addProperty("file", d.file());
		o.addProperty("diagramType", d.diagramType());
		o.add("diagramTypes", strings(d.diagramTypes()));
		JsonArray nodes = new JsonArray();
		for (Diagram.DiagramNode n : d.nodes()) {
			JsonObject jn = new JsonObject();
			jn.addProperty("id", n.id());
			jn.addProperty("kind", n.kind());
			jn.addProperty("label", n.label());
			jn.addProperty("typeName", n.typeName());
			jn.addProperty("inherited", n.inherited());
			jn.addProperty("inlined", n.inlined());
			jn.addProperty("error", n.error());
			jn.addProperty("x", n.x());
			jn.addProperty("y", n.y());
			jn.addProperty("width", n.width());
			jn.addProperty("height", n.height());
			jn.add("inPorts", ports(n.inPorts()));
			jn.add("outPorts", ports(n.outPorts()));
			jn.addProperty("inConnectable", n.inConnectable());
			jn.addProperty("outConnectable", n.outConnectable());
			jn.addProperty("deletable", n.deletable());
			nodes.add(jn);
		}
		o.add("nodes", nodes);
		JsonArray edges = new JsonArray();
		for (Diagram.DiagramEdge e : d.edges()) {
			JsonObject je = new JsonObject();
			je.addProperty("id", e.id());
			je.addProperty("source", e.source());
			je.addProperty("sourceHandle", e.sourceHandle());
			je.addProperty("target", e.target());
			je.addProperty("targetHandle", e.targetHandle());
			je.addProperty("inherited", e.inherited());
			je.addProperty("deletable", e.deletable());
			je.addProperty("error", e.error());
			je.addProperty("fromLiteral", e.fromLiteral());
			je.addProperty("intercept", e.intercept());
			je.addProperty("largeMarker", e.largeMarker());
			edges.add(je);
		}
		o.add("edges", edges);
		JsonArray problems = new JsonArray();
		for (Problem p : d.problems()) {
			JsonObject jp = new JsonObject();
			jp.addProperty("line", p.line());
			jp.addProperty("column", p.column());
			jp.addProperty("message", p.message());
			problems.add(jp);
		}
		o.add("problems", problems);
		o.addProperty("readOnly", d.readOnly());
		JsonArray palette = new JsonArray();
		for (Diagram.PaletteEntry p : d.palette()) {
			JsonObject jp = new JsonObject();
			jp.addProperty("name", p.name());
			jp.addProperty("group", p.group());
			jp.addProperty("kind", p.kind());
			jp.addProperty("doc", p.doc());
			palette.add(jp);
		}
		o.add("palette", palette);
		if (change == null) {
			o.add("change", com.google.gson.JsonNull.INSTANCE);
		} else {
			JsonObject c = new JsonObject();
			c.addProperty("before", change.before());
			c.addProperty("after", change.after());
			o.add("change", c);
		}
		return GSON.toJson(o);
	}

	static String error(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "error");
		o.addProperty("message", message);
		return GSON.toJson(o);
	}

	private static JsonArray ports(List<Diagram.DiagramPort> ports) {
		JsonArray a = new JsonArray();
		for (Diagram.DiagramPort p : ports) {
			JsonObject jp = new JsonObject();
			jp.addProperty("name", p.name());
			jp.addProperty("typeName", p.typeName());
			jp.addProperty("y", p.y());
			jp.addProperty("connectable", p.connectable());
			a.add(jp);
		}
		return a;
	}

	private static JsonArray strings(List<String> values) {
		JsonArray a = new JsonArray();
		for (String v : values) {
			a.add(v);
		}
		return a;
	}

	private static DiagramBuilder.Endpoint endpoint(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonObject()) {
			throw new IllegalArgumentException("Missing " + key);
		}
		return new DiagramBuilder.Endpoint(required(e.getAsJsonObject(), "node"), required(e.getAsJsonObject(), "handle"));
	}

	/** An optional array of strings (empty if missing). */
	private static List<String> strings(JsonObject o, String key) {
		JsonElement e = o.get(key);
		List<String> values = new ArrayList<>();
		if (e != null && e.isJsonArray()) {
			for (JsonElement v : e.getAsJsonArray()) {
				values.add(v.getAsString());
			}
		}
		return values;
	}

	private static String required(JsonObject o, String key) {
		String value = string(o, key);
		if (value == null) {
			throw new IllegalArgumentException("Missing " + key);
		}
		return value;
	}

	private static int integer(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			throw new IllegalArgumentException("Missing " + key);
		}
		return (int) Math.round(e.getAsDouble());
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}
}
