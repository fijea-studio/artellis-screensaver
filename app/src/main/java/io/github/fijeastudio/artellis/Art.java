package io.github.fijeastudio.artellis;

import org.json.JSONException;
import org.json.JSONObject;

/** One painting saved on the TV: its label (Artic style: title, year, artist) and the picture file. */
final class Art {

    /** "aic" or "met", plus the museum's own id: also the key that keeps a painting from coming back soon. */
    final String key;
    final String title;
    final String date;
    final String artist;
    /** File name inside the art folder. */
    final String file;
    /** Size of the saved picture, already scaled for its place on this screen. */
    final int width;
    final int height;

    Art(String key, String title, String date, String artist, String file, int width, int height) {
        this.key = key;
        this.title = title;
        this.date = date;
        this.artist = artist;
        this.file = file;
        this.width = width;
        this.height = height;
    }

    float aspect() {
        return height > 0 ? (float) width / height : 1f;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("key", key).put("title", title).put("date", date).put("artist", artist)
                .put("file", file).put("w", width).put("h", height);
    }

    static Art fromJson(JSONObject o) {
        return new Art(o.optString("key"), o.optString("title"), o.optString("date"), o.optString("artist"),
                o.optString("file"), o.optInt("w"), o.optInt("h"));
    }

    /** "Title, 1884": the first label line. */
    String titleLine() {
        // Non-breaking spaces keep ", c. 1884" together on the last line when a long title wraps.
        return date.isEmpty() ? title : title + ", " + date.replace(' ', ' ');
    }
}
