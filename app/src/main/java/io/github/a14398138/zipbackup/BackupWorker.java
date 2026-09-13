package io.github.a14398138.zipbackup;

import android.content.Context;
import androidx.work.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class BackupWorker extends BaseWorker {
    public BackupWorker(Context c,WorkerParameters p) { super(c,p); }
    @Override public Result doWork() {
        File dir=null; char[] password=null; boolean terminal=false;
        try {
            foreground("暗号化バックアップを実行中");
            Context c=getApplicationContext(); Settings settings=new Settings(c);
            if(settings.roots().isEmpty()) throw new IllegalStateException("対象フォルダを選択してください");
            String account=settings.account(); Auth.token(c,account); password=Vault.read(c);
            dir=jobDir(); File zip=new File(dir,"backup.zip"), ready=new File(dir,"ready");
            if(!ready.exists()) {
                if(zip.exists()&&!zip.delete()) throw new IOException("前回の一時ファイルを削除できません");
                status("ファイルをAES-256で暗号化中");
                Archive.create(c,settings.roots(),zip,password,()->{check(); if(c.getFilesDir().getUsableSpace()<32*1024*1024) throw new IOException("端末の空き容量が不足しています");});
                status("暗号化ZIPを検証中"); Archive.verify(zip,password,this::check);
                try(FileOutputStream out=new FileOutputStream(ready)) { out.write(1); out.getFD().sync(); }
            }
            Drive drive=new Drive(c,account,this::check); String folder=drive.folder(); status("Driveへ送信中");
            String name="ZipBackUp-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date(zip.lastModified()))+"-"+getId().toString().substring(0,8)+".zip";
            String id=drive.upload(zip,folder,settings.deviceId(),getId().toString(),name);
            status("Drive上のZIPを検証中"); drive.verify(id,zip); check();
            String message="バックアップ完了";
            try { drive.prune(folder,settings.deviceId()); }
            catch(Exception e) { message="バックアップ完了（古い世代の整理は未完了）"; }
            finish(message); terminal=true; return Result.success();
        } catch(Exception e) {
            if(isStopped()) { status("中断しました。定期実行または手動実行で再開してください"); return Result.failure(); }
            boolean retry=e instanceof IOException && !(e instanceof Auth.Required) && !(e instanceof net.lingala.zip4j.exception.ZipException)
                    && (!(e instanceof Drive.ApiError) || ((Drive.ApiError)e).code==429 || ((Drive.ApiError)e).code>=500) && getRunAttemptCount()<4;
            if(retry) { status("再試行待ち："+error(e)); return Result.retry(); }
            finish("バックアップ失敗："+error(e)); terminal=true; return Result.failure();
        } finally {
            if(password!=null) Arrays.fill(password,'\0');
            if(terminal&&dir!=null) delete(dir);
        }
    }
}
