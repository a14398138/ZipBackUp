package io.github.a14398138.zipbackup;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.*;
import net.lingala.zip4j.model.enums.*;
import java.io.*;
import java.util.*;

final class Archive {
    interface Check { void run() throws IOException; }
    private static final long MAX_EXTRACT = 200L * 1024 * 1024 * 1024;
    static ZipParameters parameters(String path, boolean directory) {
        ZipParameters p = new ZipParameters();
        p.setFileNameInZip(path);
        p.setEncryptFiles(!directory);
        p.setEncryptionMethod(EncryptionMethod.AES);
        p.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        p.setCompressionMethod(CompressionMethod.DEFLATE);
        return p;
    }
    static long create(Context c, List<String> roots, File file, char[] password, Check check) throws Exception {
        long[] count = {0}; Set<String> paths = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)), password)) {
            int index = 0;
            for (String raw : roots) {
                check.run(); Uri tree = Uri.parse(raw);
                String id = DocumentsContract.getTreeDocumentId(tree);
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                String name;
                try (Cursor cur = c.getContentResolver().query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                    if (cur == null || !cur.moveToFirst()) throw new IOException("選択フォルダを読み取れません。再選択してください");
                    name = ArchiveRules.component(cur.getString(0));
                }
                walk(c, tree, id, (++index) + "-" + name + "/", zip, paths, count, check, 0);
            }
        }
        if (count[0] == 0) throw new IOException("対象ファイルが0件です。フォルダの内容を確認してください");
        return count[0];
    }
    private static void walk(Context c, Uri tree, String id, String path, ZipOutputStream zip,
                             Set<String> paths, long[] count, Check check, int depth) throws Exception {
        check.run();
        if (depth > 60 || paths.size() >= 100000) throw new IOException("対象が上限（10万項目・60階層）を超えました");
        if (!paths.add(path)) throw new IOException("同じ名前の項目があります");
        zip.putNextEntry(parameters(path, true)); zip.closeEntry();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try (Cursor cur = c.getContentResolver().query(children, cols, null, null, null)) {
            if (cur == null) throw new IOException("フォルダ一覧を読み取れません");
            while (cur.moveToNext()) {
                check.run(); String child = cur.getString(0), name = ArchiveRules.component(cur.getString(1));
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(2))) {
                    walk(c, tree, child, path + name + "/", zip, paths, count, check, depth + 1);
                } else {
                    String entry = path + name;
                    if (paths.size() >= 100000 || !paths.add(entry)) throw new IOException("項目数が多すぎるか名前が重複しています");
                    long expected = cur.isNull(3) ? -1 : cur.getLong(3), modified = cur.getLong(4);
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, child);
                    zip.putNextEntry(parameters(entry, false));
                    long read;
                    try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("ファイルを開けません");
                        read = copy(in, zip, check, Long.MAX_VALUE);
                    }
                    zip.closeEntry();
                    if (expected >= 0 && expected != read) throw new IOException("処理中にファイルサイズが変わりました。再実行してください");
                    try (Cursor after = c.getContentResolver().query(uri, new String[]{cols[3], cols[4]}, null, null, null)) {
                        if (after == null || !after.moveToFirst() || (!after.isNull(0) && after.getLong(0) != read) || after.getLong(1) != modified)
                            throw new IOException("処理中にファイルが変わりました。再実行してください");
                    }
                    count[0]++;
                }
            }
        }
    }
    static long copy(InputStream in, OutputStream out, Check check, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
        while ((n = in.read(buffer)) != -1) {
            check.run(); total += n;
            if (total > limit) throw new IOException("ファイルサイズが上限を超えました");
            out.write(buffer, 0, n);
        }
        return total;
    }
    static void verify(File file, char[] password, Check check) throws Exception {
        try (ZipFile zip = new ZipFile(file, password)) {
            List<FileHeader> headers = zip.getFileHeaders();
            if (headers.isEmpty() || headers.size() > 100000) throw new IOException("ZIPの項目数が不正です");
            Set<String> seen = new HashSet<>(); long total = 0;
            for (FileHeader h : headers) {
                check.run(); ArchiveRules.path(h.getFileName());
                String key = String.join("/", ArchiveRules.path(h.getFileName()));
                if (!seen.add(key)) throw new IOException("ZIP内の名前が重複しています");
                if (h.isDirectory()) continue;
                if (!h.isEncrypted() || h.getEncryptionMethod() != EncryptionMethod.AES || h.getAesExtraDataRecord() == null
                        || h.getAesExtraDataRecord().getAesKeyStrength() != AesKeyStrength.KEY_STRENGTH_256)
                    throw new IOException("AES-256のZIPを選択してください");
                if (h.getUncompressedSize() < 0 || h.getUncompressedSize() > MAX_EXTRACT - total) throw new IOException("展開サイズが200GBを超えます");
                try (InputStream in = zip.getInputStream(h)) {
                    long size = copy(in, new OutputStream() { @Override public void write(int b) {} @Override public void write(byte[] b, int off, int len) {} }, check, h.getUncompressedSize());
                    if (size != h.getUncompressedSize()) throw new IOException("ZIPが破損しています");
                    total += size;
                }
            }
        }
    }
    static void restore(Context c, File file, char[] password, Uri destination, Check check) throws Exception {
        verify(file, password, check); // Authenticate every entry before writing any plaintext.
        DocumentFile parent = DocumentFile.fromTreeUri(c, destination);
        if (parent == null || !parent.canWrite()) throw new IOException("復元先に書き込めません");
        DocumentFile root = parent.createDirectory("ZipBackUp-restore-" + System.currentTimeMillis());
        if (root == null) throw new IOException("復元フォルダを作成できません");
        boolean ok = false;
        try (ZipFile zip = new ZipFile(file, password)) {
            Map<String, DocumentFile> dirs = new HashMap<>(); dirs.put("", root);
            for (FileHeader h : zip.getFileHeaders()) {
                check.run(); String[] parts = ArchiveRules.path(h.getFileName());
                DocumentFile dir = root; String prefix = "";
                for (int i=0; i<parts.length - (h.isDirectory() ? 0 : 1); i++) {
                    prefix += parts[i] + "/";
                    DocumentFile next = dirs.get(prefix);
                    if (next == null) { next = dir.createDirectory(parts[i]); if (next == null) throw new IOException("フォルダを作成できません"); dirs.put(prefix, next); }
                    dir = next;
                }
                if (!h.isDirectory()) {
                    DocumentFile target = dir.createFile("application/octet-stream", parts[parts.length-1]);
                    if (target == null) throw new IOException("復元ファイルを作成できません");
                    try (InputStream in = zip.getInputStream(h); OutputStream out = c.getContentResolver().openOutputStream(target.getUri(), "wt")) {
                        if (out == null) throw new IOException("復元先を開けません");
                        copy(in, out, check, h.getUncompressedSize());
                    }
                }
            }
            ok = true;
        } finally {
            if (!ok && !root.delete()) new Settings(c).status("復元失敗。復元先に未完成フォルダが残っています。削除してください");
        }
    }
}
