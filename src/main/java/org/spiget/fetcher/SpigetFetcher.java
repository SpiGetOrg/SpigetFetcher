package org.spiget.fetcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.sentry.Sentry;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Log4j2
public class SpigetFetcher {

    public static JsonObject config;

    public static DatabaseClient databaseClient;

    WebhookExecutor webhookExecutor;

    public SpigetFetcher() {
    }

    @Nullable
    public SpigetFetcher init() {
        Sentry.init(options -> {
            options.setEnableExternalConfiguration(true);
        });

        try {
            Sentry.captureMessage("#init");
            try {
                throw new RuntimeException("Test Exception " + ThreadLocalRandom.current().nextInt());
            } catch (Exception e) {
                Sentry.captureException(e);
                log.log(Level.ERROR, "", e);
            }

            config = new JsonParser().parse(new FileReader("config.json")).getAsJsonObject();
            SpigetClient.config = config;
            SpigetClient.userAgent = config.get("request.userAgent").getAsString();
            PuppeteerClient.DIR_NAME = config.get("puppeteer.path").getAsString();
            PuppeteerClient.DIR = Paths.get(PuppeteerClient.DIR_NAME);
            //		JsonArray hostArray = config.getAsJsonArray("puppeteer.hosts");
            //		PuppeteerClient2.HOST = hostArray.get(ThreadLocalRandom.current().nextInt(hostArray.size())).getAsString();
            //		log.info("Using puppeteer host " + PuppeteerClient2.HOST);
            SpigetClient.loadCookiesFromFile();

            webhookExecutor = new WebhookExecutor();
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
                    Sentry.captureException(e);
                    log.fatal("Connection failed after " + (System.currentTimeMillis() - testStart) + "ms", e);
                    log.fatal("Aborting.");

                    Discord.postMessage("⚠️Database connection failed with exception!", config);

                    System.exit(-1);
                    return null;
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
            throw new RuntimeException(throwable);
        }
        return this;
    }

