package io.github.a14398138.zipbackup;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import androidx.work.*;
import java.io.*;
import java.text.DateFormat;
import java.util.Date;

abstract class BaseWorker extends Worker {
    BaseWorker(Context c,WorkerParameters p) { super(c,p); }
    void check() throws IOException { if(isStopped()||Thread.currentThread().isInterrupted()) throw new IOException("処理が中断されました"); }
    Notification notification(String title, boolean ongoing) {
        Context c=getApplicationContext(); NotificationManager nm=c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("backup","バックアップと復元",NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b=new Notification.Builder(c,"backup").setSmallIcon(R.drawable.ic_backup).setContentTitle("ZipBackUp")
                .setContentText(title).setOngoing(ongoing).setContentIntent(PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE));
        if(ongoing) b.addAction(new Notification.Action.Builder(null,"中止",WorkManager.getInstance(c).createCancelPendingIntent(getId())).build());
        return b.build();
    }
    void foreground(String title) throws Exception {
        setForegroundAsync(new ForegroundInfo(Math.abs(getId().hashCode()%100000)+1,notification(title,true),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)).get();
    }
    void status(String message) { new Settings(getApplicationContext()).status(message); }
    void finish(String message) {
        Settings s=new Settings(getApplicationContext()); status(message);
        String history=DateFormat.getDateTimeInstance().format(new Date())+"  "+message+"\n"+s.prefs.getString("history","");
        String[] lines=history.split("\n"); StringBuilder shortHistory=new StringBuilder();
        for(int i=0;i<Math.min(lines.length,20);i++) shortHistory.append(lines[i]).append('\n');
        s.prefs.edit().putString("history",shortHistory.toString()).apply();
        try { getApplicationContext().getSystemService(NotificationManager.class).notify(100001,notification(message,false)); }
        catch(SecurityException ignored) { }
    }
    static void delete(File f) {
        File[] children=f.listFiles(); if(children!=null) for(File child:children) delete(child);
        if(f.exists()) f.delete();
    }
    File jobDir() throws IOException {
        File root=new File(getApplicationContext().getFilesDir(),"jobs");
        File[] old=root.listFiles(); if(old!=null) for(File f:old) if(System.currentTimeMillis()-f.lastModified()>7L*86400000) delete(f);
        File dir=new File(root,getId().toString());
        if(!dir.isDirectory()&&!dir.mkdirs()) throw new IOException("作業領域を作成できません。空き容量を確認してください");
        dir.setLastModified(System.currentTimeMillis()); return dir;
    }
    String error(Exception e) {
        if(e instanceof SecurityException) return "フォルダへのアクセス権がありません。再選択してください";
        if(e instanceof net.lingala.zip4j.exception.ZipException) return "パスワードが違うか、ZIPが破損しています";
        if(e instanceof IOException || e instanceof IllegalStateException) return e.getMessage()==null?"読み書きに失敗しました":e.getMessage();
        return "処理に失敗しました。設定・空き容量・Google接続を確認してください";
    }
}
