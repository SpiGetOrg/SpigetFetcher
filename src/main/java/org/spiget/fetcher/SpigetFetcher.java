package org.spiget.fetcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.spiget.client.SpigetClient;
import org.spiget.client.SpigetDownload;
import org.spiget.client.SpigetResponse;
import org.spiget.data.author.Author;
import org.spiget.data.author.ListedAuthor;
import org.spiget.data.resource.ListedResource;
import org.spiget.data.resource.Resource;
import org.spiget.data.resource.ResourceReview;
import org.spiget.data.resource.update.ResourceUpdate;
import org.spiget.data.resource.version.ResourceVersion;
import org.spiget.data.webhook.event.author.NewAuthorEvent;
import org.spiget.data.webhook.event.resource.NewResourceEvent;
import org.spiget.data.webhook.event.resource.ResourceUpdateEvent;
import org.spiget.database.DatabaseClient;
import org.spiget.fetcher.webhook.WebhookExecutor;
import org.spiget.parser.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Iterator;

@Log4j2
public class SpigetFetcher {

	public static JsonObject config;

	public static DatabaseClient databaseClient;

	WebhookExecutor webhookExecutor;

	public SpigetFetcher() {
	}

	public SpigetFetcher init() throws IOException {
		config = new JsonParser().parse(new FileReader("config.json")).getAsJsonObject();
		SpigetClient.config = config;
		SpigetClient.userAgent = config.get("request.userAgent").getAsString();
		SpigetClient.loadCookiesFromFile();
		webhookExecutor = new WebhookExecutor();
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
				databaseClient.collectionCount();
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

		return this;
	}

