package org.spiget.fetcher.request;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.log4j.Log4j2;
import org.spiget.fetcher.SpigetFetcher;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

@Log4j2
public abstract class SpigetClient {

	public static final String BASE_URL  = "https://spigotmc.org/";
	public static final String userAgent = SpigetFetcher.config.get("request.userAgent").getAsString();

	public static boolean             bypassCloudflare = true;
	public static Map<String, String> cookies          = new HashMap<>();

	public static SpigetResponse get(String url) throws IOException, InterruptedException {
		if (SpigetFetcher.config.get("debug.connections").getAsBoolean()) {
			log.debug("GET " + url);
		}
		if (bypassCloudflare) {
			return HtmlUnitClient.get(url);
		} else {
			return JsoupClient.get(url);
		}
	}

	public static SpigetDownload download(String url) throws IOException, InterruptedException {
		if (SpigetFetcher.config.get("debug.connections").getAsBoolean()) {
			log.debug("DOWNLOAD " + url);
		}
		return HtmlUnitClient.download(url);
	}

	public static void loadCookiesFromFile() throws IOException {
		JsonObject cookieJson = new JsonParser().parse(new FileReader(SpigetFetcher.config.get("request.cookieFile").getAsString())).getAsJsonObject();
		for (Map.Entry<String, JsonElement> entry : cookieJson.entrySet()) {
			cookies.put(entry.getKey(), entry.getValue().getAsString());
		}
	}

	public static void saveCookiesToFile() throws IOException {
		JsonObject cookieJson = new JsonObject();
		for (Map.Entry<String, String> entry : cookies.entrySet()) {
			cookieJson.addProperty(entry.getKey(), entry.getValue());
		}
		try (Writer writer = new FileWriter(SpigetFetcher.config.get("request.cookieFile").getAsString())){
			new Gson().toJson(cookieJson, writer);
		}
	}

}
