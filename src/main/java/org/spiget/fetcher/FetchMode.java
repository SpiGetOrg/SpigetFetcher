package org.spiget.fetcher;

public enum FetchMode {

	/**
	 * Loads all resources from the specified amount of list-pages, loads the data from the source page and loads data from the author page
	 */
	FULL(true, true),
	/**
	 * Only loads resources from the specified amount of list-pages, without getting the full data for resources or authors
	 */
	LIST(false, false),
	/**
	 * Loads resources from the specified amount of list-pages, loads data from the resource but doesn't load full author data
	 */
	NO_AUTHOR(true, false);

	private boolean fullResource;
	private boolean fullAuthor;

	FetchMode(boolean fullResource, boolean fullAuthor) {
		this.fullResource = fullResource;
		this.fullAuthor = fullAuthor;
	}

	public boolean isFullResource() {
		return fullResource;
	}

	public boolean isFullAuthor() {
		return fullAuthor;
	}
}
