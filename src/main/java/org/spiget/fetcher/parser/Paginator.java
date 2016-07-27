package org.spiget.fetcher.parser;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.spiget.client.SpigetClient;
import org.spiget.client.SpigetResponse;

import java.io.IOException;
import java.util.Iterator;

public class Paginator implements Iterable<Document> {

	private final String pageFormat;
	private       int    maxPage;
	boolean inverted;

	int currentPage;

	public Paginator(String pageFormat) {
		this.pageFormat = pageFormat;
	}

	public Paginator(String pageFormat, int maxPage, boolean inverted) {
		this(pageFormat);
		this.maxPage = maxPage;
		this.inverted = inverted;

		if (this.inverted) {
			this.currentPage = this.maxPage;
		} else {
			this.currentPage = 1;
		}
	}

	protected SpigetResponse doRequest(String url) throws IOException, InterruptedException {
		return SpigetClient.get(url);
	}

	public Document getPage(int page) {
		try {
			SpigetResponse response = doRequest(String.format(this.pageFormat, page));
			return response.getDocument();
		} catch (IOException | InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public PageIterator iterator() {
		return new PageIterator();
	}

	public class PageIterator implements Iterator<Document> {

		@Override
		public boolean hasNext() {
			if (inverted) {
				return currentPage >= 1;
			} else {
				return currentPage <= maxPage;
			}
		}

		@Override
		public Document next() {
			return getPage(inverted ? currentPage-- : currentPage++);
		}
	}

	public static int parseNavPageCount(Element pageNav) {
		if (pageNav == null) { return 1; }
		return Integer.parseInt(pageNav.attr("data-last"));
	}

	public static int parseDocumentPageCount(Document document) {
		return parseNavPageCount(document.select("div.PageNav").first());
	}

}
