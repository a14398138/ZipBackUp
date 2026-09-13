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
    static List<String> expired(List<String> newestFirst, int keep) {
        if (keep < 1) throw new IllegalArgumentException("keep");
        return new ArrayList<>(newestFirst.subList(Math.min(keep, newestFirst.size()), newestFirst.size()));
    }
}
