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
 * client -> server  {"type":"rename", "file", "diagramType", "node", "name"}
 * client -> server  {"type":"setLiteral", "file", "diagramType", "literal", "value"}
 * client -> server  {"type":"autoLayout", "file", "diagramType"}
 * client -> server  {"type":"addDiagramType", "file", "name"}   (answered with the new type's diagram)
 * client -> server  {"type":"addExternalFunction", "file", "diagramType", "name",
 *                    "inputs":[{name,typeName}], "output": {name,typeName} or null}
 *                   (answered with the diagram of "diagramType")
 * client -> server  {"type":"renameType", "file", "diagramType", "typeName", "name"}
 * client -> server  {"type":"deleteType", "file", "diagramType", "typeName"}
 *                   (both answered with the shown diagramType, renamed or, if deleted, the first)
 * client -> server  {"type":"addSubtype", "file", "superType", "name"}   (answered with the new type)
 * client -> server  {"type":"setSuperTypes", "file", "typeName", "superTypes":[...]}
 * client -> server  {"type":"setParameters", "file", "diagramType", "inParameters":[...],
 *                    "outParameters":[...]}   (the type's own parameters in their new order;
 *                   those left out are deleted)
 * client -> server  {"type":"createFile", "file", "diagramType"}   (file: the new file; diagramType:
 *                   its first type, or null for an empty file; answered with the new file)
 * client -> server  {"type":"renameFile", "file", "newName", "diagramType"}   (answered with the renamed file)
 * client -> server  {"type":"deleteFile", "file"}   (answered with the first remaining file)
 * client -> server  {"type":"intercept", "file", "diagramType", "edge", "atSource",
 *                    "into":{node,handle}, "outOf":{node,handle}}
 *                   (edits write the file and are answered with the new "diagram",
 *                    whose "change" is {before, after}: the file's text before and
 *                    after the edit; or with an "error" and the file unchanged)
 * client -> server  {"type":"restore", "file", "diagramType", "text", "expected"}
 *                   (undo/redo: writes "text" if the file still contains "expected";
 *                    answered with the "diagram" and its "change", or an "error")
 * client -> server  {"type":"setText", "file", "diagramType", "text", "expected"}
 *                   (the text view's save: like restore, but the text may have errors;
 *                    the diagram is of "diagramType", or of the first type if it is gone)
 * client -> server  {"type":"checkText", "file", "text"}
 *                   (answered with {"type":"problems", "problems":[{line,column,message}]}
 *                    for that text; nothing is written)
 * client -> server  {"type":"generateC", "file", "variant"}   ("minimal" or "with-driver")
 *                   (answered with {"type":"cCode", "variant", "code": the C code, or null
 *                    if the compiler would refuse, "problems":[...] why; see CCode)
 * server -> client  {"type":"diagram", "file", "diagramType", "diagramTypes":[...],
 *                    "nodes":[{id,kind,label,typeName,inherited,inlined,error,x,y,width,height,
 *                              inPorts:[{name,typeName,y,connectable}], outPorts:[...],
 *                              inConnectable,outConnectable,deletable}],
 *                    "edges":[{id,source,sourceHandle,target,targetHandle,
 *                              inherited,deletable,interceptable,error,fromLiteral,intercept,largeMarker}],
 *                    "problems":[{line,column,message}],
 *                    "readOnly": null or why the diagram cannot be edited,
 *                    "palette":[{name,group,kind,doc}], "superTypes":[...],
 *                    "text": the file's text the diagram was built from,
 *                    "change": null, or {before, after} if the request changed the file}
 * server -> client  {"type":"error", "message"}
 *
 * Messages the server sends on its own (not answers to a request):
 * server -> client  {"type":"update", ...as "diagram"...}  the shown file changed on disk
 * server -> client  {"type":"files", "files":[...]}        the workspace's files changed
 * server -> client  {"type":"notice", "message"}           e.g. the shown file was deleted
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
			AddVariableRequest, AddParameterRequest, RenameRequest, SetLiteralRequest, AutoLayoutRequest,
			AddDiagramTypeRequest, AddExternalFunctionRequest, RenameTypeRequest, DeleteTypeRequest,
			AddSubtypeRequest, SetSuperTypesRequest, SetParametersRequest, CreateFileRequest, RenameFileRequest, DeleteFileRequest,
			InterceptRequest,
			RestoreRequest, SetTextRequest, CheckTextRequest, GenerateCRequest {
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

	/** Rename a block, variable or parameter (and its uses) and save the file. */
	record RenameRequest(String file, String diagramType, String node, String name) implements Request {
	}

	/** Change a literal's value and save the file. */
	record SetLiteralRequest(String file, String diagramType, String literal, String value) implements Request {
	}

	/** Lay out the whole type with ELK and save the file. */
	record AutoLayoutRequest(String file, String diagramType) implements Request {
	}

	/** Add an empty diagram type and show it. */
	record AddDiagramTypeRequest(String file, String name) implements Request {
	}

	/** Add an external function; keep showing {@code diagramType} (may be null). */
	record AddExternalFunctionRequest(String file, String diagramType, String name,
			List<DiagramEdits.Parameter> inputs, DiagramEdits.Parameter output) implements Request {
	}

	/** Rename a diagram type or external function; {@code diagramType} is the shown one. */
	record RenameTypeRequest(String file, String diagramType, String typeName, String name) implements Request {
	}

	/** Delete a diagram type or external function; {@code diagramType} is the shown one. */
	record DeleteTypeRequest(String file, String diagramType, String typeName) implements Request {
	}

	/** Add {@code diagramtype <name> extends <superType>} and show it. */
	record AddSubtypeRequest(String file, String superType, String name) implements Request {
	}

	/** Set the super types of a diagram type. */
	record SetSuperTypesRequest(String file, String typeName, List<String> superTypes) implements Request {
	}

	/** Reorder (and delete) the shown type's own parameters. */
	record SetParametersRequest(String file, String diagramType, List<String> inParameters,
			List<String> outParameters) implements Request {
	}

	/** Create a file, optionally with a first, empty diagram type, and show it. */
	record CreateFileRequest(String file, String diagramType) implements Request {
	}

	/** Rename a file; keep showing {@code diagramType} in it. */
	record RenameFileRequest(String file, String newName, String diagramType) implements Request {
	}

	/** Delete a file and show another. */
	record DeleteFileRequest(String file) implements Request {
	}

	/** Intercept an inherited connection (at its target, or at its source) and save the file. */
	record InterceptRequest(String file, String diagramType, String edge, boolean atSource,
			DiagramBuilder.Endpoint into, DiagramBuilder.Endpoint outOf) implements Request {
	}

	/** Undo/redo: replace the file's text with {@code text} if it is still {@code expected}. */
	record RestoreRequest(String file, String diagramType, String text, String expected) implements Request {
	}

	/** The text view's save: replace the file's text with {@code text} if it is still {@code expected}. */
	record SetTextRequest(String file, String diagramType, String text, String expected) implements Request {
	}

	/** The problems {@code text} would have (the text view, while typing); nothing is written. */
	record CheckTextRequest(String file, String text) implements Request {
	}

	/** The C code the compiler generates for the file (the C view); nothing is written. */
	record GenerateCRequest(String file, String variant) implements Request {
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
		if ("rename".equals(type)) {
			return new RenameRequest(string(o, "file"), required(o, "diagramType"), required(o, "node"),
					required(o, "name"));
		}
		if ("setLiteral".equals(type)) {
			return new SetLiteralRequest(string(o, "file"), required(o, "diagramType"), required(o, "literal"),
					required(o, "value"));
		}
		if ("autoLayout".equals(type)) {
			return new AutoLayoutRequest(string(o, "file"), required(o, "diagramType"));
		}
		if ("addDiagramType".equals(type)) {
			return new AddDiagramTypeRequest(string(o, "file"), required(o, "name"));
		}
		if ("addExternalFunction".equals(type)) {
			List<DiagramEdits.Parameter> inputs = new ArrayList<>();
			JsonElement in = o.get("inputs");
			if (in != null && in.isJsonArray()) {
				for (JsonElement e : in.getAsJsonArray()) {
					inputs.add(parameter(e.getAsJsonObject()));
				}
			}
			JsonElement out = o.get("output");
			return new AddExternalFunctionRequest(string(o, "file"), string(o, "diagramType"), required(o, "name"),
					inputs, out == null || out.isJsonNull() ? null : parameter(out.getAsJsonObject()));
		}
		if ("renameType".equals(type)) {
			return new RenameTypeRequest(string(o, "file"), string(o, "diagramType"), required(o, "typeName"),
					required(o, "name"));
		}
		if ("deleteType".equals(type)) {
			return new DeleteTypeRequest(string(o, "file"), string(o, "diagramType"), required(o, "typeName"));
		}
		if ("addSubtype".equals(type)) {
			return new AddSubtypeRequest(string(o, "file"), required(o, "superType"), required(o, "name"));
		}
		if ("setSuperTypes".equals(type)) {
			return new SetSuperTypesRequest(string(o, "file"), required(o, "typeName"), strings(o, "superTypes"));
		}
		if ("setParameters".equals(type)) {
			return new SetParametersRequest(string(o, "file"), required(o, "diagramType"),
					strings(o, "inParameters"), strings(o, "outParameters"));
		}
		if ("createFile".equals(type)) {
			return new CreateFileRequest(required(o, "file"), string(o, "diagramType"));
		}
		if ("renameFile".equals(type)) {
			return new RenameFileRequest(required(o, "file"), required(o, "newName"), string(o, "diagramType"));
		}
		if ("deleteFile".equals(type)) {
			return new DeleteFileRequest(required(o, "file"));
		}
		if ("intercept".equals(type)) {
			JsonElement atSource = o.get("atSource");
			return new InterceptRequest(string(o, "file"), required(o, "diagramType"), required(o, "edge"),
					atSource != null && atSource.getAsBoolean(), endpoint(o, "into"), endpoint(o, "outOf"));
		}
		if ("restore".equals(type)) {
			return new RestoreRequest(string(o, "file"), string(o, "diagramType"),
					required(o, "text"), required(o, "expected"));
		}
		if ("setText".equals(type)) {
			return new SetTextRequest(required(o, "file"), string(o, "diagramType"),
					required(o, "text"), required(o, "expected"));
		}
		if ("generateC".equals(type)) {
			return new GenerateCRequest(required(o, "file"), required(o, "variant"));
		}
		if ("checkText".equals(type)) {
			return new CheckTextRequest(required(o, "file"), required(o, "text"));
		}
		return null;
	}

	static String workspace(List<String> files) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "workspace");
		o.add("files", strings(files));
		return GSON.toJson(o);
	}

	/** A diagram pushed because its file changed on disk (not an answer); {@code text} is the file's. */
	static String update(Diagram d, String text) {
		JsonObject o = diagramJson(d, null, text);
		o.addProperty("type", "update");
		return GSON.toJson(o);
	}

	/** The workspace's files, pushed when they change (not an answer). */
	static String files(List<String> files) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "files");
		o.add("files", strings(files));
		return GSON.toJson(o);
	}

	/** A message for the user, pushed (not an answer). */
	static String notice(String message) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "notice");
		o.addProperty("message", message);
		return GSON.toJson(o);
	}

	/**
	 * A diagram, built from the file's {@code text}, with the change that the
	 * request made to the file (or null).
	 */
	static String diagram(Diagram d, Change change, String text) {
		return GSON.toJson(diagramJson(d, change, text));
	}

	/** The answer to "checkText". */
	static String problems(List<Problem> problems) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "problems");
		o.add("problems", problemsJson(problems));
		return GSON.toJson(o);
	}

	/** The answer to "generateC". */
	static String cCode(String variant, CCode.Result result) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "cCode");
		o.addProperty("variant", variant);
		o.addProperty("code", result.code());
		o.add("problems", problemsJson(result.problems()));
		return GSON.toJson(o);
	}

	private static JsonArray problemsJson(List<Problem> problems) {
		JsonArray array = new JsonArray();
		for (Problem p : problems) {
			JsonObject jp = new JsonObject();
			jp.addProperty("line", p.line());
			jp.addProperty("column", p.column());
			jp.addProperty("message", p.message());
			array.add(jp);
		}
		return array;
	}

	private static JsonObject diagramJson(Diagram d, Change change, String text) {
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
			je.addProperty("interceptable", e.interceptable());
			je.addProperty("error", e.error());
			je.addProperty("fromLiteral", e.fromLiteral());
			je.addProperty("intercept", e.intercept());
			je.addProperty("largeMarker", e.largeMarker());
			edges.add(je);
		}
		o.add("edges", edges);
		o.add("problems", problemsJson(d.problems()));
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
		o.add("superTypes", strings(d.superTypes()));
		o.addProperty("text", text);
		if (change == null) {
			o.add("change", com.google.gson.JsonNull.INSTANCE);
		} else {
			JsonObject c = new JsonObject();
			c.addProperty("before", change.before());
			c.addProperty("after", change.after());
			o.add("change", c);
		}
		return o;
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

	private static DiagramEdits.Parameter parameter(JsonObject o) {
		return new DiagramEdits.Parameter(required(o, "name"), required(o, "typeName"));
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
