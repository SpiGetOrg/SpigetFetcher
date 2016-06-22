package org.spiget.fetcher.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.jsoup.nodes.Document;

import java.util.HashMap;
import java.util.Map;

@Data
@AllArgsConstructor
public class SpigetResponse {

	protected Map<String, String> cookies = new HashMap<>();
	protected Document document;

}
