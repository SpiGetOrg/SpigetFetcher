package org.spiget.fetcher;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.IOException;

public class Discord {

	public static void postMessage(String content, @NotNull JsonObject config) throws IOException {
		if(!config.has("discord.channel"))return;
		String channel = config.get("discord.channel").getAsString();

		JsonObject body = new JsonObject();
		body.addProperty("content", content);

		Jsoup.connect("https://discordapp.com/api/channels/"+channel+"/messages")
				.method(Connection.Method.POST)
				.userAgent("Spiget")
				.header("Authorization","Bot "+config.get("discord.token").getAsString())
				.requestBody(body.toString())
				.execute();
	}

}