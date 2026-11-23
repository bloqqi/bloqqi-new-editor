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
 *                   (writes the file; answered with the new "diagram")
 * server -> client  {"type":"diagram", "file", "diagramType", "diagramTypes":[...],
 *                    "nodes":[{id,kind,label,typeName,inherited,inlined,error,x,y,width,height,
 *                              inPorts:[{name,typeName,y}], outPorts:[...]}],
 *                    "edges":[{id,source,sourceHandle,target,targetHandle,
 *                              inherited,error,fromLiteral,intercept,largeMarker}],
 *                    "problems":[{line,column,message}],
 *                    "readOnly": null or why the diagram cannot be edited}
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
	sealed interface Request permits OpenRequest, MoveRequest {
		String file();
	}

	/** Show a diagram type of a file. */
	record OpenRequest(String file, String diagramType) implements Request {
	}

	/** Move nodes of a diagram type and save the file. */
	record MoveRequest(String file, String diagramType, List<DiagramEdits.Move> moves) implements Request {
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
		return null;
	}

	static String workspace(List<String> files) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "workspace");
		o.add("files", strings(files));
		return GSON.toJson(o);
	}

	static String diagram(Diagram d) {
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
