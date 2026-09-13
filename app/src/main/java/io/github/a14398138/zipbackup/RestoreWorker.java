package io.github.a14398138.zipbackup;

import android.content.Context;
import android.net.Uri;
import androidx.work.*;
import java.io.*;
import java.util.Arrays;

public final class RestoreWorker extends BaseWorker {
    public RestoreWorker(Context c,WorkerParameters p) { super(c,p); }
    @Override public Result doWork() {
        File dir=null; char[] password=null; Settings s=new Settings(getApplicationContext());
        String key="restore-password-"+getId();
        try {
            foreground("ZIPを検証して復元中"); dir=jobDir(); File zip=new File(dir,"restore.zip");
            password=Vault.decrypt(s.prefs.getString(key,""));
            String source=getInputData().getString("source"), target=getInputData().getString("target");
            if(source==null||target==null) throw new IOException("ZIPと復元先を選択してください");
            status("復元用ZIPを読み込み中");
            try(InputStream in=getApplicationContext().getContentResolver().openInputStream(Uri.parse(source)); OutputStream out=new FileOutputStream(zip)) {
                if(in==null) throw new IOException("ZIPを読み取れません");
                Archive.copy(in,out,()->{check(); if(getApplicationContext().getFilesDir().getUsableSpace()<32*1024*1024) throw new IOException("端末の空き容量が不足しています");},200L*1024*1024*1024);
            }
            status("全ファイルを検証してから復元します");
            Archive.restore(getApplicationContext(),zip,password,Uri.parse(target),this::check);
            finish("復元完了：選択先のZipBackUp-restoreフォルダを確認してください"); return Result.success();
        } catch(Exception e) { finish("復元失敗："+error(e)); return Result.failure(); }
        finally { if(password!=null) Arrays.fill(password,'\0'); s.prefs.edit().remove(key).commit(); if(dir!=null) delete(dir); }
    }
}