	public void fetch() {
		log.info("----- Fetcher started -----");
		long start = System.currentTimeMillis();
		databaseClient.updateStatus("fetch.start", start);
		databaseClient.updateStatus("fetch.end", 0);

		boolean modeResources = config.get("fetch.mode.resources").getAsBoolean();
		boolean modeResourceVersions = config.get("fetch.mode.resource.versions").getAsBoolean();
		boolean modeResourceUpdates = config.get("fetch.mode.resource.updates").getAsBoolean();
		boolean modeResourceReviews = config.get("fetch.mode.resource.reviews").getAsBoolean();

		int stopOnExisting = config.get("fetch.resources.stopOnExisting").getAsInt();
		int existingCount = 0;
		boolean fetchStopped = false;

		int pageAmount = config.get("fetch.resources.pages").getAsInt();
		int pageOffset = config.get("fetch.resources.pageOffset").getAsInt();
		boolean inverted = config.get("fetch.resources.inverted").getAsBoolean();
		databaseClient.updateStatus("fetch.page.amount", pageAmount);
		int pageCounter = 0;
		Paginator resourceListPaginator = new Paginator(SpigetClient.BASE_URL + "resources/?page=%s", pageAmount, inverted);
		//noinspection ForLoopReplaceableByForEach
		for (Iterator<Document> iterator = resourceListPaginator.iterator(); iterator.hasNext(); ) {
			if (fetchStopped) { break; }
			pageCounter++;
			log.info("Fetching page " + pageCounter + "/" + pageAmount);
			try {
				databaseClient.updateStatus("fetch.page.index", pageCounter);
				Document document = iterator.next();
				if (pageCounter < pageOffset) {
					log.info("Skipping page #" + pageCounter + " (Offset: " + pageOffset + ")");
					continue;
				}

				ResourceListItemParser resourceItemParser = new ResourceListItemParser();
				ResourcePageParser resourcePageParser = new ResourcePageParser();
				Elements resourceListItems = document.select("li.resourceListItem");
				int itemCounter = 0;
				for (Element resourceListItem : resourceListItems) {
					if (fetchStopped) { break; }
					itemCounter++;
					databaseClient.updateStatus("fetch.page.item.index", itemCounter);
					databaseClient.updateStatus("fetch.page.item.state", "list");
					try {
						ListedResource listedResource = resourceItemParser.parse(resourceListItem);
						if (modeResources) {
							databaseClient.updateStatus("fetch.page.item.state", "general");
							try {
								Document resourceDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + listedResource.getId()).getDocument();
								listedResource = resourcePageParser.parse(resourceDocument, listedResource);
							} catch (Throwable throwable) {
								log.error("Unexpected exception while parsing full resource #" + listedResource.getId(), throwable);
								continue;
							}
							// Do this inside of here, so we can be sure we actually have a Resource object
							if (modeResourceVersions) {
								databaseClient.updateStatus("fetch.page.item.state", "versions");
								ResourceVersionItemParser resourceVersionItemParser = new ResourceVersionItemParser();
								try {
									Document versionDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + listedResource.getId() + "/history").getDocument();
									Element resourceHistory = versionDocument.select("table.resourceHistory").first();
									Elements versionElements = resourceHistory.select("tr.dataRow");
									boolean first = true;
									for (Element versionElement : versionElements) {
										if (first) {
											// The first row is the table header
											first = false;
											continue;
										}

										ResourceVersion resourceVersion = resourceVersionItemParser.parse(versionElement);
										((Resource) listedResource).getVersions().add(resourceVersion);

										databaseClient.updateOrInsertVersion(listedResource, resourceVersion);
									}
									listedResource.setVersion(((Resource) listedResource).getVersions().get(0));
								} catch (Throwable throwable) {
									log.error("Unexpected exception while parsing resource versions for #" + listedResource.getId(), throwable);
								}
							}

							if (modeResourceUpdates) {
								databaseClient.updateStatus("fetch.page.item.state", "updates");
								ResourceUpdateItemParer resourceUpdateItemParer = new ResourceUpdateItemParer();
								ResourceUpdateParser resourceUpdateParser = new ResourceUpdateParser();
								try {
									int pageCount = Paginator.parseDocumentPageCount(SpigetClient.get(SpigetClient.BASE_URL + "resources/" + listedResource.getId() + "/updates").getDocument());
									int maxPage = Math.min(pageCount, config.get("fetch.resources.updates.maxPage").getAsInt());
									Paginator resourceUpdatesPaginator = new Paginator(SpigetClient.BASE_URL + "resources/" + listedResource.getId() + "/updates?page=%s", maxPage, false);
									for (Document updateDocument : resourceUpdatesPaginator) {
										Element resourceUpdatesTab = updateDocument.select("li.resourceTabUpdates").first();
										if (resourceUpdatesTab == null || !resourceUpdatesTab.hasClass("active")) {
											// We're not on the updates page, which probably means the resource hasn't been updated yet.
											break;
										}

										Elements resourceUpdateElements = updateDocument.select("li.resourceUpdate");
										for (Element resourceUpdateElement : resourceUpdateElements) {
											ResourceUpdate resourceUpdate = resourceUpdateItemParer.parse(resourceUpdateElement);
											Document resourceUpdateDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + listedResource.getId() + "/update?update=" + resourceUpdate.getId()).getDocument();
											resourceUpdate = resourceUpdateParser.parse(resourceUpdateDocument, resourceUpdate);

											Document resourceUpdateLikesDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + listedResource.getId() + "/update-likes?resource_update_id=" + resourceUpdate.getId()).getDocument();
											Elements likesElements = resourceUpdateLikesDocument.select("li.memberListItem");
											resourceUpdate.setLikes(likesElements.size());

											((Resource) listedResource).getUpdates().add(resourceUpdate);
											((Resource) listedResource).setLikes(((Resource) listedResource).getLikes() + resourceUpdate.getLikes());

											databaseClient.updateOrInsertUpdate(listedResource, resourceUpdate);
										}
									}
								} catch (Throwable throwable) {
									log.error("Unexpected exception while parsing resource updates for #" + listedResource.getId(), throwable);
								}
							}
							if (modeResourceReviews) {
								databaseClient.updateStatus("fetch.page.item.state", "reviews");
								ResourceReviewItemParser reviewItemParser = new ResourceReviewItemParser();
								try {
									int pageCount = Paginator.parseDocumentPageCount(SpigetClient.get(SpigetClient.BASE_URL + "resources/" + listedResource.getId() + "/reviews").getDocument());
									int maxPage = Math.min(pageCount, config.get("fetch.resources.reviews.maxPage").getAsInt());
									Paginator resourceReviewsPaginator = new Paginator(SpigetClient.BASE_URL + "resources/" + listedResource.getId() + "/reviews?page=%s", maxPage, false);
									for (Document reviewDocument : resourceReviewsPaginator) {
										Element resourceReviewsTab = reviewDocument.select("li.resourceTabReviews").first();
										if (resourceReviewsTab == null || !resourceReviewsTab.hasClass("active")) {
											// We're not on the reviews page, which probably means the resource hasn't been reviewed yet.
											break;
										}

										Elements reviewElements = reviewDocument.select("li.review");
										for (Element reviewElement : reviewElements) {
											ResourceReview review = reviewItemParser.parse(reviewElement);

											((Resource) listedResource).getReviews().add(review);

											Author databaseReviewAuthor = databaseClient.getAuthor(review.getAuthor().getId());
											if (databaseReviewAuthor == null) {// Only insert if the document doesn't exist, so we don't accidentally overwrite existing data
												databaseClient.insertAuthor(review.getAuthor());
											}

											databaseClient.updateOrInsertReview(listedResource, review);
										}
									}
								} catch (Throwable throwable) {
									log.error("Unexpected exception while parsing resource reviews for #" + listedResource.getId(), throwable);
								}
							}
							if (!((Resource) listedResource).isExternal()) {
								if (SpigetFetcher.config.get("fetch.resources.download").getAsBoolean()) {
									String basePath = SpigetFetcher.config.get("fetch.resources.downloadBase").getAsString();
									if (basePath != null && !basePath.isEmpty()) {
										databaseClient.updateStatus("fetch.page.item.state", "download");
										log.info("Downloading #" + listedResource.getId());
										try {
											File outputFile = makeDownloadFile(basePath, String.valueOf(listedResource.getId()), ((Resource) listedResource).getFile().getType());
											if (outputFile.exists()) {
												log.debug("Overwriting existing file");
											} else {
												outputFile.createNewFile();

												String os = System.getProperty("os.name").toLowerCase();
												if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
													Runtime.getRuntime().exec("chmod 777 " + outputFile);
												}

												outputFile.setReadable(true);
												outputFile.setWritable(true);
											}

											log.info("Downloading '" + ((Resource) listedResource).getFile().getUrl() + "' to '" + outputFile + "'...");
											SpigetDownload download = SpigetClient.download(SpigetClient.BASE_URL + ((Resource) listedResource).getFile().getUrl());
											ReadableByteChannel channel = Channels.newChannel(download.getInputStream());
											FileOutputStream out = new FileOutputStream(outputFile);
											out.getChannel().transferFrom(channel, 0, 10000000L/*10MB, should be enough*/);
											out.flush();
											out.close();
										} catch (IOException e) {
											log.warn("Download for resource #" + listedResource.getId() + " failed", e);
										}
									}
								}
							}
						}

