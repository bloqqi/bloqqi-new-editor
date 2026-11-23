package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** End-to-end over the WebSocket, without a browser. */
public class WebServerTest {
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private WebServer server;
	private final List<Client> clients = new ArrayList<>();

	/** A WebSocket client that queues incoming text messages. */
	private final class Client {
		final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
		final WebSocket socket;

		Client() throws Exception {
			socket = HttpClient.newHttpClient().newWebSocketBuilder()
					.buildAsync(URI.create("ws://localhost:" + server.port() + WebServer.WEBSOCKET_PATH),
							new WebSocket.Listener() {
								private final StringBuilder buffer = new StringBuilder();

								@Override
								public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
									buffer.append(data);
									if (last) {
										messages.add(buffer.toString());
										buffer.setLength(0);
									}
									ws.request(1);
									return null;
								}
							})
					.get(10, TimeUnit.SECONDS);
			clients.add(this);
		}

		/** The next message within {@code millis}, or null. */
		JsonObject poll(long millis) throws InterruptedException {
			String text = messages.poll(millis, TimeUnit.MILLISECONDS);
			return text == null ? null : new JsonParser().parse(text).getAsJsonObject();
		}

		/** The next answer ("diagram" or "error"), skipping pushed messages. */
		JsonObject answer() throws InterruptedException {
			while (true) {
				JsonObject m = next();
				String type = m.get("type").getAsString();
				if (type.equals("diagram") || type.equals("error")) {
					return m;
				}
			}
		}

		JsonObject next() throws InterruptedException {
			String text = messages.poll(20, TimeUnit.SECONDS);
			assertNotNull("message received", text);
			return new JsonParser().parse(text).getAsJsonObject();
		}