    public void fetch() {
        Sentry.captureMessage("#fetch");
        log.info("----- Fetcher started -----");
        long start = System.currentTimeMillis();
        try {
            databaseClient.updateStatus("fetch.start", start);
            long lastEnd = databaseClient.getStatus("fetch.end", 0L);
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

        int stopOnExisting = config.get("fetch.resources.stopOnExisting").getAsInt();
        int existingCount = 0;
        boolean fetchStopped = false;

        int pageAmount = config.get("fetch.resources.pages").getAsInt();
        int pageOffset = config.get("fetch.resources.pageOffset").getAsInt();
        boolean inverted = config.get("fetch.resources.inverted").getAsBoolean();
        databaseClient.updateStatus("fetch.page.amount", pageAmount);
        int pageCounter = 0;
        Paginator resourceListPaginator = new Paginator(SpigetClient.BASE_URL + "resources/?page=%s", pageAmount, inverted);
        if (!config.get("fetch.requestsOnly").getAsBoolean()) {
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
                    if (resourceListItems.isEmpty()) {
                        log.warn("Page has " + resourceListItems.size() + " resource items");
                        log.info(document);
                        Discord.postMessage("⚠Resource page has no resource items!", config);
                    } else {
                        log.debug("Page has " + resourceListItems.size() + " resource items");
                    }
                    int itemCounter = 0;
                    for (Element resourceListItem : resourceListItems) {
                        if (fetchStopped) { break; }
                        itemCounter++;
                        databaseClient.updateStatus("fetch.page.item.index", itemCounter);
                        databaseClient.updateStatus("fetch.page.item.state", "list");
                        try {
                            ListedResource listedResource = resourceItemParser.parse(resourceListItem);

                            if (listedResource != null) {
                                if (modeResources) {
                                    // Update the resource
                                    listedResource = updateResource(listedResource, resourcePageParser);
                                    listedResource = updateResourceExtras((Resource) listedResource, modeResourceVersions, modeResourceUpdates, modeResourceReviews, true);

                                    final int resId = listedResource.getId();
                                    databaseClient.deleteUpdateRequest(new UpdateRequest() {{
                                        this.setRequestedId(resId);
                                    }});
                                }

                                databaseClient.updateStatus("fetch.page.item.state", "database");

                                ListedResource databaseResource = databaseClient.getResource(listedResource.getId());
                                if (databaseResource != null) {
                                    log.info("Updating existing resource #" + listedResource.getId());
                                    databaseClient.updateResource(listedResource);

                                    if (databaseResource.getUpdateDate() != listedResource.getUpdateDate()) {// There was actually an update
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
            log.log(Level.INFO, "Running update request fetch");
            int maxResourceRequest = config.get("resourceRequest.max").getAsInt();
            Set<UpdateRequest> updateRequests = databaseClient.getUpdateRequests(maxResourceRequest);
            if (updateRequests != null && !updateRequests.isEmpty()) {
                int updateCount = updateRequests.size();
                long updateStart = System.currentTimeMillis();
                log.log(Level.INFO, "Fetching (" + updateCount + ") resources with requested update...");
                ResourcePageParser resourcePageParser = new ResourcePageParser();
                int c = 0;
                for (UpdateRequest request : updateRequests) {
                    if (c++ > maxResourceRequest) {
                        log.info("Max Resource Requests processed. Stopping.");
                        break;
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
                        resource = updateResource(resource, resourcePageParser);
                        if (resource == null) {
                            if (request.isDelete()) {
                                log.log(Level.INFO, "Deleting resource #" + request.getRequestedId() + " since it has likely been deleted.");
                                databaseClient.deleteResource(request.getRequestedId());
                            }
                            databaseClient.deleteUpdateRequest(request);
                            continue;
                        }
                        updateResourceExtras(resource, request.isVersions(), request.isUpdates(), request.isReviews(), false);

                        if (existed) {
                            log.info("Updating existing resource #" + resource.getId());
                        } else {
                            log.log(Level.INFO, "Handling resource update request for a resource that wasn't in the database already (" + request.getRequestedId() + ")");
                        }

                        databaseClient.updateResource(resource);

                        databaseClient.deleteUpdateRequest(request);
                    } catch (Throwable throwable) {
                        Sentry.captureException(throwable);
                        log.error("Unexpected exception while updating resource #" + request.getRequestedId(), throwable);
                    }
                }
                log.log(Level.INFO, "Finished requested updates. Took " + (((double) System.currentTimeMillis() - updateStart) / 1000 / 60) + " minutes to update " + updateCount + " resources.");
            }
        } catch (Throwable throwable) {
            Sentry.captureException(throwable);
            log.log(Level.ERROR, "Update Request exception", throwable);
        }

        try {
            long end = System.currentTimeMillis();
            databaseClient.updateStatus("fetch.end", end);
            databaseClient.updateStatus("fetch.duration", (end - start));
        } catch (Exception e) {
            Sentry.captureException(e);
            log.log(Level.ERROR, "Failed to update status", e);
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
    }

    private boolean checkIfResourceExists(int id) {
        try {
            JsonResponse response = JsonClient.get("https://api.spigotmc.org/simple/0.1/index.php?action=getResource&id=" + id);
            if (response != null) {
                if (response.code == 404) { return false; }
                if (response.code == 200) { return true; }
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
                log.error("Failed to update resource #" + listedResource.getId() + ": page returned non-OK status code (" + response.getCode() + ")");
                return null;
            }
            Document resourceDocument = response.getDocument();
            return resourcePageParser.parse(resourceDocument, listedResource);
        } catch (Throwable throwable) {
            Sentry.captureException(throwable);
            log.error("Unexpected exception while parsing full resource #" + listedResource.getId(), throwable);
            return null;
        }
    }

    private Resource updateResourceExtras(@NotNull Resource resource, boolean modeResourceVersions, boolean modeResourceUpdates, boolean modeResourceReviews, boolean modeResourceDownload) throws InterruptedException {
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
        if (modeResourceDownload && !resource.isExternal() && !resource.isPremium()) {
            if (SpigetFetcher.config.get("fetch.resources.download").getAsBoolean()) {
                downloadResource(resource);
            }
        }
        return resource;
    }

    private void updateResourceVersions(@NotNull Resource resource) {
        databaseClient.updateStatus("fetch.page.item.state", "versions");
        ResourceVersionItemParser resourceVersionItemParser = resource.isPremium() ? new PremiumResourceVersionItemParser() : new ResourceVersionItemParser();
        try {
            Document versionDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + resource.getId() + "/history").getDocument();
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
                    log.log(Level.ERROR, "Failed to make UUID for version, Resource: " + resource.getId() + ", Version: " + resourceVersion.getName(), e);
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

                Elements resourceUpdateElements = updateDocument.select("li.resourceUpdate");
                for (Element resourceUpdateElement : resourceUpdateElements) {
                    ResourceUpdate resourceUpdate = resourceUpdateItemParer.parse(resourceUpdateElement);
                    Document resourceUpdateDocument = SpigetClient.get(SpigetClient.BASE_URL + "resources/" + resource.getId() + "/update?update=" + resourceUpdate.getId()).getDocument();
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

    private void downloadResource(@NotNull Resource resource) throws InterruptedException {
        String basePath = SpigetFetcher.config.get("fetch.resources.downloadBase").getAsString();
        if (basePath != null && !basePath.isEmpty()) {
            databaseClient.updateStatus("fetch.page.item.state", "download");
            log.info("Downloading #" + resource.getId());
            try {
                File outputFile = makeDownloadFile(basePath, String.valueOf(resource.getId()), resource.getFile().getType());
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

                log.info("Downloading '" + resource.getFile().getUrl() + "' to '" + outputFile + "'...");
                SpigetDownload download = SpigetClient.download(SpigetClient.BASE_URL + resource.getFile().getUrl());
                if (download.isAvailable()) {
                    ReadableByteChannel channel = Channels.newChannel(download.getInputStream());
                    FileOutputStream out = new FileOutputStream(outputFile);
                    out.getChannel().transferFrom(channel, 0, 10000000L/*10MB, should be enough*/);
                    out.flush();
                    out.close();
                } else {
                    log.warn("Download is not available (probably blocked by CloudFlare)");
                }
            } catch (IOException e) {
                Sentry.captureException(e);
                log.warn("Download for resource #" + resource.getId() + " failed", e);
            }
        }
    }

    @NotNull
    private File makeDownloadFile(String baseDir, @NotNull String resource, String type) {
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
