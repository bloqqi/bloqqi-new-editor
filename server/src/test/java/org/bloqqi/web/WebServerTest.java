package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
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
