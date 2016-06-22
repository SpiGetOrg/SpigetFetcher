package org.spiget.fetcher.request;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.InputStream;

@Data
@AllArgsConstructor
public class SpigetDownload {

	private String      url;
	private InputStream inputStream;

}
