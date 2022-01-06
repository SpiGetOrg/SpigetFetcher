package org.spiget.fetcher;

import com.backblaze.b2.client.B2StorageClient;
import com.backblaze.b2.client.B2StorageClientFactory;
import com.backblaze.b2.client.contentSources.B2ContentTypes;
import com.backblaze.b2.client.contentSources.B2FileContentSource;
import com.backblaze.b2.client.structures.B2FileVersion;
import com.backblaze.b2.client.structures.B2UploadFileRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.sentry.Sentry;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.influxdb.dto.Point;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.spiget.client.*;
import org.spiget.client.json.JsonClient;
import org.spiget.client.json.JsonResponse;
import org.spiget.data.UpdateRequest;
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

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Paths;
import java.util.*;

@Log4j2
public class SpigetFetcher {

    public static JsonObject config;

    public static DatabaseClient databaseClient;
    static B2StorageClient b2Client;

    WebhookExecutor webhookExecutor;

    static SpigetMetrics metrics;

    Set<String> downloadedResources = new HashSet<>();

    public SpigetFetcher() {
    }

    @Nullable
    public SpigetFetcher init() {
        log.debug("init");
        Sentry.init(options -> {
            options.setEnableExternalConfiguration(true);
        });

        log.info("loading config");
        try {
            config = new JsonParser().parse(new FileReader("config.json")).getAsJsonObject();
            SpigetClient.config = config;
            SpigetClient.userAgent = config.get("request.userAgent").getAsString();
            PuppeteerClient.DIR_NAME = config.get("puppeteer.path").getAsString();
            PuppeteerClient.DIR = Paths.get(PuppeteerClient.DIR_NAME);
            //		JsonArray hostArray = config.getAsJsonArray("puppeteer.hosts");
            //		PuppeteerClient2.HOST = hostArray.get(ThreadLocalRandom.current().nextInt(hostArray.size())).getAsString();
            //		log.info("Using puppeteer host " + PuppeteerClient2.HOST);
            SpigetClient.loadCookiesFromFile();

            metrics = new SpigetMetrics(config);
            SpigetClient.metrics = metrics.metrics;
            SpigetClient.project = "fetcher";

            webhookExecutor = new WebhookExecutor();
            log.info("registering shutdown hook");
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    try {
                        log.info("Saving cookies...");
                        SpigetClient.saveCookiesToFile();
                    } catch (IOException e) {
                        Sentry.captureException(e);
                        log.warn("Failed to save cookies", e);
                    }

                    databaseClient.updateStatus("fetch.end", System.currentTimeMillis());

                    try {
                        log.info("Disconnecting database...");
                        databaseClient.disconnect();
                    } catch (IOException e) {
                        Sentry.captureException(e);
                        log.warn("Failed to disconnect from database", e);
                    }
                }
            });

            {
                log.info("Initializing & testing database connection...");
                long testStart = System.currentTimeMillis();
                try {
                    if (config.has("database.url")) {
                        databaseClient = new DatabaseClient(config.get("database.url").getAsString(), config.get("database.name").getAsString());
                    } else {
                        databaseClient = new DatabaseClient(
                                config.get("database.name").getAsString(),
                                config.get("database.host").getAsString(),
                                config.get("database.port").getAsInt(),
                                config.get("database.user").getAsString(),
                                config.get("database.pass").getAsString().toCharArray(),
                                config.get("database.db").getAsString());
                    }
                    databaseClient.connect(config.get("database.timeout").getAsInt());
                    databaseClient.collectionCount();
                    log.info("Connection successful (" + (System.currentTimeMillis() - testStart) + "ms)");
                } catch (Exception e) {
                    Sentry.captureException(e);
                    log.fatal("Connection failed after " + (System.currentTimeMillis() - testStart) + "ms", e);
                    log.fatal("Aborting.");

                    Discord.postMessage("⚠️Database connection failed with exception!", config);

                    System.exit(-1);
                    return null;
                }
            }

            {
                log.info("Initializing B2...");
                try {
                    b2Client = B2StorageClientFactory.createDefaultFactory()
                            .create(config.get("b2.app").getAsString(), config.get("b2.key").getAsString(), "SpigetFetcher");
                } catch (Exception e) {
                    Sentry.captureException(e);
                    log.fatal("Failed to init B2", e);
                }
            }

            {
                log.info("Testing SpigotMC connection...");
                long testStart = System.currentTimeMillis();
                try {
                    SpigetResponse response = SpigetClient.get("https://www.spigotmc.org");
                    int code = response.getCode();
                    if (code >= 200 && code < 400) {
                        log.info("Connection successful (" + (System.currentTimeMillis() - testStart) + "ms)");
                    } else {
                        log.fatal("Connection failed with code " + code + " after " + (System.currentTimeMillis() - testStart) + "ms");
                        log.fatal("Aborting.");
                        log.info(response.getDocument().body());

                        Discord.postMessage("⚠SpigotMC connection failed with code " + code + "!", config);

                        System.exit(-1);
                        return null;
                    }
                } catch (Exception e) {
                    Sentry.captureException(e);
                    log.fatal("Connection failed after " + (System.currentTimeMillis() - testStart) + "ms", e);
                    log.fatal("Aborting.");

                    Discord.postMessage("⚠SpigotMC connection failed with exception!", config);

                    System.exit(-1);
                    return null;
                }
            }

        } catch (Throwable throwable) {
            Sentry.captureException(throwable);
            log.log(Level.ERROR, "", throwable);
            throw new RuntimeException(throwable);
        }
        return this;
    }

    public void fetch() {
        log.debug("fetch");
        log.info("----- Fetcher started -----");
        long start = System.currentTimeMillis();
        try {
            databaseClient.updateStatus("fetch.start", start);
            long lastEnd = ((Number) databaseClient.getStatus("fetch.end", 0)).longValue();
            databaseClient.updateStatus("fetch.lastEnd", lastEnd);
            databaseClient.updateStatus("fetch.end", 0);
        } catch (Exception e) {
            Sentry.captureException(e);
            log.log(Level.ERROR, "Failed to update status", e);
        }

        boolean modeResources = config.get("fetch.mode.resources").getAsBoolean();
        boolean modeResourceVersions = config.get("fetch.mode.resource.versions").getAsBoolean();
        boolean modeResourceUpdates = config.get("fetch.mode.resource.updates").getAsBoolean();
        boolean modeResourceReviews = config.get("fetch.mode.resource.reviews").getAsBoolean();
        boolean modeResourceDocumentation = config.get("fetch.mode.resource.documentation").getAsBoolean();

        int stopOnExisting = config.get("fetch.resources.stopOnExisting").getAsInt();
        int existingCount = 0;
        int newCount = 0;
        boolean fetchStopped = false;

        int pageAmount = config.get("fetch.resources.pages").getAsInt();
        int pageOffset = config.get("fetch.resources.pageOffset").getAsInt();
        boolean inverted = config.get("fetch.resources.inverted").getAsBoolean();
        databaseClient.updateStatus("fetch.page.amount", pageAmount);
        Set<Integer> updatedResourceIds = new HashSet<>();
        int pageCounter = 0;
        Paginator resourceListPaginator = new Paginator(SpigetClient.BASE_URL + "resources/?page=%s", pageAmount, inverted);
        if (!config.get("fetch.requestsOnly").getAsBoolean()) {
            //noinspection ForLoopReplaceableByForEach
            for (Iterator<Document> iterator = resourceListPaginator.iterator(); iterator.hasNext(); ) {
                if (fetchStopped) {
                    break;
                }
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
                    if (resourceListItems.isEmpty()) {
                        log.warn("Page has " + resourceListItems.size() + " resource items");
                        log.info(document);
                        Discord.postMessage("⚠Resource page has no resource items!", config);
                    } else {
                        log.debug("Page has " + resourceListItems.size() + " resource items");
                    }
                    int itemCounter = 0;
                    for (Element resourceListItem : resourceListItems) {
                        if (fetchStopped) {
                            break;
                        }
                        itemCounter++;
                        databaseClient.updateStatus("fetch.page.item.index", itemCounter);
                        databaseClient.updateStatus("fetch.page.item.state", "list");
                        try {
                            ListedResource listedResource = resourceItemParser.parse(resourceListItem);

                            if (listedResource != null) {
                                if (modeResources) {
                                    // Update the resource
                                    listedResource = updateResource(listedResource, resourcePageParser);

                                    final int resId = listedResource.getId();
                                    databaseClient.deleteUpdateRequest(new UpdateRequest() {{
                                        this.setRequestedId(resId);
                                    }});
                                }

                                databaseClient.updateStatus("fetch.page.item.state", "database");

                                ListedResource databaseResource = databaseClient.getResource(listedResource.getId());
                                if (databaseResource != null) {
                                    if (modeResources) {
                                        listedResource = updateResourceExtras((Resource) listedResource, modeResourceVersions, modeResourceUpdates, modeResourceReviews, modeResourceDocumentation, databaseResource.getUpdateDate() != listedResource.getUpdateDate());
                                    }
                                    log.info("Updating existing resource #" + listedResource.getId());

                                    updatedResourceIds.add(listedResource.getId());
                                    databaseClient.updateResource(listedResource);

                                    if (databaseResource.getUpdateDate() != listedResource.getUpdateDate()) {// There was actually an update
                                        newCount++;
                                        existingCount = 0;
                                        if (listedResource instanceof Resource) {
                                            int updateId = -1;
                                            List<ResourceUpdate> updates = ((Resource) listedResource).getUpdates();
                                            if (updates != null && !updates.isEmpty()) {
                                                updateId = updates.get(0).getId();
                                            }
                                            webhookExecutor.callEvent(new ResourceUpdateEvent((Resource) listedResource, listedResource.getVersion().getName(), updateId));
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
                                    if (modeResources) {
                                        listedResource = updateResourceExtras((Resource) listedResource, modeResourceVersions, modeResourceUpdates, modeResourceReviews, modeResourceDocumentation, true);
                                    }
                                    log.info("Inserting new resource #" + listedResource.getId());
                                    updatedResourceIds.add(listedResource.getId());
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
                            }
                        } catch (Throwable throwable) {
                            Sentry.captureException(throwable);
                            log.error("Unexpected exception while parsing item #" + itemCounter + " on page " + pageCounter, throwable);
                        }

                        if (itemCounter % 10 == 0) {
                            databaseClient.updateSystemStats("fetch.");
                        }
                    }
                } catch (Throwable throwable) {
                    Sentry.captureException(throwable);
                    log.log(Level.ERROR, "Unexpected exception while parsing page #" + pageCounter, throwable);
                }

                databaseClient.updateSystemStats("fetch.");

                if (pageCounter % 2 == 0) {
                    HtmlUnitClient.disposeClient();
                }
            }
            log.log(Level.INFO, "Finished live resource fetch");
        }

        try {
            metrics.metrics.getInflux().write(Point
                    .measurement("new_resources")
                    .addField("count", newCount)
                    .build());
        } catch (Exception e) {
            Sentry.captureException(e);
        }

        try {
            Thread.sleep(2000);
        } catch (Exception ignored) {
        }

        try {
            log.log(Level.INFO, "Running update request fetch");
            int maxResourceRequest = config.get("resourceRequest.max").getAsInt();
            Set<UpdateRequest> updateRequests = databaseClient.getUpdateRequests(maxResourceRequest);
            if (updateRequests != null && !updateRequests.isEmpty()) {
                int updateRequestCount = updateRequests.size();
                try {
                    metrics.metrics.getInflux().write(Point
                            .measurement("update_requests")
                            .addField("count", updateRequestCount)
                            .build());
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
                long updateStart = System.currentTimeMillis();
                log.log(Level.INFO, "Fetching (" + updateRequestCount + ") resources with requested update...");
                ResourcePageParser resourcePageParser = new ResourcePageParser();
                int c = 0;
                for (UpdateRequest request : updateRequests) {
                    if (c++ > maxResourceRequest) {
                        log.info("Max Resource Requests processed. Stopping.");
                        break;
                    }
                    if (updatedResourceIds.contains(request.getRequestedId())) {
                        databaseClient.deleteUpdateRequest(request);
                        continue;
                    }
                    Resource resource;
                    try {
                        resource = databaseClient.getResource(request.getRequestedId());
                    } catch (Exception e) {
                        Sentry.captureException(e);
                        log.log(Level.WARN, "Failed to query resource data for " + request.getRequestedId(), e);
                        continue;
                    }
                    boolean existed = resource != null;
                    if (resource == null) {
                        resource = new Resource(request.getRequestedId());
                    }
                    try {
                        long oldUpdateDate = resource.getUpdateDate();

                        resource = updateResource(resource, resourcePageParser);
                        if (resource == null) {
                            if (request.isDelete()) {
                                log.log(Level.INFO, "Deleting resource #" + request.getRequestedId() + " since it has likely been deleted.");
                                databaseClient.deleteResource(request.getRequestedId());
                            }
                            databaseClient.deleteUpdateRequest(request);
                            continue;
                        }
                        updateResourceExtras(resource, request.isVersions(), request.isUpdates(), request.isReviews(), true, true);

                        if (existed) {
                            log.info("Updating existing resource #" + resource.getId());
                        } else {
                            log.log(Level.INFO, "Handling resource update request for a resource that wasn't in the database already (" + request.getRequestedId() + ")");
                        }

                        databaseClient.updateResource(resource);
                        updatedResourceIds.add(resource.getId());

                        databaseClient.deleteUpdateRequest(request);
                    } catch (Throwable throwable) {
                        Sentry.captureException(throwable);
                        log.error("Unexpected exception while updating resource #" + request.getRequestedId(), throwable);
                    }
                }
                log.log(Level.INFO, "Finished requested updates. Took " + (((double) System.currentTimeMillis() - updateStart) / 1000 / 60) + " minutes to update " + updateRequestCount + " resources.");
            }
        } catch (Throwable throwable) {
            Sentry.captureException(throwable);
            log.log(Level.ERROR, "Update Request exception", throwable);
        }

        try {
            metrics.metrics.getInflux().write(Point
                    .measurement("downloaded_resources")
                    .addField("count", downloadedResources.size())
                    .build());
        } catch (Exception e) {
            Sentry.captureException(e);
        }

        try {
            if (!downloadedResources.isEmpty()) {
                JsonArray cdnFiles = new JsonArray();
                downloadedResources.forEach(r -> {
                    cdnFiles.add("https://cdn.spiget.org/file/spiget-resources/" + r/*1234.jar*/);
                });
                purgeCloudflareCache(cdnFiles);

                JsonArray resourceFiles = new JsonArray();
                JsonArray downloadFiles = new JsonArray();
                JsonArray versionFiles = new JsonArray();
                JsonArray latestVersionFiles = new JsonArray();
                JsonArray updateFiles = new JsonArray();
                updatedResourceIds.forEach(r -> {
                    resourceFiles.add("https://api.spiget.org/v2/resources/" + r/*54321*/);
                    downloadFiles.add("https://api.spiget.org/v2/resources/" + r + "/download");
                    versionFiles.add("https://api.spiget.org/v2/resources/" + r + "/versions");
                    latestVersionFiles.add("https://api.spiget.org/v2/resources/" + r + "/versions/latest");
                    updateFiles.add("https://api.spiget.org/v2/resources/" + r + "/updates");
                });

                purgeCloudflareCache(resourceFiles);
                purgeCloudflareCache(downloadFiles);
                purgeCloudflareCache(versionFiles);
                purgeCloudflareCache(latestVersionFiles);
                purgeCloudflareCache(updateFiles);
            }
        } catch (Exception e) {
            Sentry.captureException(e);
            log.log(Level.WARN, "Failed to invalidate cloudflare cache", e);
            if (e instanceof HttpStatusException) {
                log.log(Level.WARN, ((HttpStatusException) e).getStatusCode() + "");
            }
        }

        long end = System.currentTimeMillis();
        try {
            databaseClient.updateStatus("fetch.end", end);
            databaseClient.updateStatus("fetch.duration", (end - start));
        } catch (Exception e) {
            Sentry.captureException(e);
            log.log(Level.ERROR, "Failed to update status", e);
        }
        try {
            metrics.metrics.getInflux().write(Point
                    .measurement("fetch_duration")
                    .addField("duration", (end - start))
                    .build());
        } catch (Exception e) {
            Sentry.captureException(e);
        }

        log.info("Waiting for (" + webhookExecutor.pendingCalls + ") Webhooks to complete...");
        while (!webhookExecutor.isFinished()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Sentry.captureException(e);
                log.log(Level.ERROR, "Webhook-delay interrupted", e);
            }
        }

        System.exit(0);
    }

    private void purgeCloudflareCache(JsonArray files) throws IOException {
        JsonObject body = new JsonObject();
        body.add("files", files);
        Connection.Response response = Jsoup.connect("https://api.cloudflare.com/client/v4/zones/" + config.get("cf.zone").getAsString() + "/purge_cache")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.get("cf.token").getAsString())
                .requestBody(JsonClient.gson.toJson(body))
                .method(Connection.Method.POST)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .execute();
        log.log(Level.INFO, "CF purge " + response.statusCode() + " " + response.statusMessage());
        if (response.statusCode() != 200) {
            log.warn(response.body());
        }
    }

    private boolean checkIfResourceExists(int id) {
        try {
            JsonResponse response = JsonClient.get("https://api.spigotmc.org/simple/0.1/index.php?action=getResource&id=" + id);
            if (response != null) {
                if (response.code == 404) {
                    return false;
                }
                if (response.code == 200) {
                    return true;
                }
            }
        } catch (Exception e) {
            Sentry.captureException(e);
            log.error("Failed to check if resource #" + id + " exists", e);
        }
        return true;// defaulting to true, in case of exceptions on existing stuff
    }

    @Nullable
    private Resource updateResource(@NotNull ListedResource listedResource, @NotNull ResourcePageParser resourcePageParser) {
        databaseClient.updateStatus("fetch.page.item.state", "general");
        try {
            SpigetResponse response = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + listedResource.getId());
            if (response.getCode() != 200) {// This SHOULD only happen if this method is called via the update requests part
                log.warn("Failed to update resource #" + listedResource.getId() + ": page returned non-OK status code (" + response.getCode() + ")");
//                log.warn(response.getDocument().toString());
                if (response.getCode() == 429) {
                    try {
                        Thread.sleep(500);
                    } catch (Exception e) {
                    }
                }
                if (response.getCode() == 403) {
                    return null;
                }
                throw new RuntimeException("Failed to update resource #" + listedResource.getId() + ": page returned non-OK status code (" + response.getCode() + ")");
            }
            Document resourceDocument = response.getDocument();
            return resourcePageParser.parse(resourceDocument, listedResource);
        } catch (Throwable throwable) {
            Sentry.captureException(throwable);
            log.error("Unexpected exception while parsing full resource #" + listedResource.getId(), throwable);
            throw new RuntimeException(throwable);
        }
    }

    private Resource updateResourceExtras(@NotNull Resource resource, boolean modeResourceVersions, boolean modeResourceUpdates, boolean modeResourceReviews, boolean modeResourceDocumentation, boolean modeResourceDownload) throws InterruptedException {
        // Do this inside of here, so we can be sure we actually have a Resource object
        if (modeResourceVersions) {
            updateResourceVersions(resource);
        }
        if (modeResourceUpdates) {
            updatedResourceUpdates(resource);
        }
        if (modeResourceReviews) {
            updateResourceReviews(resource);
        }
        if (modeResourceDocumentation) {
            updateResourceDocumentation(resource);
        }
        if (modeResourceDownload && !resource.isExternal() && !resource.isPremium()) {
            if (SpigetFetcher.config.get("fetch.resources.download").getAsBoolean()) {
                downloadResource(resource);
            }
        }
        return resource;
    }

    void writeDocumentToFile(Document document, String name) {
        try {
            File file = new File("/home/spiget/spiget/v2/debug/" + name + ".html");
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(document.toString());
                writer.flush();
            }
        } catch (Exception e) {
            log.log(Level.WARN, "", e);
        }
    }

    private void updateResourceVersions(@NotNull Resource resource) {
        databaseClient.updateStatus("fetch.page.item.state", "versions");
        ResourceVersionItemParser resourceVersionItemParser = resource.isPremium() ? new PremiumResourceVersionItemParser() : new ResourceVersionItemParser();
        try {
            Document versionDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + resource.getId() + "/history").getDocument();

//            writeDocumentToFile(versionDocument, resource.getId() + "-history");

            Element resourceHistory = versionDocument.select("table.resourceHistory").first();
            Elements versionElements = resourceHistory.select("tr.dataRow");
            boolean first = true;
            int i = 0;
            for (Element versionElement : versionElements) {
                i++;
                if (first) {
                    // The first row is the table header
                    first = false;
                    continue;
                }

                ResourceVersion resourceVersion = resourceVersionItemParser.parse(versionElement, resource);
                try {
                    UUID uuid = ResourceVersion.makeUuid(resource.getId(), resource.getAuthor().getId(), resourceVersion.getName(), versionElements.size() - i/*initial version doesn't count as update*/, new Date(resourceVersion.getReleaseDate() * 1000));
                    resourceVersion.setUuid(uuid);
                } catch (Exception e) {
                    Sentry.captureException(e);
                    log.log(Level.ERROR, "Failed to make UUID for version, Resource: " + resource.getId() + ", Author: " + resource.getAuthor().getId() + ", Version: " + resourceVersion.getName(), e);
                }

                resource.getVersions().add(resourceVersion);
                resourceVersion.setResource(resource.getId());

                databaseClient.updateOrInsertVersion(resource, resourceVersion);
            }
            resource.setVersion(resource.getVersions().get(0));
        } catch (Throwable throwable) {
            Sentry.captureException(throwable);
            log.error("Unexpected exception while parsing resource versions for #" + resource.getId(), throwable);
        }
    }

    private void updatedResourceUpdates(@NotNull Resource resource) {
        databaseClient.updateStatus("fetch.page.item.state", "updates");
        ResourceUpdateItemParer resourceUpdateItemParer = new ResourceUpdateItemParer();
        ResourceUpdateParser resourceUpdateParser = new ResourceUpdateParser();
        try {
            int pageCount = Paginator.parseDocumentPageCount(SpigetClient.get(SpigetClient.BASE_URL + "resources/" + resource.getId() + "/updates").getDocument());
            int maxPage = Math.min(pageCount, config.get("fetch.resources.updates.maxPage").getAsInt());
            Paginator resourceUpdatesPaginator = new Paginator(SpigetClient.BASE_URL + "resources/" + resource.getId() + "/updates?page=%s", maxPage, false);
            for (Document updateDocument : resourceUpdatesPaginator) {
                Element resourceUpdatesTab = updateDocument.select("li.resourceTabUpdates").first();
                if (resourceUpdatesTab == null || !resourceUpdatesTab.hasClass("active")) {
                    // We're not on the updates page, which probably means the resource hasn't been updated yet.
                    break;
                }

//                writeDocumentToFile(updateDocument, resource.getId() + "-update");

                Elements resourceUpdateElements = updateDocument.select("li.resourceUpdate");
                for (Element resourceUpdateElement : resourceUpdateElements) {
                    ResourceUpdate resourceUpdate = resourceUpdateItemParer.parse(resourceUpdateElement);
                    Document resourceUpdateDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + resource.getId() + "/update?update=" + resourceUpdate.getId()).getDocument();
//                    writeDocumentToFile(resourceUpdateDocument, resource.getId() + "-update-" + resourceUpdate.getId());
                    resourceUpdate = resourceUpdateParser.parse(resourceUpdateDocument, resourceUpdate);

                    Document resourceUpdateLikesDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + resource.getId() + "/update-likes?resource_update_id=" + resourceUpdate.getId()).getDocument();
                    Elements likesElements = resourceUpdateLikesDocument.select("li.memberListItem");
                    resourceUpdate.setLikes(likesElements.size());

                    resource.getUpdates().add(resourceUpdate);
                    resource.setLikes(resource.getLikes() + resourceUpdate.getLikes());

                    resourceUpdate.setResource(resource.getId());

                    databaseClient.updateOrInsertUpdate(resource, resourceUpdate);
                }
            }
        } catch (Throwable throwable) {
            Sentry.captureException(throwable);
            log.error("Unexpected exception while parsing resource updates for #" + resource.getId(), throwable);
        }
    }

    private void updateResourceReviews(@NotNull Resource resource) {
        databaseClient.updateStatus("fetch.page.item.state", "reviews");
        ResourceReviewItemParser reviewItemParser = new ResourceReviewItemParser();
        try {
            int pageCount = Paginator.parseDocumentPageCount(SpigetClient.get(SpigetClient.BASE_URL + "resources/" + resource.getId() + "/reviews").getDocument());
            int maxPage = Math.min(pageCount, config.get("fetch.resources.reviews.maxPage").getAsInt());
            Paginator resourceReviewsPaginator = new Paginator(SpigetClient.BASE_URL + "resources/" + resource.getId() + "/reviews?page=%s", maxPage, false);
            for (Document reviewDocument : resourceReviewsPaginator) {
                Element resourceReviewsTab = reviewDocument.select("li.resourceTabReviews").first();
                if (resourceReviewsTab == null || !resourceReviewsTab.hasClass("active")) {
                    // We're not on the reviews page, which probably means the resource hasn't been reviewed yet.
                    break;
                }

                Elements reviewElements = reviewDocument.select("li.review");
                for (Element reviewElement : reviewElements) {
                    ResourceReview review = reviewItemParser.parse(reviewElement);

                    resource.getReviews().add(review);
                    review.setResource(resource.getId());

                    Author databaseReviewAuthor = databaseClient.getAuthor(review.getAuthor().getId());
                    if (databaseReviewAuthor == null) {// Only insert if the document doesn't exist, so we don't accidentally overwrite existing data
                        databaseClient.insertAuthor(review.getAuthor());
                    }

                    databaseClient.updateOrInsertReview(resource, review);
                }
            }
        } catch (Throwable throwable) {
            Sentry.captureException(throwable);
            log.error("Unexpected exception while parsing resource reviews for #" + resource.getId(), throwable);
        }
    }

    private void updateResourceDocumentation(@NotNull Resource resource) {
        databaseClient.updateStatus("fetch.page.item.state", "documentation");
        try {
            if (resource.getLinks().containsKey("documentation")) {
                Document documentationDocument = SpigetClient.get(SpigetClient.BASE_URL + resource.getLinks().get("documentation")).getDocument();
                Element mainContainer = documentationDocument.select("div.mainContainer").first();
                if (mainContainer != null) {
                    Element documentationText = mainContainer.select("blockquote.messageText").first();
                    if (documentationText != null) {
                        resource.setDocumentation(Base64.getEncoder().encodeToString(documentationText.html().getBytes()));
                    }
                }
            }
        } catch (Throwable throwable) {
            Sentry.captureException(throwable);
            log.error("Unexpected exception while parsing resource documentation for #" + resource.getId(), throwable);
        }
    }

    private void downloadResource(@NotNull Resource resource) throws InterruptedException {
        databaseClient.updateStatus("fetch.page.item.state", "download");
        log.info("Downloading #" + resource.getId());
        try {
            File outputFile = File.createTempFile("resource", resource.getId() + resource.getFile().getType());

            log.info("Downloading '" + resource.getFile().getUrl() + "' to '" + outputFile + "'...");
            SpigetDownload download = SpigetClient.download(SpigetClient.BASE_URL + resource.getFile().getUrl());
            if (download.isAvailable()) {
                InputStream inputStream = download.getInputStream();
                log.info("Available Size: " + inputStream.available());
                ReadableByteChannel channel = Channels.newChannel(inputStream);
                FileOutputStream out = new FileOutputStream(outputFile);
                out.getChannel().transferFrom(channel, 0, 10000000L/*10MB, should be enough*/);
                out.flush();
                out.close();

                if (b2Client != null) {
                    try {
                        log.info("Uploading to B2...");
                        B2FileVersion fileVersion = b2Client
                                .uploadSmallFile(B2UploadFileRequest
                                        .builder(config.get("b2.bucket").getAsString(), "" + resource.getId() + resource.getFile().getType(), B2ContentTypes.B2_AUTO, B2FileContentSource
                                                .build(outputFile))
                                        .build());
                        log.info(fileVersion);

                        outputFile.deleteOnExit();
                    } catch (Exception e) {
                        Sentry.captureException(e);
                        log.warn("Failed to upload " + outputFile + " to B2", e);
                    }
                }
                downloadedResources.add("" + resource.getId() + resource.getFile().getType());
            } else {
                log.warn("Download is not available (probably blocked by CloudFlare)");
            }
        } catch (IOException e) {
            Sentry.captureException(e);
            log.warn("Download for resource #" + resource.getId() + " failed", e);
        }
    }


}
