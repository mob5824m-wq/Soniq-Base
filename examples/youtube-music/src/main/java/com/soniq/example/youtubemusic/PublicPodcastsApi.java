package com.soniq.example.youtubemusic;

import android.util.Xml;

import com.soniq.base.model.MediaItem;
import com.soniq.base.model.StreamSource;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Public podcasts with no account, no API key and no sign-in.
 *
 * <p>Three public pieces make up the whole catalogue:
 *
 * <ul>
 *   <li><b>Apple's iTunes Search API</b> ({@code itunes.apple.com/search}) finds
 *       shows by keyword and returns each show's public RSS {@code feedUrl}.</li>
 *   <li><b>Each show's own RSS feed</b> lists its episodes. Nothing is proxied
 *       or re-hosted: the feed is whatever the publisher publishes.</li>
 *   <li><b>The episode's {@code <enclosure>} URL</b> is the audio file, so an
 *       episode plays by streaming straight from the publisher's host.</li>
 * </ul>
 *
 * <p>Only public endpoints are used. Nothing here signs in, scrapes a page or
 * works around a paywall, and every method fails with an
 * {@link IOException} carrying a message a listener can read.
 */
public final class PublicPodcastsApi {

    /** iTunes Search: {@code ?media=podcast&entity=podcast&term=…}. */
    public static final String SEARCH_ENDPOINT = "https://itunes.apple.com/search";
    /** iTunes Lookup, used to turn chart ids into feed addresses. */
    public static final String LOOKUP_ENDPOINT = "https://itunes.apple.com/lookup";
    /** Apple's public "top podcasts" chart (no key, no account). */
    public static final String TOP_CHART_ENDPOINT =
            "https://rss.applemarketingtools.com/api/v2/us/podcasts/top/25/podcasts.json";

    /** Used when the listener opens the Podcasts chip with an empty query. */
    public static final String DISCOVER_TERM = "podcasts";

