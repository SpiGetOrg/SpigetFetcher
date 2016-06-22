package org.spiget.fetcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.log4j.Log4j2;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.spiget.DatabaseClient;
import org.spiget.data.resource.ListedResource;
import org.spiget.fetcher.parser.Paginator;
import org.spiget.fetcher.parser.ResourceListItemParser;
import org.spiget.fetcher.request.SpigetClient;
import org.spiget.fetcher.request.SpigetResponse;

import java.io.FileReader;
import java.io.IOException;

@Log4j2
public class SpigetFetcher {

	public static JsonObject config;

	public static DatabaseClient databaseClient;
	public static FetchMode mode = FetchMode.LIST;

	public SpigetFetcher() {
	}

	public SpigetFetcher init() throws IOException {
		config = new JsonParser().parse(new FileReader("config.json")).getAsJsonObject();
		SpigetClient.loadCookiesFromFile();
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					log.info("Saving cookies...");
					SpigetClient.saveCookiesToFile();
				} catch (IOException e) {
					log.warn("Failed to save cookies", e);
				}
				try {
					log.info("Disconnecting database...");
					databaseClient.disconnect();
				} catch (IOException e) {
					log.warn("Failed to disconnect from database", e);
				}
			}
		});

		{
			log.info("Initializing & testing database connection...");
			long testStart = System.currentTimeMillis();
			try {
				databaseClient = new DatabaseClient(
						config.get("database.name").getAsString(),
						config.get("database.host").getAsString(),
						config.get("database.port").getAsInt(),
						config.get("database.user").getAsString(),
						config.get("database.pass").getAsString().toCharArray(),
						config.get("database.db").getAsString());
				databaseClient.connect(config.get("database.timeout").getAsInt());
				databaseClient.databaseCount();
				log.info("Connection successful (" + (System.currentTimeMillis() - testStart) + "ms)");
			} catch (Exception e) {
				log.fatal("Connection failed after " + (System.currentTimeMillis() - testStart) + "ms", e);
				log.fatal("Aborting.");
				System.exit(-1);
				return null;
			}
		}

		{
			log.info("Testing SpigotMC connection...");
			long testStart = System.currentTimeMillis();
			try {
				SpigetResponse response = SpigetClient.get(SpigetClient.BASE_URL);
				log.info("Connection successful (" + (System.currentTimeMillis() - testStart) + "ms)");
			} catch (Exception e) {
				log.fatal("Connection failed after " + (System.currentTimeMillis() - testStart) + "ms", e);
				log.fatal("Aborting.");
				System.exit(-1);
				return null;
			}
		}

		mode = FetchMode.valueOf(config.get("fetch.mode").getAsString());
		log.info("Fetch mode is " + mode.name());

		return this;
	}

	public void fetch() {
		log.info("----- Fetcher started -----");

		int pageAmount = config.get("fetch.resources.pages").getAsInt();
		int pageCounter = 0;
		Paginator resourceListPaginator = new Paginator(SpigetClient.BASE_URL + "/resources/?page=%s", pageAmount, config.get("fetch.resouces.inverted").getAsBoolean());
		for (org.jsoup.nodes.Document document : resourceListPaginator) {
			pageCounter++;
			log.info("Fetching page " + pageCounter + "/" + pageAmount);

			ResourceListItemParser resourceItemParser = new ResourceListItemParser();
			Elements resourceListItems = document.select("li.resourceListItem");
			int itemCounter = 0;
			for (Element resourceListItem : resourceListItems) {
				itemCounter++;
				try {
					ListedResource listedResource = resourceItemParser.parse(resourceListItem);
					if (mode.isFullResource()) {
						try {
							Document resourceDocument = SpigetClient.get(SpigetClient.BASE_URL + "/resources/" + listedResource.getId()).getDocument();

						} catch (Throwable throwable) {
							log.error("Unexpected exception while parsing full resource #" + listedResource.getId(), throwable);
						}
					}
				} catch (Throwable throwable) {
					log.error("Unexpected exception while parsing item #" + itemCounter + " on page " + pageCounter, throwable);
				}
			}
		}
	}

}
