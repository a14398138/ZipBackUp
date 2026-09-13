package io.github.a14398138.zipbackup;

import java.io.IOException;
import java.util.*;

final class ArchiveRules {
    static String component(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains(":"))
            throw new IOException("安全に扱えないファイル名があります");
        for (char c : name.toCharArray()) if (Character.isISOControl(c)) throw new IOException("制御文字を含むファイル名です");
        return name;
    }
    static String[] path(String name) throws IOException {
        if (name == null || name.length() > 4096) throw new IOException("不正なZIPパスです");
        String clean = name.endsWith("/") ? name.substring(0, name.length()-1) : name;
        String[] parts = clean.split("/", -1);
        if (parts.length > 64) throw new IOException("フォルダ階層が深すぎます");
        for (String p : parts) component(p);
        return parts;
    }
    static final class PathIndex {
        private final Map<String,String> names = new HashMap<>();
        private final Map<String,Boolean> directories = new HashMap<>();
        private final Set<String> entries = new HashSet<>();
        void add(String name, boolean directory) throws IOException {
            String[] parts = path(name); String raw = "";
            for (int i=0; i<parts.length; i++) {
                raw += (i==0 ? "" : "/") + parts[i];
                String key = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                boolean isDir = i<parts.length-1 || directory;
                if (names.containsKey(key) && (!names.get(key).equals(raw) || directories.get(key)!=isDir))
                    throw new IOException("復元先で名前が衝突するZIPです");
                names.put(key,raw); directories.put(key,isDir);
                if (i==parts.length-1 && !entries.add(key)) throw new IOException("ZIP内の名前が重複しています");
            }
        }
    }
    static List<String> expired(List<String> newestFirst, int keep) {
        if (keep < 1) throw new IllegalArgumentException("keep");
        return new ArrayList<>(newestFirst.subList(Math.min(keep, newestFirst.size()), newestFirst.size()));
    }
}
