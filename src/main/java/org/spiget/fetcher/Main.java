package org.spiget.fetcher;

public class Main {

	public static void main(String... args) throws Exception {
		SpigetFetcher spigetFetcher = new SpigetFetcher();
		spigetFetcher.init();
		spigetFetcher.fetch();
	}

}
