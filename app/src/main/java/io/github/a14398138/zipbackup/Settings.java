package io.github.a14398138.zipbackup;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

final class Settings {
    final SharedPreferences prefs;
    Settings(Context c) { prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    boolean mobile() { return prefs.getBoolean("mobile", false); }
    boolean charging() { return prefs.getBoolean("charging", true); }
    boolean enabled() { return prefs.getBoolean("enabled", false); }
    int hours() { return prefs.getInt("hours", 24); }
    String account() { return prefs.getString("account", ""); }
    List<String> roots() { return new ArrayList<>(new TreeSet<>(prefs.getStringSet("roots", Collections.emptySet()))); }
    void root(String uri, boolean add) {
        Set<String> roots = new HashSet<>(roots());
        if (add) roots.add(uri); else roots.remove(uri);
        prefs.edit().putStringSet("roots", roots).commit();
    }
    String deviceId() {
        String id = prefs.getString("device", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); prefs.edit().putString("device", id).commit(); }
        return id;
    }
    void status(String message) { prefs.edit().putString("status", message).apply(); }
}
