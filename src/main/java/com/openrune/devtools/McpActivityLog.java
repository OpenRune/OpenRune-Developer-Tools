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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Ring buffer of recent MCP tool calls, feeding the dashboard at GET /.
 */
class McpActivityLog
{
	private static final int MAX_ENTRIES = 100;

	private final Deque<JsonObject> entries = new ArrayDeque<>();
	private long nextId = 1;

	synchronized void record(String tool, JsonObject args, JsonObject result, long durationMs)
	{
		JsonObject entry = new JsonObject();
		entry.addProperty("id", nextId++);
		entry.addProperty("time", Instant.now().toString());
		entry.addProperty("tool", tool);
		entry.add("args", args);
		entry.addProperty("durationMs", durationMs);
		entry.addProperty("isError", result.has("isError") && result.get("isError").getAsBoolean());

		JsonArray texts = new JsonArray();
		JsonArray images = new JsonArray();
		if (result.has("content") && result.get("content").isJsonArray())
		{
			for (JsonElement item : result.getAsJsonArray("content"))
			{
				if (!item.isJsonObject())
				{
					continue;
				}
				JsonObject content = item.getAsJsonObject();
				String type = content.has("type") ? content.get("type").getAsString() : "";
				if ("text".equals(type) && content.has("text"))
				{
					texts.add(content.get("text").getAsString());
				}
				else if ("image".equals(type) && content.has("data"))
				{
					images.add(content.get("data").getAsString());
				}
			}
		}
		entry.add("texts", texts);
		entry.add("images", images);

		entries.addLast(entry);
		while (entries.size() > MAX_ENTRIES)
		{
			entries.removeFirst();
		}
	}

	synchronized JsonObject since(long afterId)
	{
		JsonArray out = new JsonArray();
		for (JsonObject entry : entries)
		{
			if (entry.get("id").getAsLong() > afterId)
			{
				out.add(entry);
			}
		}
		JsonObject result = new JsonObject();
		result.addProperty("last", nextId - 1);
		result.add("entries", out);
		return result;
	}
}
