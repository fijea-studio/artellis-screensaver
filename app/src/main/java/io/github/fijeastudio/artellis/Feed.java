package io.github.fijeastudio.artellis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The walls: about 15 a day, each one to three public-domain paintings from one place and period, from the
 * Art Institute of Chicago (two walls in three) or The Met's European Paintings. Each painting is saved in the
 * app's private folder already scaled for its place on this screen. Fetched only while the screensaver runs
 * (no background service), topped up after a run that was cut short.
 *
 * Storage: about 60 paintings at most (40 MB hard cap), the oldest walls going first; no downloading while the
 * TV has under 500 MB free. The last 1500 works shown are remembered so they don't come back for weeks.
 */
final class Feed {

    interface Listener {
        /** A new wall is saved and may be shown. Called on the feed's own thread. */
        void onWall(Wall wall);
    }

    static final String TAG = "Artellis";
    static final int WALLS_PER_DAY = 15;
    private static final int MAX_PAINTINGS = 60;
    private static final long MAX_BYTES = 40L * 1024 * 1024;
    private static final long MIN_FREE_BYTES = 500L * 1024 * 1024;
    private static final int SEEN_MAX = 1500;
    private static final long MAX_DOWNLOAD = 40L * 1024 * 1024;
    private static final int JPEG_QUALITY = 88;

    private static final String AIC_SEARCH = "https://api.artic.edu/api/v1/artworks/search";
    private static final String AIC_FIELDS = "id,title,date_display,artist_title,image_id,thumbnail";
    private static final String AIC_IMAGE = "https://www.artic.edu/iiif/2/%s/full/1686,/0/default.jpg";
    private static final String AIC_IMAGE_SMALL = "https://www.artic.edu/iiif/2/%s/full/843,/0/default.jpg";
    private static final String MET_SEARCH = "https://collectionapi.metmuseum.org/public/collection/v1/search";
    private static final String MET_OBJECT = "https://collectionapi.metmuseum.org/public/collection/v1/objects/";

    /** A painting that could hang on the wall being put together. */
    private static final class Candidate {
        String key, title, date, artist;
        float aspect;
        /** Art Institute: the IIIF image id. */
        String imageId;
        /** The Met: the web-large picture already downloaded (to learn its shape), and the full scan's URL. */
        File local;
        String bigUrl;
    }

