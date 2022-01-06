package org.spiget.fetcher;

public class Main {

    public static void main(String... args) throws Exception {
        System.out.println("Hello World!");
        SpigetFetcher spigetFetcher = new SpigetFetcher();
        spigetFetcher.init();
        spigetFetcher.fetch();
        //		spigetFetcher.patchVersions();
    }

}
