package org.spiget.fetcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.log4j.Log4j2;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.spiget.data.author.ListedAuthor;
import org.spiget.data.category.ListedCategory;
import org.spiget.data.resource.ListedResource;
import org.spiget.data.resource.Resource;
import org.spiget.data.resource.version.ResourceVersion;
import org.spiget.database.DatabaseClient;
import org.spiget.fetcher.parser.Paginator;
import org.spiget.fetcher.parser.ResourceListItemParser;
import org.spiget.fetcher.parser.ResourcePageParser;
import org.spiget.fetcher.parser.ResourceVersionItemParser;
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
		long start = System.currentTimeMillis();
		databaseClient.updateStatus("fetch.start", start);
		databaseClient.updateStatus("fetch.end", 0);

		int pageAmount = config.get("fetch.resources.pages").getAsInt();
		databaseClient.updateStatus("fetch.page.amount", pageAmount);
		int pageCounter = 0;
		Paginator resourceListPaginator = new Paginator(SpigetClient.BASE_URL + "resources/?page=%s", pageAmount, config.get("fetch.resources.inverted").getAsBoolean());
		for (org.jsoup.nodes.Document document : resourceListPaginator) {
			pageCounter++;
			log.info("Fetching page " + pageCounter + "/" + pageAmount);
			databaseClient.updateStatus("fetch.page.index", pageCounter);

			ResourceListItemParser resourceItemParser = new ResourceListItemParser();
			ResourcePageParser resourcePageParser = new ResourcePageParser();
			Elements resourceListItems = document.select("li.resourceListItem");
			int itemCounter = 0;
			for (Element resourceListItem : resourceListItems) {
				itemCounter++;
				databaseClient.updateStatus("fetch.page.item.index", itemCounter);
				try {
					ListedResource listedResource = resourceItemParser.parse(resourceListItem);
					if (mode.isUpdateResource()) {
						try {
							Document resourceDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + listedResource.getId()).getDocument();
							listedResource = resourcePageParser.parse(resourceDocument, listedResource);
						} catch (Throwable throwable) {
							log.error("Unexpected exception while parsing full resource #" + listedResource.getId(), throwable);
						}
						// Do this inside of here, so we can be sure we actually have a Resource object
						if (mode.isUpdateResourceVersions()) {
							ResourceVersionItemParser resourceVersionItemParser = new ResourceVersionItemParser();
							try {
								Document versionDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + listedResource.getId()+"/history").getDocument();
								Element resourceHistory = versionDocument.select("table.resourceHistory").first();
								Elements versionElements = resourceHistory.select("tr.dataRow");
								boolean first=true;
								for (Element versionElement : versionElements) {
									if (first) {
										// The first row is the table header
										first=false;
										continue;
									}

									ResourceVersion resourceVersion=resourceVersionItemParser.parse(versionElement);
									((Resource) listedResource).getVersions().add(resourceVersion);
								}
							} catch (Throwable throwable) {
								log.error("Unexpected exception while parsing resource versions for #" + listedResource.getId(), throwable);
							}
						}
					}


					ListedResource databaseResource = databaseClient.getResource(listedResource.getId());
					if (databaseResource != null) {
						log.info("Updating existing resource #" + listedResource.getId());
						databaseClient.updateResource(listedResource);
					} else {
						log.info("Inserting new resource #" + listedResource.getId());
						databaseClient.insertResource(listedResource);
					}

					ListedAuthor databaseAuthor = databaseClient.getAuthor(listedResource.getAuthor().getId());
					if (databaseAuthor != null) {
						log.info("Updating existing author #" + listedResource.getAuthor().getId());
						databaseClient.updateAuthor(listedResource.getAuthor());
					} else {
						log.info("Inserting new author #" + listedResource.getAuthor().getId());
						databaseClient.insertAuthor(listedResource.getAuthor());
					}

					ListedCategory databaseCategory = databaseClient.getCategory(listedResource.getCategory().getId());
					if (databaseCategory != null) {
						log.info("Updating existing category #" + listedResource.getCategory().getId());
						databaseClient.updateCategory(listedResource.getCategory());
					} else {
						log.info("Inserting new category #" + listedResource.getCategory().getId());
						databaseClient.insertCategory(listedResource.getCategory());
					}
				} catch (Throwable throwable) {
					log.error("Unexpected exception while parsing item #" + itemCounter + " on page " + pageCounter, throwable);
				}
			}
		}

		long end = System.currentTimeMillis();
		databaseClient.updateStatus("fetch.end", end);
	}

}