    private final File dir;
    private final SharedPreferences prefs;
    private final int screenW;
    private final int screenH;
    private final Random random = new Random();
    private volatile boolean cancelled;
    /** One refresh at a time in the app: the screensaver and the settings page each have their own Feed. */
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    Feed(Context context, int screenW, int screenH) {
        dir = new File(context.getFilesDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        prefs = context.getSharedPreferences("artellis", Context.MODE_PRIVATE);
        this.screenW = screenW;
        this.screenH = screenH;
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    File fileOf(Art art) {
        return new File(dir, art.file);
    }

    /** Saved walls, newest first, whose pictures are all still there. */
    synchronized List<Wall> saved() {
        List<Wall> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("walls", "[]"));
            for (int i = 0; i < array.length(); i++) {
                Wall wall = Wall.fromJson(array.getJSONObject(i));
                boolean whole = !wall.art.isEmpty();
                for (Art a : wall.art) whole &= new File(dir, a.file).isFile();
                if (whole) list.add(wall);
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    /** {walls saved, bytes used}, for the settings page. */
    long[] stats() {
        List<Wall> walls = saved();
        long bytes = 0;
        for (Wall w : walls) for (Art a : w.art) bytes += new File(dir, a.file).length();
        return new long[]{walls.size(), bytes};
    }

    /** How many more walls today still needs. */
    int missingToday() {
        String today = today();
        int count = 0;
        for (Wall w : saved()) if (today.equals(w.day)) count++;
        return Math.max(0, WALLS_PER_DAY - count);
    }

    void cancel() {
        cancelled = true;
    }

    /** Fetches the walls today still needs. See {@link #refresh(Listener, int)}. */
    boolean refresh(Listener listener) {
        return refresh(listener, Integer.MAX_VALUE);
    }

    /**
     * Fetches up to {@code max} of the walls today still needs. Runs on the caller's (background) thread.
     * False, at once, when another refresh is still running: a screensaver run that just ended finishing its last
     * request, or the settings page fetching its first wall.
     */
    boolean refresh(Listener listener, int max) {
        if (!BUSY.compareAndSet(false, true)) return false;
        try {
            cancelled = false;
            // Leftovers of a download that was cut short (standby, Wi-Fi drop).
            File[] stale = dir.listFiles((d, name) -> name.endsWith(".tmp"));
            if (stale != null) for (File f : stale) //noinspection ResultOfMethodCallIgnored
                f.delete();
            int failures = 0;
            int fetched = 0;
            while (!cancelled && fetched < max && missingToday() > 0 && failures < 4) {
                if (dir.getUsableSpace() < MIN_FREE_BYTES) {
                    Log.i(TAG, "under 500 MB free on the TV: not downloading");
                    break;
                }
                Wall wall = nextWall();
                if (wall == null) {
                    failures++;
                    continue;
                }
                failures = 0;
                add(wall);
                fetched++;
                listener.onWall(wall);
            }
            prune();
        } finally {
            BUSY.set(false);
        }
        return true;
    }

    /** One wall: a theme from one museum, then the other, then a random Art Institute wall. */
    private Wall nextWall() {
        Set<String> seen = seen();
        int last = prefs.getInt("last_theme", -1);
        int pick = random.nextInt(Theme.ALL.length);
        if (pick == last) pick = (pick + 1) % Theme.ALL.length;
        prefs.edit().putInt("last_theme", pick).apply();
        Theme theme = Theme.ALL[pick];
        boolean metFirst = theme.metNationality != null && random.nextFloat() < 0.34f;
        Wall wall = metFirst ? metWall(theme, seen) : aicWall(theme, seen);
        if (wall == null && !cancelled) wall = metFirst ? aicWall(theme, seen) : metWall(theme, seen);
        if (wall == null && !cancelled) wall = aicWall(null, seen);
        return wall;
    }

    // ---- Art Institute of Chicago -------------------------------------------------------------------------

    private Wall aicWall(Theme theme, Set<String> seen) {
        List<Candidate> pool = new ArrayList<>();
        try {
            for (JSONObject o : aicPage(theme)) {
                Candidate c = new Candidate();
                c.key = "aic:" + o.optInt("id");
                c.imageId = o.optString("image_id", "");
                c.title = clean(o.optString("title"));
                c.artist = clean(o.optString("artist_title"));
                c.date = clean(o.optString("date_display"));
                JSONObject thumb = o.optJSONObject("thumbnail");
                int w = thumb == null ? 0 : thumb.optInt("width"), h = thumb == null ? 0 : thumb.optInt("height");
                if (seen.contains(c.key) || c.imageId.isEmpty() || "null".equals(c.imageId)
                        || c.title.isEmpty() || c.artist.isEmpty() || w <= 0 || h <= 0) continue;
                c.aspect = (float) w / h;
                pool.add(c);
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Art Institute search failed: " + e);
            return null;
        }
        Collections.shuffle(pool, random);
        return hang(pool, seen);
    }

    /** One random page of public-domain paintings with an image, in the theme when there is one. */
    private List<JSONObject> aicPage(Theme theme) throws IOException, JSONException {
        String query = "params=" + Uri.encode(aicQuery(theme));
        int limit = 40;
        int total;
        try {
            total = new JSONObject(Net.text(aicUrl(query, 1, 1))).getJSONObject("pagination").optInt("total", 0);
        } catch (IOException | JSONException e) {
            if (theme != null) throw e;
            total = 0;
        }
        if (total == 0 && theme == null) {
            // The API didn't take `params`: the same filters as query[...] fields.
            query = aicBrackets();
            total = new JSONObject(Net.text(aicUrl(query, 1, 1))).getJSONObject("pagination").optInt("total", 0);
        }
        List<JSONObject> list = new ArrayList<>();
        if (total == 0) return list;
        // Elasticsearch serves the first 10,000 hits at most.
        int pages = Math.max(1, (Math.min(total, 10_000) + limit - 1) / limit);
        int page = 1 + random.nextInt(pages);
        JSONArray data = new JSONObject(Net.text(aicUrl(query, page, limit))).getJSONArray("data");
        for (int i = 0; i < data.length(); i++) list.add(data.getJSONObject(i));
        return list;
    }

    private static String aicQuery(Theme theme) throws JSONException {
        JSONArray filter = new JSONArray()
                .put(new JSONObject().put("term", new JSONObject().put("is_public_domain", true)))
                .put(new JSONObject().put("term", new JSONObject().put("artwork_type_id", 1)))   // Painting
                .put(new JSONObject().put("exists", new JSONObject().put("field", "image_id")));
        if (theme != null) {
            filter.put(new JSONObject().put("range", new JSONObject().put("date_start",
                    new JSONObject().put("gte", theme.from).put("lte", theme.to))));
            filter.put(new JSONObject().put("match", new JSONObject().put("place_of_origin", theme.aicPlace)));
        }
        return new JSONObject().put("query",
                new JSONObject().put("bool", new JSONObject().put("filter", filter))).toString();
    }

    private static String aicBrackets() {
        String f = "query%5Bbool%5D%5Bfilter%5D%5B";
        return f + "0%5D%5Bterm%5D%5Bis_public_domain%5D=true"
                + "&" + f + "1%5D%5Bterm%5D%5Bartwork_type_id%5D=1"
                + "&" + f + "2%5D%5Bexists%5D%5Bfield%5D=image_id";
    }

    private static String aicUrl(String query, int page, int limit) {
        return AIC_SEARCH + "?" + query + "&fields=" + AIC_FIELDS + "&limit=" + limit + "&page=" + page;
    }

    // ---- The Met ----------------------------------------------------------------------------------------------

    private Wall metWall(Theme theme, Set<String> seen) {
        if (theme.metNationality == null || cancelled) return null;
        List<Candidate> pool = new ArrayList<>();
        try {
            String url = MET_SEARCH + "?departmentId=11&hasImages=true&dateBegin=" + theme.from
                    + "&dateEnd=" + theme.to + "&q=" + Uri.encode(theme.metNationality);
            JSONArray found = new JSONObject(Net.text(url)).optJSONArray("objectIDs");
            if (found == null || found.length() == 0) return null;
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < found.length(); i++) ids.add(found.getInt(i));
            Collections.shuffle(ids, random);
            int tries = 0;
            for (int id : ids) {
                if (pool.size() >= 5 || tries >= 14 || cancelled) break;
                String key = "met:" + id;
                if (seen.contains(key)) continue;
                tries++;
                Candidate c = metCandidate(id, key, theme);
                if (c == null) remember(seen, key);   // not usable: don't look at it again
                else pool.add(c);
            }
            if (pool.isEmpty()) return null;
            return hang(pool, seen);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "The Met failed: " + e);
            return null;
        } finally {
            for (Candidate c : pool) if (c.local != null) //noinspection ResultOfMethodCallIgnored
                c.local.delete();
        }
    }

    /** A usable Met painting with its web-large picture downloaded, or null. */
    private Candidate metCandidate(int id, String key, Theme theme) {
        try {
            JSONObject o = new JSONObject(Net.text(MET_OBJECT + id));
            String big = o.optString("primaryImage", "");
            String small = o.optString("primaryImageSmall", "");
            String nationality = o.optString("artistNationality", "").toLowerCase(Locale.ROOT);
            String want = theme.metNationality.toLowerCase(Locale.ROOT);
            boolean sameOrigin = nationality.contains(want)
                    || ("british".equals(want) && nationality.contains("english"));
            int begin = o.optInt("objectBeginDate", Integer.MIN_VALUE);
            String kind = o.optString("classification", "").toLowerCase(Locale.ROOT);
            Candidate c = new Candidate();
            c.key = key;
            c.title = clean(o.optString("title"));
            c.artist = clean(o.optString("artistDisplayName"));
            c.date = clean(o.optString("objectDate"));
            if (!o.optBoolean("isPublicDomain") || (big.isEmpty() && small.isEmpty()) || !sameOrigin
                    || c.title.isEmpty() || c.artist.isEmpty() || c.date.isEmpty()
                    || begin < theme.from - 25 || begin > theme.to + 25
                    || (!kind.isEmpty() && !kind.contains("painting"))) return null;
            File local = new File(dir, "cand-" + id + ".tmp");
            Net.file(small.isEmpty() ? big : small, local, MAX_DOWNLOAD);
            int[] size = bounds(local);
            if (size[0] <= 0 || size[1] <= 0) {
                //noinspection ResultOfMethodCallIgnored
                local.delete();
                return null;
            }
            c.local = local;
            c.aspect = (float) size[0] / size[1];
            c.bigUrl = small.isEmpty() ? null : big;
            return c;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "The Met object " + id + " failed: " + e);
            return null;
        }
    }

    // ---- Hanging and saving ------------------------------------------------------------------------------------

    /** Chooses the wall's paintings from the pool and saves each at the height it hangs at. */
    private Wall hang(List<Candidate> pool, Set<String> seen) {
        if (pool.isEmpty() || cancelled) return null;
        float[] aspects = new float[pool.size()];
        for (int i = 0; i < aspects.length; i++) aspects[i] = pool.get(i).aspect;
        int[] chosen = Hang.choose(aspects, random, screenW, screenH);
        float[] picked = new float[chosen.length];
        for (int i = 0; i < chosen.length; i++) picked[i] = aspects[chosen[i]];
        int targetH = Math.round(Hang.height(picked, screenW, screenH));
        String today = today();
        List<Art> art = new ArrayList<>();
        for (int i : chosen) {
            if (cancelled) return null;
            Candidate c = pool.get(i);
            Art a = c.imageId != null ? saveAic(c, targetH) : saveMet(c, targetH);
            if (a != null) {
                art.add(a);
                remember(seen, c.key);
            }
        }
        return art.isEmpty() ? null : new Wall(art, today);
    }

    private Art saveAic(Candidate c, int targetH) {
        File tmp = new File(dir, "download.tmp");
        try {
            // 1686 wide is sharper; 843 is the size the museum always serves.
            for (String size : new String[]{AIC_IMAGE, AIC_IMAGE_SMALL}) {
                try {
                    Net.file(String.format(Locale.ROOT, size, c.imageId), tmp, MAX_DOWNLOAD);
                    Art a = saveScaled(tmp, c, targetH);
                    if (a != null) return a;
                } catch (IOException e) {
                    Log.w(TAG, "picture " + c.key + " failed: " + e);
                }
            }
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private Art saveMet(Candidate c, int targetH) {
        File tmp = new File(dir, "download.tmp");
        try {
            int[] size = bounds(c.local);
            // The web-large picture is often enough; the full scan only when it would show noticeably soft.
            if (c.bigUrl != null && !c.bigUrl.isEmpty() && size[1] < targetH * 0.9f) {
                try {
                    Net.file(c.bigUrl, tmp, MAX_DOWNLOAD);
                    Art a = saveScaled(tmp, c, targetH);
                    if (a != null) return a;
                } catch (IOException e) {
                    Log.w(TAG, "full scan " + c.key + " failed, using the smaller one: " + e);
                }
            }
            return saveScaled(c.local, c, targetH);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Decodes, scales to the place it hangs at and saves as JPEG. Null when it can't be read. */
    private Art saveScaled(File src, Candidate c, int targetH) {
        try {
            int[] size = bounds(src);
            int w = size[0], h = size[1];
            if (w <= 0 || h <= 0) return null;
            int[] t = Fit.target(w, h, targetH);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Fit.sampleSize(w, h, t[1]);
            Bitmap decoded = BitmapFactory.decodeFile(src.getPath(), options);
            if (decoded == null) return null;
            Bitmap scaled = decoded;
            if (decoded.getWidth() != t[0] || decoded.getHeight() != t[1]) {
                scaled = Bitmap.createScaledBitmap(decoded, t[0], t[1], true);
                if (scaled != decoded) decoded.recycle();
            }
            String name = c.key.replace(':', '-') + ".jpg";
            try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            }
            int sw = scaled.getWidth(), sh = scaled.getHeight();
            scaled.recycle();
            return new Art(c.key, c.title, c.date, c.artist, name, sw, sh);
        } catch (IOException | OutOfMemoryError | RuntimeException e) {
            Log.w(TAG, "picture " + c.key + " failed: " + e);
            return null;
        }
    }

    private static int[] bounds(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        return new int[]{o.outWidth, o.outHeight};
    }

    // ---- Index and storage -----------------------------------------------------------------------------------

    /** Adds the wall in front, then drops the oldest walls past 60 paintings or 40 MB. */
    private synchronized void add(Wall wall) {
        List<Wall> walls = new ArrayList<>();
        walls.add(wall);
        walls.addAll(saved());
        int paintings = 0;
        long bytes = 0;
        int keep = 0;
        for (Wall w : walls) {
            int n = w.art.size();
            long b = 0;
            for (Art a : w.art) b += new File(dir, a.file).length();
            if (keep > 0 && (paintings + n > MAX_PAINTINGS || bytes + b > MAX_BYTES)) break;
            paintings += n;
            bytes += b;
            keep++;
        }
        writeIndex(walls.subList(0, keep));
        prune();
    }

    /** Removes files no longer on a saved wall (and leftovers from a broken-off download). */
    private synchronized void prune() {
        Set<String> keep = new HashSet<>();
        for (Wall w : saved()) for (Art a : w.art) keep.add(a.file);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (keep.contains(name) || (BUSY.get() && name.endsWith(".tmp"))) continue;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private void writeIndex(List<Wall> walls) {
        JSONArray array = new JSONArray();
        try {
            for (Wall w : walls) array.put(w.toJson());
        } catch (JSONException ignored) {
        }
        // commit(), not apply(): prune() reads the index straight after.
        prefs.edit().putString("walls", array.toString()).commit();
    }

    private Set<String> seen() {
        Set<String> set = new LinkedHashSet<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("seen", "[]"));
            for (int i = 0; i < array.length(); i++) set.add(array.getString(i));
        } catch (JSONException ignored) {
        }
        return set;
    }

    private void remember(Set<String> seen, String key) {
        seen.add(key);
        List<String> list = new ArrayList<>(seen);
        if (list.size() > SEEN_MAX) list = list.subList(list.size() - SEEN_MAX, list.size());
        prefs.edit().putString("seen", new JSONArray(list).toString()).apply();
    }

    /** Museum text can carry HTML entities or line breaks. */
    private static String clean(String s) {
        if (s == null || "null".equals(s)) return "";
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("\\s+", " ").trim();
    }
}
