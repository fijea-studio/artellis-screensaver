package io.github.fijeastudio.artellis;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Plain HTTPS GETs. Only the museum hosts are ever asked; nothing about the TV is sent beyond the request. */
final class Net {

    private static final String AGENT = "Mozilla/5.0 (Linux; Android TV) Artellis/1.0";
    /** The Art Institute asks callers to name themselves; no contact details are sent. */
    private static final String AIC_AGENT = "Artellis screensaver (github.com/fijea-studio)";
    private static final int TIMEOUT_MS = 20_000;

    private Net() {}

    static String text(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            check(c);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                copy(in, out, 8L * 1024 * 1024);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /** Downloads to target, refusing anything over maxBytes (a 100 MB museum scan, say). */
    static void file(String url, File target, long maxBytes) throws IOException {
        HttpURLConnection c = open(url);
        try {
            check(c);
            long length = c.getContentLengthLong();
            if (length > maxBytes) throw new IOException("too large: " + length);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                copy(in, out, maxBytes);
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", AGENT);
        if (url.contains("artic.edu")) c.setRequestProperty("AIC-User-Agent", AIC_AGENT);
        c.setRequestProperty("Accept", "*/*");
        return c;
    }

    private static void check(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " " + c.getURL().getHost());
    }

    private static void copy(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > max) throw new IOException("too large");
            out.write(buffer, 0, n);
        }
    }
}