    private static final String DISPLAY_SOURCE = "Apple Podcasts";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) SoniqBaseExample/1.0";
    private static final int CONNECT_TIMEOUT_MS = 14000;
    private static final int READ_TIMEOUT_MS = 25000;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FEED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_EPISODES = 100;
    private static final int MAX_REMEMBERED_TYPES = 200;

    private static final String[] DATE_PATTERNS = {
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
    };

    /**
     * Enclosure MIME types seen while parsing feeds, kept so {@link #resolve}
     * can report the real type instead of guessing from the file extension.
     * Access-ordered and bounded: an LRU of the most recently parsed episodes.
     */
    private final Map<String, String> mimeByEpisode =
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_REMEMBERED_TYPES;
                }
            };

    /** The podcast catalogue's display name, e.g. for the Podcasts tab subtitle. */
    public String displaySource() {
        return DISPLAY_SOURCE;
    }

    /* ------------------------------------------------------------- discovery */

    /** Shows matching a keyword, newest iTunes ranking first. */
    public List<MediaItem> searchShows(String term, int limit) throws IOException {
        String query = term == null ? "" : term.trim();
        if (query.length() == 0) {
            query = DISCOVER_TERM;
        }
        int cap = Math.max(1, Math.min(limit, 50));
        String url = SEARCH_ENDPOINT
                + "?media=podcast&entity=podcast&limit=" + cap
                + "&term=" + URLEncoder.encode(query, "UTF-8");
        JSONObject root = json(getBytes(url, MAX_JSON_BYTES));
        return showsFrom(root.optJSONArray("results"));
    }

    /**
     * Apple's public top-podcasts chart, resolved to feed addresses so every
     * show can be opened. Falls back to a keyword search if the chart is
     * unavailable, and only throws when both attempts fail.
     */
    public List<MediaItem> featuredShows() throws IOException {
        IOException chartFailure = null;
        try {
            List<MediaItem> chart = chartShows();
            if (!chart.isEmpty()) {
                return chart;
            }
        } catch (IOException error) {
            chartFailure = error;
        }
        try {
            return searchShows(DISCOVER_TERM, 25);
        } catch (IOException error) {
            // Report the search failure: it is the path the listener can retry.
            if (chartFailure != null) {
                error.addSuppressed(chartFailure);
            }
            throw error;
        }
    }

    private List<MediaItem> chartShows() throws IOException {
        JSONObject root = json(getBytes(TOP_CHART_ENDPOINT, MAX_JSON_BYTES));
        JSONObject feed = root.optJSONObject("feed");
        JSONArray entries = feed == null ? null : feed.optJSONArray("results");
        if (entries == null || entries.length() == 0) {
            throw new IOException("The podcast chart was empty.");
        }
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            String id = entry == null ? "" : entry.optString("id", "").trim();
            if (id.length() == 0 || !isDigits(id)) {
                continue;
            }
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id);
        }
        if (ids.length() == 0) {
            throw new IOException("The podcast chart had no show ids.");
        }
        // One lookup for the whole chart returns each show's feedUrl.
        String url = LOOKUP_ENDPOINT + "?entity=podcast&id=" + ids;
        return showsFrom(json(getBytes(url, MAX_JSON_BYTES)).optJSONArray("results"));
    }

    /** Turns iTunes show records into browsable {@link MediaItem#KIND_PODCAST} items. */
    private List<MediaItem> showsFrom(JSONArray results) {
        List<MediaItem> shows = new ArrayList<>();
        if (results == null) {
            return shows;
        }
        for (int i = 0; i < results.length(); i++) {
            JSONObject record = results.optJSONObject(i);
            if (record == null) {
                continue;
            }
            String feedUrl = record.optString("feedUrl", "").trim();
            String title = firstOf(record, "collectionName", "trackName");
            if (feedUrl.length() == 0 || !isHttp(feedUrl) || title.length() == 0) {
                // No public feed means the show cannot be opened, so it is not
                // offered: an entry that cannot be browsed is worse than none.
                continue;
            }
            MediaItem show = new MediaItem();
            show.id = feedUrl;
            show.kind = MediaItem.KIND_PODCAST;
            show.title = title;
            show.author = firstOf(record, "artistName", "collectionCensoredName");
            show.thumbnailUrl = firstOf(record, "artworkUrl600", "artworkUrl100", "artworkUrl60");
            shows.add(show);
        }
        return shows;
    }

    /* -------------------------------------------------------------- episodes */

    /** The episodes in a show's public RSS feed, in the order the feed lists them. */
    public List<MediaItem> episodes(MediaItem show) throws IOException {
        if (show == null) {
            throw new IOException("Nothing to browse.");
        }
        String feedUrl = show.id == null ? "" : show.id.trim();
        if (!isHttp(feedUrl)) {
            throw new IOException("This show has no public feed address.");
        }
        if (feedUrl.startsWith("http://")) {
            throw new IOException("This show's feed is served over plain HTTP, "
                    + "which this app does not allow.");
        }
        return parseFeed(getBytes(feedUrl, MAX_FEED_BYTES), show);
    }

    /** Streams an episode's enclosure URL directly; nothing is proxied. */
    public StreamSource resolve(MediaItem episode) throws IOException {
        if (episode == null || episode.id == null || episode.id.length() == 0) {
            throw new IOException("This episode has no audio file.");
        }
        String url = episode.id.trim();
        if (!isHttp(url)) {
            throw new IOException("This episode has no audio file link.");
        }
        if (url.startsWith("http://")) {
            // The example ships with usesCleartextTraffic="false", so an
            // http:// enclosure would fail inside the player with an opaque
            // error. Say what is actually wrong instead.
            throw new IOException("This episode's audio is served over plain HTTP, "
                    + "which this app does not allow.");
        }
        StreamSource source = new StreamSource();
        source.url = url;
        String mime = mimeByEpisode.get(url);
        if (mime == null || mime.length() == 0) {
            mime = guessMime(url);
        }
        source.mimeType = mime;
        String lower = url.toLowerCase(Locale.US);
        source.hls = mime.toLowerCase(Locale.US).contains("mpegurl")
                || lower.contains(".m3u8");
        source.sourceLabel = "Podcast feed";
        return source;
    }

    /* ----------------------------------------------------------- RSS parsing */

    /** One {@code <item>} while it is being parsed. */
    private static final class Draft {
        String title = "";
        String author = "";
        String guid = "";
        String date = "";
        String thumb = "";
        String enclosure = "";
        String enclosureType = "";
        long seconds = -1L;
    }

    private List<MediaItem> parseFeed(byte[] body, MediaItem show) throws IOException {
        List<MediaItem> episodes = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            // Handing the parser the bytes lets it honour the XML declaration's
            // encoding, which many feeds still set to ISO-8859-1.
            parser.setInput(new ByteArrayInputStream(body), null);
            String channelTitle = "";
            String channelAuthor = "";
            String channelImage = "";
            Draft draft = null;
            String field = null;
            int depth = 0;
            int itemDepth = -1;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    depth++;
                    String name = local(parser.getName());
                    if ("item".equals(name) && draft == null && episodes.size() < MAX_EPISODES) {
                        draft = new Draft();
                        itemDepth = depth;
                        field = null;
                        event = parser.next();
                        continue;
                    }
                    if ("enclosure".equals(name) && draft != null) {
                        String url = attribute(parser, "url");
                        if (url.length() > 0 && draft.enclosure.length() == 0) {
                            draft.enclosure = url;
                        }
                        String type = attribute(parser, "type");
                        if (type.length() > 0) {
                            draft.enclosureType = type;
                        }
                        field = null;
                        event = parser.next();
                        continue;
                    }
                    if ("image".equals(name)) {
                        // Either <itunes:image href="…"/> or <image><url>…</url></image>.
                        String href = attribute(parser, "href");
                        if (href.length() > 0) {
                            if (draft == null) {
                                if (channelImage.length() == 0) {
                                    channelImage = href;
                                }
                            } else if (draft.thumb.length() == 0) {
                                draft.thumb = href;
                            }
                        }
                        field = null;
                        event = parser.next();
                        continue;
                    }
                    field = name;
                } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                    if (field != null) {
                        String text = parser.getText();
                        if (text != null && text.trim().length() > 0) {
                            if (draft == null) {
                                if ("title".equals(field) && channelTitle.length() == 0) {
                                    channelTitle = text.trim();
                                } else if ("author".equals(field) && channelAuthor.length() == 0) {
                                    channelAuthor = text.trim();
                                } else if ("url".equals(field) && channelImage.length() == 0) {
                                    channelImage = text.trim();
                                }
                            } else {
                                applyField(draft, field, text.trim());
                            }
                        }
                        field = null;
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if (draft != null && depth == itemDepth
                            && "item".equals(local(parser.getName()))) {
                        MediaItem episode = toEpisode(draft, show, channelTitle, channelImage);
                        if (episode != null) {
                            episodes.add(episode);
                        }
                        draft = null;
                        itemDepth = -1;
                    }
                    field = null;
                    depth--;
                }
                event = parser.next();
            }
            if (episodes.isEmpty() && channelTitle.length() == 0 && channelAuthor.length() == 0) {
                throw new IOException("This feed has no episodes this app can play.");
            }
            return episodes;
        } catch (org.xmlpull.v1.XmlPullParserException error) {
            throw new IOException("This show's feed is not readable XML.", error);
        } catch (RuntimeException error) {
            throw new IOException("This show's feed could not be read.", error);
        }
    }

    /** Stores one leaf element of an {@code <item>}; the first value of each wins. */
    private static void applyField(Draft draft, String field, String text) {
        if ("title".equals(field)) {
            if (draft.title.length() == 0) {
                draft.title = text;
            }
        } else if ("guid".equals(field) || "id".equals(field)) {
            if (draft.guid.length() == 0) {
                draft.guid = text;
            }
        } else if ("pubDate".equals(field) || "published".equals(field)
                || "date".equals(field)) {
            if (draft.date.length() == 0) {
                draft.date = text;
            }
        } else if ("duration".equals(field)) {
            if (draft.seconds < 0L) {
                draft.seconds = parseDuration(text);
            }
        } else if ("author".equals(field)) {
            if (draft.author.length() == 0) {
                draft.author = text;
            }
        } else if ("url".equals(field)) {
            // <image><url>…</url></image> inside an item.
            if (draft.thumb.length() == 0) {
                draft.thumb = text;
            }
        }
    }

    private MediaItem toEpisode(Draft draft, MediaItem show, String channelTitle,
                                String channelImage) {
        // The enclosure URL is both the audio and the identity of the episode:
        // it survives being saved to the library and resumes correctly later.
        String id = draft.enclosure;
        if (id.length() == 0 && isHttp(draft.guid)) {
            id = draft.guid;
        }
        if (id.length() == 0) {
            // No audio file: an unplayable row would only be a dead end.
            return null;
        }
        MediaItem episode = new MediaItem();
        episode.id = id;
        episode.kind = MediaItem.KIND_EPISODE;
        episode.title = draft.title.length() > 0 ? draft.title : "(untitled episode)";
        String showTitle = show.title == null ? "" : show.title;
        episode.author = showTitle.length() > 0 ? showTitle
                : (channelTitle.length() > 0 ? channelTitle : draft.author);
        episode.date = formatDate(draft.date);
        if (draft.seconds > 0L) {
            episode.setDurationSec(draft.seconds);
        }
        episode.thumbnailUrl = draft.thumb.length() > 0 ? draft.thumb
                : (show.thumbnailUrl != null && show.thumbnailUrl.length() > 0
                ? show.thumbnailUrl : channelImage);
        if (draft.enclosureType.length() > 0) {
            mimeByEpisode.put(id, draft.enclosureType);
        }
        return episode;
    }

    /* --------------------------------------------------------------- helpers */

    private JSONObject json(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            throw new IOException("The podcast directory returned an empty response.");
        }
        try {
            return new JSONObject(new String(body, "UTF-8"));
        } catch (Exception error) {
            throw new IOException("The podcast directory returned unexpected data.", error);
        }
    }

    private static String local(String name) {
        if (name == null) {
            return "";
        }
        int colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String attribute(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        if (value == null && name.length() > 0) {
            // Namespace-aware parsers report prefixed attributes only with
            // their namespace, so fall back to a manual scan.
            for (int i = 0; i < parser.getAttributeCount(); i++) {
                String candidate = local(parser.getAttributeName(i));
                if (name.equalsIgnoreCase(candidate)) {
                    String found = parser.getAttributeValue(i);
                    return found == null ? "" : found.trim();
                }
            }
            return "";
        }
        return value == null ? "" : value.trim();
    }

    private static String firstOf(JSONObject record, String... keys) {
        for (String key : keys) {
            String value = record.optString(key, "").trim();
            if (value.length() > 0) {
                return value;
            }
        }
        return "";
    }

    /** {@code "3600"}, {@code "42:05"} or {@code "1:02:03"} to seconds, or -1. */
    private static long parseDuration(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) {
            return -1L;
        }
        long total = 0L;
        int parts = 0;
        String[] chunks = value.split(":");
        for (String chunk : chunks) {
            String digits = chunk.trim();
            if (digits.length() == 0) {
                return -1L;
            }
            try {
                total = total * 60L + Long.parseLong(digits);
            } catch (NumberFormatException error) {
                return -1L;
            }
            parts++;
        }
        if (parts == 0) {
            return -1L;
        }
        if (parts == 1 && total > 86400L) {
            // A single huge number is milliseconds; real feeds do both.
            total = total / 1000L;
        }
        return total > 0L ? total : -1L;
    }

    /** A feed date in any common form to {@code "Nov 24, 2026"}, or "". */
    private static String formatDate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) {
            return "";
        }
        for (String pattern : DATE_PATTERNS) {
            SimpleDateFormat input = new SimpleDateFormat(pattern, Locale.US);
            input.setLenient(true);
            ParsePosition position = new ParsePosition(0);
            Date parsed = input.parse(value, position);
            if (parsed != null && position.getIndex() >= value.length() - 1) {
                return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(parsed);
            }
        }
        return "";
    }

    private static String guessMime(String url) {
        String lower = url.toLowerCase(Locale.US);
        int cut = lower.indexOf('?');
        if (cut >= 0) {
            lower = lower.substring(0, cut);
        }
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".m4b")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".aac")) {
            return "audio/aac";
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".oga")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        return "";
    }

    private static boolean isHttp(String url) {
        if (url == null) {
            return false;
        }
        String value = url.trim().toLowerCase(Locale.US);
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static boolean isDigits(String value) {
        if (value == null || value.length() == 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /* ------------------------------------------------------------------ HTTP */

    private byte[] getBytes(String url, int maxBytes) throws IOException {
        if (url == null || url.length() == 0) {
            throw new IOException("Empty request URL");
        }
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept",
                    "application/json, application/rss+xml, application/xml, text/xml, */*");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            int code = connection.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            input = ok ? connection.getInputStream() : connection.getErrorStream();
            byte[] body = readFully(input, Math.max(1024, maxBytes));
            if (!ok) {
                throw new IOException("HTTP " + code + " from " + hostOf(url));
            }
            return body;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readFully(InputStream input, int maxBytes) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int read;
        while ((read = input.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() >= maxBytes) {
                break;
            }
        }
        return buffer.toByteArray();
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception ignored) {
            return url;
        }
    }
}
