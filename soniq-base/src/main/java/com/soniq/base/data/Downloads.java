package com.soniq.base.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.soniq.base.SoniqApp;
import com.soniq.base.model.MediaItem;
import com.soniq.base.model.StreamSource;
import com.soniq.base.provider.MusicProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Offline audio downloads.
 *
 * <p>On Android 10+ the file is written into MediaStore under
 * {@code Music/Soniq Music}, so it appears in the system music apps. On Android
 * 8-9 it goes to the app's external music directory (no storage permission).
 * An index in SharedPreferences maps a track identity to its local URI.
 */
public final class Downloads {

    public interface Callback {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
    }

    private static final String PREFS = "soniq_downloads";
    private static final String KEY_INDEX = "index";
    /** MediaStore folder, e.g. {@code Music/<app label>}; apps can override it. */

    private final Context context;
    private final MusicProvider provider;
    private final SharedPreferences prefs;
    private final String mediaRelativePath;

    /** Stores downloads under {@code Music/<application label>}. */
    public Downloads(Context context) {
        this(context, SoniqApp.providerOf(context), defaultRelativePath(context));
    }

    public Downloads(Context context, MusicProvider provider, String mediaRelativePath) {
        this.context = context.getApplicationContext();
        this.provider = provider;
        this.mediaRelativePath = mediaRelativePath == null || mediaRelativePath.trim().length() == 0
                ? "Music/Soniq Base" : mediaRelativePath.trim();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String defaultRelativePath(Context context) {
        try {
            CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
            String name = sanitize(label == null ? "" : label.toString());
            if (name.length() > 0) {
                return "Music/" + name;
            }
        } catch (RuntimeException ignored) {
        }
        return "Music/Soniq Base";
    }

    public List<MediaItem> all() {
        List<MediaItem> out = new ArrayList<>();
        List<MediaItem> stored = readIndex();
        boolean changed = false;
        for (MediaItem item : stored) {
            if (item.localUri.length() == 0) {
                changed = true;
                continue;
            }
            if (exists(item.localUri)) {
                out.add(item);
            } else {
                changed = true;
            }
        }
        if (changed) {
            writeIndex(out);
        }
        return out;
    }

    public boolean isDownloaded(MediaItem item) {
        return item != null && find(item.identity()) != null;
    }

    public MediaItem find(String identity) {
        if (identity == null || identity.length() == 0) {
            return null;
        }
        for (MediaItem item : readIndex()) {
            if (identity.equals(item.identity()) && item.localUri.length() > 0
                    && exists(item.localUri)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Resolves, downloads and registers a track. Blocking; call from a worker
     * thread. Returns a copy of the item with {@link MediaItem#localUri} set.
     */
    public MediaItem downloadSync(MediaItem item, Callback callback) throws IOException {
        if (item == null || item.id.length() == 0) {
            throw new IOException("Play an online song before downloading it.");
        }
        MediaItem existing = find(item.identity());
        if (existing != null) {
            return existing;
        }
        StreamSource track = provider.resolve(item);
        if (track == null || !track.hasStream()) {
            throw new IOException("No downloadable audio stream was returned.");
        }
        if (track.hls) {
            throw new IOException("This stream is HLS-only and cannot be saved offline.");
        }

        File partial = new File(context.getCacheDir(), "download-" + UUID.randomUUID() + ".part");
        try {
            downloadTo(track, partial, callback);
            String extension = track.mimeType.startsWith("audio/webm") ? "webm" : "m4a";
            String displayName = sanitize((track.author.length() > 0
                    ? track.author + " - " : track.title.length() > 0
                    ? track.title : item.title)) + "." + extension;
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    uri = insertIntoMediaStore(displayName, track.mimeType, partial);
                } catch (IOException | RuntimeException mediaStoreFailure) {
                    uri = copyToAppMusic(displayName, partial);
                }
            } else {
                uri = copyToAppMusic(displayName, partial);
            }

            MediaItem saved = item.copy();
            saved.title = track.title.length() > 0 ? track.title : item.title;
            saved.author = track.author.length() > 0 ? track.author : item.author;
            saved.thumbnailUrl = track.artworkUrl.length() > 0
                    ? track.artworkUrl : item.thumbnailUrl;
            saved.durationSec = track.durationSec > 0 ? track.durationSec : item.durationSec;
            saved.localUri = uri.toString();

            List<MediaItem> index = readIndex();
            index.add(0, saved);
            writeIndex(index);
            return saved;
        } finally {
            if (partial.exists() && !partial.delete()) {
                partial.deleteOnExit();
            }
        }
    }

    public boolean delete(MediaItem item) {
        if (item == null) {
            return false;
        }
        List<MediaItem> index = readIndex();
        boolean removed = false;
        for (int i = 0; i < index.size(); i++) {
            if (item.identity().equals(index.get(i).identity())) {
                MediaItem stored = index.remove(i);
                removed = true;
                deleteStoredFile(stored);
                break;
            }
        }
        if (removed) {
            writeIndex(index);
        }
        return removed;
    }

    private void deleteStoredFile(MediaItem item) {
        if (item == null || item.localUri.length() == 0) {
            return;
        }
        try {
            Uri uri = Uri.parse(item.localUri);
            if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                context.getContentResolver().delete(uri, null, null);
            } else if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                if (file.exists() && !file.delete()) {
                    file.deleteOnExit();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void downloadTo(StreamSource track, File target, Callback callback)
            throws IOException {
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            connection = (HttpURLConnection) new URL(track.url)
                    .openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            for (java.util.Map.Entry<String, String> header : track.headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new IOException("HTTP " + code + " while downloading audio");
            }
            long total = connection.getContentLength();
            input = connection.getInputStream();
            output = new FileOutputStream(target);
            byte[] buffer = new byte[64 * 1024];
            long done = 0L;
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                done += read;
                if (callback != null) {
                    int percent = total > 0 ? (int) Math.min(99L, done * 100L / total) : -1;
                    callback.onProgress(percent, done, total);
                }
            }
            output.flush();
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
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

    private Uri insertIntoMediaStore(String displayName, String mimeType, File source)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, mediaRelativePath);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Could not create a MediaStore entry for the download");
        }
        boolean success = false;
        try {
            InputStream input = null;
            OutputStream output = null;
            try {
                input = new FileInputStream(source);
                output = resolver.openOutputStream(uri);
                if (output == null) {
                    throw new IOException("Could not open the MediaStore output stream");
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            } finally {
                if (output != null) {
                    try {
                        output.close();
                    } catch (IOException ignored) {
                    }
                }
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            success = true;
            return uri;
        } finally {
            if (!success) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Uri copyToAppMusic(String displayName, File source) throws IOException {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (directory == null) {
            throw new IOException("Download folder is unavailable");
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Download folder is unavailable");
        }
        File target = new File(directory, displayName);
        int suffix = 1;
        while (target.exists()) {
            int dot = displayName.lastIndexOf('.');
            String base = dot > 0 ? displayName.substring(0, dot) : displayName;
            String extension = dot > 0 ? displayName.substring(dot) : "";
            target = new File(directory, base + " (" + suffix + ")" + extension);
            suffix++;
        }
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(target);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
        MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()},
                new String[]{"audio/mp4"}, null);
        return Uri.fromFile(target);
    }

    private static boolean exists(String localUri) {
        try {
            Uri uri = Uri.parse(localUri);
            if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                return new File(uri.getPath()).exists();
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static String sanitize(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "")
                .trim();
        if (cleaned.length() == 0 || ".".equals(cleaned) || "..".equals(cleaned)) {
            cleaned = "track";
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }

    /* --------------------------------------------------------------- index */

    private List<MediaItem> readIndex() {
        List<MediaItem> out = new ArrayList<>();
        String raw = prefs.getString(KEY_INDEX, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) {
                    out.add(MediaItem.fromJson(object));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void writeIndex(List<MediaItem> items) {
        prefs.edit().putString(KEY_INDEX, MediaItem.toJsonArray(items).toString()).apply();
    }
}
