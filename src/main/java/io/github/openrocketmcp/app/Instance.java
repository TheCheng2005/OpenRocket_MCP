package io.github.openrocketmcp.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One RocketMaxxing per designs folder. The first launch holds a lock and leaves its address (port and token, readable
 * only by the user) next to it; a later launch (the app clicked again, a design double-clicked) hands its files to the
 * running one, which opens a window, and exits. The lock goes with the process, so a crash leaves nothing stuck.
 */
final class Instance {
	private final Path lockFile;
	private final Path addressFile;
	private FileChannel channel;
	private FileLock lock;

	Instance(Path workspace) {
		Path dir = dir();
		String key = key(workspace);
		this.lockFile = dir.resolve("instance-" + key + ".lock");
		this.addressFile = dir.resolve("instance-" + key + ".json");
	}

	static Path dir() {
		return Path.of(System.getProperty("user.home"), ".rocketmaxxing");
	}

	static String key(Path workspace) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(workspace.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(h, 0, 6);
		} catch (Exception e) {
			return Integer.toHexString(workspace.toString().hashCode());
		}
	}

	/** True when this process is the one for the folder (it then keeps the lock until it exits). */
	boolean claim() {
		try {
			Files.createDirectories(lockFile.getParent());
			channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			lock = channel.tryLock();
			if (lock == null) {
				channel.close();
				channel = null;
			}
			return lock != null;
		} catch (IOException | RuntimeException e) {
			// A folder or file system without locks: run unguarded rather than not at all.
			return true;
		}
	}

	/** Leaves the running app's address for later launches. */
	void publish(int port, String token) throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("port", port);
		o.addProperty("token", token);
		o.addProperty("pid", ProcessHandle.current().pid());
		Files.writeString(addressFile, o.toString());
		try {
			Files.setPosixFilePermissions(addressFile, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException notPosix) {
			// Windows: the file sits in the user's own profile
		}
	}

	void release() {
		try {
			Files.deleteIfExists(addressFile);
			if (lock != null) {
				lock.release();
			}
			if (channel != null) {
				channel.close();
			}
		} catch (IOException ignored) {
			// exiting anyway
		}
	}

	/**
	 * Hands the files to the running app and asks it for a window (or, with {@code window} false, only its address).
	 * Waits up to {@code waitMillis} for an app that is still starting. Returns the running app's address, or null.
	 */
	String handOver(List<String> files, boolean window, long waitMillis) {
		long deadline = System.currentTimeMillis() + waitMillis;
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
		while (true) {
			try {
				JsonObject address = JsonParser.parseString(Files.readString(addressFile)).getAsJsonObject();
				String base = "http://127.0.0.1:" + address.get("port").getAsInt();
				JsonObject args = new JsonObject();
				JsonArray f = new JsonArray();
				files.forEach(f::add);
				args.add("files", f);
				args.addProperty("window", window);
				JsonObject params = new JsonObject();
				params.addProperty("name", "app_window");
				params.add("arguments", args);
				JsonObject call = new JsonObject();
				call.addProperty("jsonrpc", "2.0");
				call.addProperty("id", 1);
				call.addProperty("method", "tools/call");
				call.add("params", params);
				HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/rpc")).timeout(Duration.ofSeconds(20))
						.header("Authorization", "Bearer " + address.get("token").getAsString())
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(call.toString())).build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() == 200) {
					JsonObject result = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("result");
					if (result != null && !result.get("isError").getAsBoolean()) {
						String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
						return JsonParser.parseString(text).getAsJsonObject().get("url").getAsString();
					}
				}
			} catch (IOException | RuntimeException notYet) {
				// still starting (no address yet), or it just went away
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
			if (System.currentTimeMillis() > deadline) {
				return null;
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
	}
}
