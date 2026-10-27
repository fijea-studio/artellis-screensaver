package io.github.fijeastudio.artellis;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One to three paintings hung together, in hanging order, and the day they were fetched. */
final class Wall {

    final List<Art> art;
    final String day;

    Wall(List<Art> art, String day) {
        this.art = art;
        this.day = day;
    }

    float[] aspects() {
        float[] a = new float[art.size()];
        for (int i = 0; i < a.length; i++) a[i] = art.get(i).aspect();
        return a;
    }

    JSONObject toJson() throws JSONException {
        JSONArray list = new JSONArray();
        for (Art a : art) list.put(a.toJson());
        return new JSONObject().put("day", day).put("art", list);
    }

    static Wall fromJson(JSONObject o) throws JSONException {
        JSONArray list = o.getJSONArray("art");
        List<Art> art = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) art.add(Art.fromJson(list.getJSONObject(i)));
        return new Wall(art, o.optString("day"));
    }
}