						databaseClient.updateStatus("fetch.page.item.state", "database");

						ListedResource databaseResource = databaseClient.getResource(listedResource.getId());
						if (databaseResource != null) {
							log.info("Updating existing resource #" + listedResource.getId());
							databaseClient.updateResource(listedResource);

							if (databaseResource.getUpdateDate() != listedResource.getUpdateDate()) {// There was actually an update
								existingCount = 0;
								if (listedResource instanceof Resource) {
									webhookExecutor.callEvent(new ResourceUpdateEvent((Resource) listedResource, listedResource.getVersion().getName()));
								}
							} else {
								existingCount++;
								// If we stop on inverted, it would stop immediately
								if (!inverted && stopOnExisting != -1 && existingCount > stopOnExisting) {
									log.info("Last new resource found (" + pageCounter + "." + itemCounter + ") #" + existingCount + ". Stopping.");
									fetchStopped = true;
									break;
								}
							}
						} else {
							existingCount = 0;
							log.info("Inserting new resource #" + listedResource.getId());
							databaseClient.insertResource(listedResource);

							if (listedResource instanceof Resource) {
								webhookExecutor.callEvent(new NewResourceEvent((Resource) listedResource));
							}
						}

						ListedAuthor databaseAuthor = databaseClient.getAuthor(listedResource.getAuthor().getId());
						if (databaseAuthor != null) {
							log.info("Updating existing author #" + listedResource.getAuthor().getId());
							databaseClient.updateAuthor(listedResource.getAuthor());
						} else {
							log.info("Inserting new author #" + listedResource.getAuthor().getId());
							databaseClient.insertAuthor(listedResource.getAuthor());

							if (listedResource.getAuthor() instanceof Author) {
								webhookExecutor.callEvent(new NewAuthorEvent((Author) listedResource.getAuthor()));
							}
						}

						databaseClient.updateOrInsertCategory(listedResource.getCategory());
					} catch (Throwable throwable) {
						log.error("Unexpected exception while parsing item #" + itemCounter + " on page " + pageCounter, throwable);
					}

					if (itemCounter % 10 == 0) {
						databaseClient.updateSystemStats("fetch.");
					}
				}
			} catch (Throwable throwable) {
				log.log(Level.ERROR, "Unexpected exception while parsing page #" + pageCounter, throwable);
			}

			databaseClient.updateSystemStats("fetch.");
		}

		long end = System.currentTimeMillis();
		databaseClient.updateStatus("fetch.end", end);

		log.info("Waiting for (" + webhookExecutor.pendingCalls + ") Webhooks to complete...");
		while (!webhookExecutor.isFinished()) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				log.log(Level.ERROR, "Webhook-delay interrupted", e);
			}
		}
	}

	File makeDownloadFile(String baseDir, String resource, String type) {
		String[] split = resource.split("");
		if (split.length == 0) {
			log.warn("Invalid resource ID! split.length == 0");
			return new File(new File(baseDir, "INVALID"), String.valueOf(System.currentTimeMillis()));
		}

		File finalFolder = new File(baseDir);
		for (int i = 0; i < split.length - 1; i++) {
			String s = split[i];
			finalFolder = new File(finalFolder, s);
			if (!finalFolder.exists()) {
				finalFolder.mkdir();
				finalFolder.setReadable(true, false);
				finalFolder.setWritable(true, false);
			}
		}

		return new File(finalFolder, resource + type);
	}

}
