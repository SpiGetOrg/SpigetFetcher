package org.spiget.fetcher;

public enum FetchMode {

	/**
	 * Loads all resources from the specified amount of list-pages, loads the data from the source page and loads data from the author page
	 */
	FULL(true),
	/**
	 * Only loads resources from the specified amount of list-pages, without getting the full data for resources or authors
	 */
	LIST(false);

	private boolean fullResource;

	FetchMode(boolean fullResource) {
		this.fullResource = fullResource;
	}

	public boolean isFullResource() {
		return fullResource;
	}

}
