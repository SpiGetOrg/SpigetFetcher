package org.spiget.fetcher.parser;

import org.jsoup.nodes.Document;
import org.spiget.fetcher.request.SpigetClient;
import org.spiget.fetcher.request.SpigetResponse;

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
			this.currentPage = 0;
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
				return currentPage > 0;
			} else {
				return currentPage < maxPage;
			}
		}

		@Override
		public Document next() {
			return getPage(inverted ? currentPage-- : currentPage++);
		}
	}

}
