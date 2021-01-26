package org.spiget.fetcher;

import com.google.gson.JsonObject;
import org.inventivetalent.metrics.Metrics;

public class SpigetMetrics {

    protected Metrics metrics;

    public SpigetMetrics(JsonObject config) {
        this.metrics = new Metrics(config.get("metrics.url").getAsString(), config.get("metrics.user").getAsString(), config.get("metrics.password").getAsString());
        this.metrics.getInflux().setDatabase("spiget");
    }

}
