/*
 * Copyright (c) 2018 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.release;


import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;

/**
 * Checks for new releases
 */
public final class ReleaseInfo {
    private static final String RELEASE_URL = "https://api.github.com/repos/pituluk/Digital/releases/latest";
    private static final String COMPARE_URL = "https://api.github.com/repos/pituluk/Digital/compare/";

    private String version;
    private String url;

    /**
     * Creates a new instance
     *
     * @throws IOException IOException
     */
    ReleaseInfo() throws IOException {
        readReleaseInfo(RELEASE_URL);
    }

    private void readReleaseInfo(String releaseUrl) throws IOException {
        JSONObject obj = readObject(releaseUrl);
        version = obj.get("tag_name").toString();
        url = obj.get("html_url").toString();
    }

    /**
     * @return the actual version
     */
    public String getVersion() {
        return version;
    }

    /**
     * @return the url of the release page
     */
    public String getUrl() {
        return url;
    }

    /**
     * Compares a running build commit with the latest release commit using GitHub.
     *
     * @param runningCommit the full commit id of the running build
     * @return GitHub comparison status
     * @throws IOException if the comparison cannot be read
     */
    public String compareRunningCommit(String runningCommit) throws IOException {
        String encodedVersion = URLEncoder.encode(version, "UTF-8");
        String compareUrl = COMPARE_URL + encodedVersion + "..." + runningCommit;
        try {
            JSONObject obj = readObject(compareUrl);
            return obj.get("status").toString();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private JSONObject readObject(String requestUrl) throws IOException {
        URLConnection connection = new URL(requestUrl).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Digital-release-check");
        try (InputStream in = connection.getInputStream()) {
            return new JSONObject(new JSONTokener(in));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

}
