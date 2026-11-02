/*
 * Copyright (c) 2026, Mark7625
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.openrune.devtools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;

/**
 * Checks the GitHub releases of this plugin for a newer build, downloads the
 * jar into every client's sideloaded-plugins folder, and schedules a swap for
 * any copy that is locked by the running client.
 */
@Slf4j
class UpdateChecker
{
	private static final String RELEASES_API =
		"https://api.github.com/repos/OpenRune/OpenRune-Developer-Tools/releases/latest";
	private static final String JAR_NAME = "OpenRune-Developer-Tools.jar";
	private static final String[] CLIENT_DIRS = {".runelite", ".rsprox", ".fluxious"};

	private final Client client;
	private final ClientThread clientThread;

	@Getter
	private volatile String latestTag;
	@Getter
	private volatile boolean updateApplied;

	UpdateChecker(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	/** Fire-and-forget: check for a newer release and grab it if found. */
	void checkAsync()
	{
		String current = currentVersion();
		if (current == null || current.endsWith("-local"))
		{
			log.info("Dev build ({}); skipping update check", current);
			return;
		}
		Thread t = new Thread(() -> check(current), "openrune-update-check");
		t.setDaemon(true);
		t.start();
	}

	private void check(String current)
	{
		try
		{
			JsonObject release = fetchLatestRelease();
			String tag = release.get("tag_name").getAsString();
			latestTag = tag;
			String latest = tag.startsWith("v") ? tag.substring(1) : tag;
			if (latest.equals(current))
			{
				log.info("OpenRune-DeveloperTools {} is up to date", current);
				return;
			}

			String assetUrl = findJarAsset(release);
			if (assetUrl == null)
			{
				log.warn("Update {} found but release has no {} asset", tag, JAR_NAME);
				return;
			}

			log.info("Updating OpenRune-DeveloperTools {} -> {}", current, latest);
			Path staged = Files.createTempFile("openrune-devtools", ".jar");
			download(assetUrl, staged);

			List<Path> lockedTargets = new ArrayList<>();
			for (String dir : CLIENT_DIRS)
			{
				File pluginDir = new File(System.getProperty("user.home"), dir + "/sideloaded-plugins");
				if (!pluginDir.isDirectory())
				{
					continue;
				}
				Path target = pluginDir.toPath().resolve(JAR_NAME);
				try
				{
					Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
					log.info("Updated {}", target);
				}
				catch (IOException locked)
				{
					// Jar is loaded by this client; stage next to it and swap after exit
					Path pending = pluginDir.toPath().resolve(JAR_NAME + ".new");
					Files.copy(staged, pending, StandardCopyOption.REPLACE_EXISTING);
					lockedTargets.add(target);
					log.info("Staged update for locked jar {}", target);
				}
			}
			Files.deleteIfExists(staged);

			if (!lockedTargets.isEmpty())
			{
				scheduleSwapOnExit(lockedTargets);
			}
			updateApplied = true;
			announce("OpenRune-DeveloperTools updated to " + latest
				+ (lockedTargets.isEmpty() ? "." : ". Restart the client to load it."));
		}
		catch (Exception e)
		{
			log.warn("Update check failed", e);
		}
	}

	private String currentVersion()
	{
		String version = UpdateChecker.class.getPackage().getImplementationVersion();
		if (version != null)
		{
			return version;
		}
		// Fallback: read our own manifest (sideloaded jars may not expose the package version)
		try (InputStream in = UpdateChecker.class.getResourceAsStream("/META-INF/MANIFEST.MF"))
		{
			if (in != null)
			{
				java.util.jar.Manifest manifest = new java.util.jar.Manifest(in);
				return manifest.getMainAttributes().getValue("Implementation-Version");
			}
		}
		catch (IOException ignored)
		{
			// treated as dev build
		}
		return null;
	}

	private JsonObject fetchLatestRelease() throws IOException
	{
		HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
		conn.setRequestProperty("Accept", "application/vnd.github+json");
		conn.setConnectTimeout(10_000);
		conn.setReadTimeout(10_000);
		try (InputStream in = conn.getInputStream())
		{
			String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			return new JsonParser().parse(body).getAsJsonObject();
		}
		finally
		{
			conn.disconnect();
		}
	}

	private static String findJarAsset(JsonObject release)
	{
		JsonArray assets = release.getAsJsonArray("assets");
		if (assets == null)
		{
			return null;
		}
		for (int i = 0; i < assets.size(); i++)
		{
			JsonObject asset = assets.get(i).getAsJsonObject();
			if (JAR_NAME.equals(asset.get("name").getAsString()))
			{
				return asset.get("browser_download_url").getAsString();
			}
		}
		return null;
	}

	private static void download(String url, Path target) throws IOException
	{
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setInstanceFollowRedirects(true);
		conn.setConnectTimeout(10_000);
		conn.setReadTimeout(60_000);
		try (InputStream in = conn.getInputStream())
		{
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
		finally
		{
			conn.disconnect();
		}
	}

	/**
	 * The running client holds a file lock on its own jar until the JVM dies, so
	 * spawn a detached helper on shutdown that swaps in the staged copy afterwards.
	 */
	private static void scheduleSwapOnExit(List<Path> targets)
	{
		Runtime.getRuntime().addShutdownHook(new Thread(() ->
		{
			for (Path target : targets)
			{
				String jar = target.toString();
				try
				{
					if (System.getProperty("os.name", "").toLowerCase().contains("win"))
					{
						new ProcessBuilder("cmd", "/c",
							"ping -n 4 127.0.0.1 >nul & move /y \"" + jar + ".new\" \"" + jar + "\"")
							.start();
					}
					else
					{
						new ProcessBuilder("sh", "-c",
							"sleep 3; mv -f '" + jar + ".new' '" + jar + "'")
							.start();
					}
				}
				catch (IOException e)
				{
					// nothing left to do this late in shutdown
				}
			}
		}, "openrune-update-swap"));
	}

	private void announce(String message)
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return false; // retry each frame until the player is logged in
			}
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
			return true;
		});
		log.info(message);
	}
}
