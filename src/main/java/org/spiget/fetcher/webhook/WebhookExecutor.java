package org.spiget.fetcher.webhook;

import com.google.gson.JsonObject;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.spiget.data.webhook.Webhook;
import org.spiget.data.webhook.event.WebhookEvent;
import org.spiget.fetcher.SpigetFetcher;

import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Log4j2
public class WebhookExecutor {

	final String startTimestamp = String.valueOf(System.currentTimeMillis());

	final int      failThreshold = SpigetFetcher.config.get("webhook.failThreshold").getAsInt();
	final Executor postExecutor  = Executors.newFixedThreadPool(SpigetFetcher.config.get("webhook.postThreads").getAsInt());

	// Keeps track of the amount of webhooks to call
	public int pendingCalls = 0;

	public WebhookExecutor() {
	}

	public void callEvent(WebhookEvent event) {
		Set<Webhook> webhooks = SpigetFetcher.databaseClient.getWebhooks(event.name);
		if (webhooks.isEmpty()) {
			log.info("No webhooks for '" + event.name + "'");
		} else {
			log.info("Calling " + webhooks.size() + " webhooks for '" + event.name + "'");
		}
		final JsonObject eventJson = event.toJson();
		for (Webhook webhook : webhooks) {
			postExecutor.execute(() -> {
				pendingCalls++;

				log.debug("Calling '" + webhook.url + "'");
				int result = postData(webhook.id, webhook.url, event.name, eventJson);
				webhook.failStatus = result;

				if (result != 0) {
					log.warn("Connection failed: " + result);
					webhook.failedConnections++;
				} else {
					//Reset fails
					webhook.failedConnections = 0;
				}

				if (result == -2) {// No reason to keep trying -> remove
					SpigetFetcher.databaseClient.deleteWebhook(webhook);
				} else if (webhook.failedConnections > failThreshold) {// Threshold exceeded -> remove
					SpigetFetcher.databaseClient.deleteWebhook(webhook);
				} else {
					SpigetFetcher.databaseClient.updateWebhookStatus(webhook);
				}

				pendingCalls--;
			});
		}
	}

	public boolean isFinished() {
		return this.pendingCalls <= 0;
	}

	/*
	 *  0 = OK
	 * >0 = connection failed (Response code)
	 * -1 = unknown error
	 * -2 = connection impossible (e.g. invalid URL)
	 */
	int postData(String webhookId, String url, String eventType, JsonObject data) {
		try {
			String dataString = data.toString();
			dataString = dataString.replace("\"_id\":", "\"id\":");
			int dataLength = dataString.getBytes("UTF-8").length;

			HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setDoOutput(true);
			connection.setRequestMethod("POST");
			connection.setRequestProperty("X-Spiget-Event", eventType);
			connection.setRequestProperty("X-Spiget-Time", startTimestamp);
			connection.setRequestProperty("X-Spiget-HookId", webhookId);
			connection.setRequestProperty("User-Agent", "Spiget-Webhook/2.0");
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("Content-Length", String.valueOf(dataLength));
			connection.setFixedLengthStreamingMode(dataLength);

			PrintWriter out = new PrintWriter(connection.getOutputStream());
			out.write(dataString);
			out.flush();
			out.close();

			if (connection.getResponseCode() != 200) {
				log.warn("Connection to " + url + " failed (Code: " + connection.getResponseCode() + ", '" + connection.getResponseMessage() + "')");
				return connection.getResponseCode();
			}

			return 0;
		} catch (MalformedURLException e) {
			log.warn("Malformed URL: " + url);
			return -2;
		} catch (Throwable e) {
			log.log(Level.ERROR, "Unknown exception", e);
			return -1;
		}
	}

}
