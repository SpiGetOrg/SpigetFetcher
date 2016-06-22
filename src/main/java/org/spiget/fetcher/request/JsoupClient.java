package org.spiget.fetcher.request;

import lombok.extern.log4j.Log4j2;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;

@Log4j2
public class JsoupClient extends SpigetClient {

	public static SpigetResponse get(String url) throws IOException, InterruptedException {
		Connection connection = Jsoup.connect(url).method(Connection.Method.GET).userAgent(userAgent);
		connection.cookies(cookies);
		connection.followRedirects(true);
		connection.ignoreHttpErrors(true);
		connection.ignoreContentType(true);

		Connection.Response response = connection.execute();
		Document document = response.parse();

		if (document.toString().contains("CloudFlare")) {
			// We've hit cloudflare -> enable bypass and try again
			bypassCloudflare = true;
			return SpigetClient.get(url);
		}
		if (response.statusCode() > 500) {
			log.warn("Got status code " + response.statusCode());
			// We've hit cloudflare but also got a bad status code -> enable bypass, wait a few seconds and try again
			Thread.sleep(1000);
			bypassCloudflare = true;
			return SpigetClient.get(url);
		}

		// Request was successful
		cookies.putAll(response.cookies());
		return new SpigetResponse(cookies, document);
	}

	public static SpigetDownload download(String url) throws IOException, InterruptedException {
		throw new UnsupportedOperationException();
	}

}