		void open(String file, String diagramType) {
			String type = diagramType == null ? "" : ",\"diagramType\":\"" + diagramType + "\"";
			socket.sendText("{\"type\":\"open\",\"file\":\"" + file + "\"" + type + "}", true);
		}
	}

	@Before
	public void start() throws Exception {
		server = new WebServer(0, Examples.workspace(), null);
		server.start();
	}

	@After
	public void stop() throws Exception {
		for (Client c : clients) {
			c.socket.abort();
		}
		server.stop();
	}

	@Test
	public void sendsWorkspaceThenDiagram() throws Exception {
		Client client = new Client();
		JsonObject workspace = client.next();
		assertEquals("workspace", workspace.get("type").getAsString());
		assertTrue(workspace.get("files").toString().contains("\"PLib.dia\""));

		client.open("PLib.dia", "PTrack");
		JsonObject diagram = client.next();
		assertEquals("diagram", diagram.get("type").getAsString());
		assertEquals("PTrack", diagram.get("diagramType").getAsString());
		// 5 in-parameters, 1 out-parameter, 3 blocks; 8 connections.
		assertEquals(9, diagram.getAsJsonArray("nodes").size());
		JsonObject sp = diagram.getAsJsonArray("nodes").get(0).getAsJsonObject();
		assertEquals("P::SP", sp.get("id").getAsString());
		assertEquals("inParameter", sp.get("kind").getAsString());
		assertEquals(8, diagram.getAsJsonArray("edges").size());
		JsonObject edge = diagram.getAsJsonArray("edges").get(0).getAsJsonObject();
		assertEquals("P::e.out:out->P::PPart.in:in1", edge.get("id").getAsString());
		assertEquals("out:out", edge.get("sourceHandle").getAsString());
		assertTrue("explicit null", edge.has("intercept") && edge.get("intercept").isJsonNull());
	}

	@Test
	public void idleConnectionIsKeptOpenByPings() throws Exception {
		stop();
		clients.clear();
		server = new WebServer(0, Examples.workspace(), null, Duration.ofMillis(500), Duration.ofMillis(100));
		server.start();

		Client client = new Client();
		client.next(); // workspace
		Thread.sleep(1500); // three idle timeouts without any messages
		client.open("PLib.dia", "P");
		assertEquals("diagram", client.next().get("type").getAsString());
	}

	@Test
	public void moveWritesTheFileAndAnswersWithTheNewDiagram() throws Exception {
		Path dir = temp.newFolder("workspace").toPath();
		Path file = dir.resolve("TwoCounters.dia");
		Files.copy(Examples.dir().resolve("TwoCounters.dia"), file);
		stop();
		clients.clear();
		server = new WebServer(0, new Workspace(dir), null);
		server.start();

		Client client = new Client();
		client.next(); // workspace
		client.socket.sendText("{\"type\":\"move\",\"file\":\"TwoCounters.dia\",\"diagramType\":\"Acc\","
				+ "\"nodes\":[{\"id\":\"add\",\"x\":123.4,\"y\":45}]}", true);
		JsonObject diagram = client.next();
		assertEquals(diagram.toString(), "diagram", diagram.get("type").getAsString());
		JsonObject add = null;
		for (var n : diagram.getAsJsonArray("nodes")) {
			if (n.getAsJsonObject().get("id").getAsString().equals("add")) {
				add = n.getAsJsonObject();
			}
		}
		assertEquals(123, add.get("x").getAsInt());
		assertEquals(45, add.get("y").getAsInt());
		assertTrue(Files.readString(file).contains("  add = (123, 45, "));
		try (var files = Files.list(dir)) {
			assertEquals("no temporary files left", 1, files.count());
		}

		// A bad move is refused and leaves the file alone.
		String text = Files.readString(file);
		client.socket.sendText("{\"type\":\"move\",\"file\":\"TwoCounters.dia\",\"diagramType\":\"Acc\","
				+ "\"nodes\":[{\"id\":\"nosuch\",\"x\":1,\"y\":1}]}", true);
		assertEquals("error", client.next().get("type").getAsString());
		assertEquals(text, Files.readString(file));
	}

	@Test
	public void editsReportTheirChangeAndCanBeUndoneAndRedone() throws Exception {
		Path dir = temp.newFolder("undo").toPath();
		Path file = dir.resolve("TwoCounters.dia");
		Files.copy(Examples.dir().resolve("TwoCounters.dia"), file);
		String original = Files.readString(file);
		stop();
		clients.clear();
		server = new WebServer(0, new Workspace(dir), null);
		server.start();

		Client client = new Client();
		client.next(); // workspace
		client.open("TwoCounters.dia", "Acc");
		assertTrue("opening changes nothing", client.next().get("change").isJsonNull());

		client.socket.sendText("{\"type\":\"move\",\"file\":\"TwoCounters.dia\",\"diagramType\":\"Acc\","
				+ "\"nodes\":[{\"id\":\"add\",\"x\":200,\"y\":100}]}", true);
		JsonObject change = client.next().getAsJsonObject("change");
		assertEquals(original, change.get("before").getAsString());
		String moved = Files.readString(file);
		assertEquals(moved, change.get("after").getAsString());

		// Undo: back to the text before the move, if the file still has the text after it.
		client.socket.sendText(restore("Acc", original, moved), true);
		JsonObject undone = client.next();
		assertEquals(undone.toString(), "diagram", undone.get("type").getAsString());
		assertEquals(original, Files.readString(file));
		assertEquals(moved, undone.getAsJsonObject("change").get("before").getAsString());

		// Redo.
		client.socket.sendText(restore("Acc", moved, original), true);
		assertEquals("diagram", client.next().get("type").getAsString());
		assertEquals(moved, Files.readString(file));

		// Changed elsewhere in the meantime: undo is refused and the file is left alone.
		String external = moved + "\n// edited elsewhere\n";
		Files.writeString(file, external);
		client.socket.sendText(restore("Acc", original, moved), true);
		JsonObject refused = client.next();
		assertEquals("error", refused.get("type").getAsString());
		assertTrue(refused.get("message").getAsString().contains("has been changed since"));
		assertEquals(external, Files.readString(file));
	}

	@Test
	public void newDiagramTypesAreShownExternalFunctionsAreNot() throws Exception {
		Path dir = temp.newFolder("types").toPath();
		Files.copy(Examples.dir().resolve("TwoCounters.dia"), dir.resolve("TwoCounters.dia"));
		stop();
		clients.clear();
		server = new WebServer(0, new Workspace(dir), null);
		server.start();

		Client client = new Client();
		client.next(); // workspace
		client.socket.sendText("{\"type\":\"addDiagramType\",\"file\":\"TwoCounters.dia\",\"name\":\"Counter\"}", true);
		JsonObject added = client.next();
		assertEquals(added.toString(), "Counter", added.get("diagramType").getAsString());

		client.socket.sendText("{\"type\":\"addExternalFunction\",\"file\":\"TwoCounters.dia\","
				+ "\"diagramType\":\"Counter\",\"name\":\"Log\",\"inputs\":[{\"name\":\"x\",\"typeName\":\"Int\"}],"
				+ "\"output\":null}", true);
		JsonObject external = client.next();
		assertEquals(external.toString(), "Counter", external.get("diagramType").getAsString());
		assertTrue(external.get("palette").toString().contains("\"name\":\"Log\""));
		assertTrue(Files.readString(dir.resolve("TwoCounters.dia")).contains("external Log(x: Int);"));
	}

	/** A server on a temporary copy of TwoCounters.dia. */
	private Path watchedWorkspace() throws Exception {
		Path dir = temp.newFolder("watched").toPath();
		Files.copy(Examples.dir().resolve("TwoCounters.dia"), dir.resolve("TwoCounters.dia"));
		stop();
		clients.clear();
		server = new WebServer(0, new Workspace(dir), null);
		server.start();
		return dir;
	}

	private Client openAcc() throws Exception {
		Client c = new Client();
		c.next(); // workspace
		c.open("TwoCounters.dia", "Acc");
		assertEquals("diagram", c.next().get("type").getAsString());
		return c;
	}

	private static int x(JsonObject diagram, String id) {
		for (var n : diagram.getAsJsonArray("nodes")) {
			if (n.getAsJsonObject().get("id").getAsString().equals(id)) {
				return n.getAsJsonObject().get("x").getAsInt();
			}
		}
		throw new AssertionError("no node " + id);
	}

	@Test
	public void anEditInOneTabUpdatesTheOthersButIsNotEchoed() throws Exception {
		watchedWorkspace();
		Client a = openAcc();
		Client b = openAcc();

		a.socket.sendText("{\"type\":\"move\",\"file\":\"TwoCounters.dia\",\"diagramType\":\"Acc\","
				+ "\"nodes\":[{\"id\":\"add\",\"x\":321,\"y\":45}]}", true);
		assertEquals("diagram", a.next().get("type").getAsString());

		JsonObject update = b.next();
		assertEquals(update.toString(), "update", update.get("type").getAsString());
		assertEquals(321, x(update, "add"));
		assertEquals("Acc", update.get("diagramType").getAsString());

		assertEquals("no echo to the tab that made the edit", null, a.poll(500));
	}

	@Test
	public void changesMadeOutsideTheEditorArePushed() throws Exception {
		Path dir = watchedWorkspace();
		Client c = openAcc();
		Path file = dir.resolve("TwoCounters.dia");

		// A text editor renames the variable.
		Files.writeString(file, Files.readString(file).replace("state", "total"));
		JsonObject update = c.next();
		assertEquals("update", update.get("type").getAsString());
		assertTrue(update.get("nodes").toString().contains("\"var total\""));

		// ... and then breaks the file: the update says it is read-only now.
		Files.writeString(file, Files.readString(file).replace("add: Add;", "add: Add"));
		update = c.next();
		assertEquals("update", update.get("type").getAsString());
		assertTrue(update.get("readOnly").getAsString().contains("syntax errors"));
	}

	@Test
	public void newAndDeletedFilesArePushed() throws Exception {
		Path dir = watchedWorkspace();
		Client c = openAcc();

		Files.writeString(dir.resolve("New.dia"), "diagramtype N {\n}\n");
		JsonObject files = c.next();
		assertEquals("files", files.get("type").getAsString());
		assertTrue(files.get("files").toString().contains("New.dia"));

		Files.delete(dir.resolve("TwoCounters.dia"));
		boolean notice = false;
		boolean listed = false;
		for (JsonObject m = c.next(); m != null; m = c.poll(500)) {
			String type = m.get("type").getAsString();
			if (type.equals("notice")) {
				notice = m.get("message").getAsString().contains("TwoCounters.dia has been deleted");
			} else if (type.equals("files")) {
				listed = !m.get("files").toString().contains("TwoCounters.dia");
			}
		}
		assertTrue("notice", notice);
		assertTrue("new file list", listed);
	}

	@Test
	public void filesCanBeCreatedRenamedAndDeleted() throws Exception {
		Path dir = watchedWorkspace();
		Client a = openAcc();
		Client b = openAcc();

		a.socket.sendText("{\"type\":\"createFile\",\"file\":\"New.dia\",\"diagramType\":\"Main\"}", true);
		JsonObject created = a.answer();
		assertEquals(created.toString(), "diagram", created.get("type").getAsString());
		assertEquals("New.dia", created.get("file").getAsString());
		assertEquals("Main", created.get("diagramType").getAsString());
		assertEquals("diagramtype Main {\n}\n", Files.readString(dir.resolve("New.dia")));
		JsonObject listed = b.next(); // the other tab learns about the file
		assertEquals("files", listed.get("type").getAsString());
		assertTrue(listed.get("files").toString().contains("New.dia"));

		// A type name the standard library has is refused, and nothing is created.
		a.socket.sendText("{\"type\":\"createFile\",\"file\":\"Other.dia\",\"diagramType\":\"Add\"}", true);
		assertEquals("error", a.answer().get("type").getAsString());
		assertFalse(Files.exists(dir.resolve("Other.dia")));

		a.socket.sendText("{\"type\":\"renameFile\",\"file\":\"New.dia\",\"newName\":\"Renamed.dia\","
				+ "\"diagramType\":\"Main\"}", true);
		JsonObject renamed = a.answer();
		assertEquals("Renamed.dia", renamed.get("file").getAsString());
		assertEquals("Main", renamed.get("diagramType").getAsString());

		a.socket.sendText("{\"type\":\"deleteFile\",\"file\":\"Renamed.dia\"}", true);
		JsonObject after = a.answer();
		assertEquals(after.toString(), "TwoCounters.dia", after.get("file").getAsString());
		assertFalse(Files.exists(dir.resolve("Renamed.dia")));

		a.socket.sendText("{\"type\":\"deleteFile\",\"file\":\"TwoCounters.dia\"}", true);
		JsonObject refused = a.answer();
		assertEquals("error", refused.get("type").getAsString());
		assertTrue(refused.get("message").getAsString().contains("it is the only file"));
	}

	private static String restore(String diagramType, String text, String expected) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "restore");
		o.addProperty("file", "TwoCounters.dia");
		o.addProperty("diagramType", diagramType);
		o.addProperty("text", text);
		o.addProperty("expected", expected);
		return o.toString();
	}

	@Test
	public void badRequestGivesError() throws Exception {
		Client client = new Client();
		client.next(); // workspace
		client.open("../secret.dia", null);
		assertEquals("error", client.next().get("type").getAsString());
	}

	/**
	 * Regression: JastAdd keeps attribute evaluation state in a static field, so
	 * concurrent requests used to fail with a NullPointerException in ASTState.
	 */
	@Test
	public void concurrentClientsAreServed() throws Exception {
		List<Client> many = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Client c = new Client();
			c.next(); // workspace
			many.add(c);
		}
		int requestsPerClient = 5;
		for (int r = 0; r < requestsPerClient; r++) {
			for (Client c : many) {
				c.open("vlhcc-large.dia", null);
				c.open("InlineExample.dia", "Tank");
			}
		}
		for (Client c : many) {
			for (int r = 0; r < 2 * requestsPerClient; r++) {
				JsonObject message = c.next();
				assertEquals(message.toString(), "diagram", message.get("type").getAsString());
			}
		}
	}
}
