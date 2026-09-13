package io.github.a14398138.zipbackup;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.File;
import java.util.*;

final class FilePickerDialog {
    interface OnFolderSelectedListener {
        void onFolderSelected(File folder);
    }

    static void show(Context context, OnFolderSelectedListener listener) {
        File initial = Environment.getExternalStorageDirectory();
        showDirectory(context, initial, listener);
    }

    private static void showDirectory(Context context, File currentDir, OnFolderSelectedListener listener) {
        File[] files = currentDir.listFiles();
        List<File> subDirs = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && !f.getName().startsWith(".")) {
                    subDirs.add(f);
                }
            }
            Collections.sort(subDirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }

        List<String> displayNames = new ArrayList<>();
        List<File> dirList = new ArrayList<>();

        File parent = currentDir.getParentFile();
        boolean hasParent = parent != null && currentDir.getAbsolutePath().startsWith(Environment.getExternalStorageDirectory().getAbsolutePath())
                && !currentDir.getAbsolutePath().equals(Environment.getExternalStorageDirectory().getAbsolutePath());

        if (hasParent) {
            displayNames.add("📁 ..（上の階層へ）");
            dirList.add(parent);
        }

        for (File d : subDirs) {
            displayNames.add("📁 " + d.getName());
            dirList.add(d);
        }

        String titlePath = currentDir.getAbsolutePath();
        String storageRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (titlePath.startsWith(storageRoot)) {
            titlePath = "内部共有ストレージ" + titlePath.substring(storageRoot.length());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(titlePath);

        if (displayNames.isEmpty()) {
            builder.setMessage("サブフォルダがありません");
        } else {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, displayNames);
            builder.setAdapter(adapter, (dialog, which) -> {
                File chosen = dirList.get(which);
                showDirectory(context, chosen, listener);
            });
        }

        builder.setPositiveButton("このフォルダを選択", (dialog, which) -> {
            listener.onFolderSelected(currentDir);
        });

        builder.setNegativeButton("キャンセル", null);
        builder.show();
    }
}
