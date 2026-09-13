package io.github.a14398138.zipbackup;

import android.content.Context;
import android.net.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class Drive {
    private static final String API = "https://www.googleapis.com/drive/v3/files";
    final Context context; final String account; final Archive.Check check;
    Drive(Context c, String account, Archive.Check check) { context=c; this.account=account; this.check=check; }
    static final class ApiError extends IOException {
        final int code;
        ApiError(int code) { super(code == 403 ? "Driveへの書き込みが拒否されました。空き容量・権限・API設定を確認してください" : "Drive通信エラー（HTTP " + code + "）"); this.code=code; }
    }
    private HttpURLConnection open(String url, String method) throws IOException {
        check.run();
        URL endpoint = new URL(url);
        if (!"https".equals(endpoint.getProtocol()) || !"www.googleapis.com".equals(endpoint.getHost())) throw new IOException("不正なDrive URLです");
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) throw new IOException("インターネット接続を待っています");
        if (!NetworkPolicy.allows(new Settings(context).mobile(), true,
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)))
            throw new IOException("Wi-Fi接続を待っています");
        String token = Auth.token(context, account);
        check.run();
        // Bind this request to the checked network; a Wi-Fi loss cannot migrate its payload to cellular.
        HttpURLConnection conn = (HttpURLConnection) network.openConnection(endpoint);
        conn.setInstanceFollowRedirects(false); conn.setRequestMethod(method);
        conn.setConnectTimeout(30000); conn.setReadTimeout(60000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        return conn;
    }
    private JSONObject json(String url, String method, JSONObject body) throws Exception {
        HttpURLConnection conn = open(url, method);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true); conn.setFixedLengthStreamingMode(bytes.length);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try(OutputStream out=conn.getOutputStream()) { out.write(bytes); }
            }
            int code=conn.getResponseCode();
            if (code == 401) throw new Auth.Required("Driveへの再接続が必要です");
            if (code < 200 || code >= 300) throw new ApiError(code);
            if (code==204) return new JSONObject();
            try(InputStream in=conn.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                Archive.copy(in,out,check,8*1024*1024); return new JSONObject(out.toString("UTF-8"));
            }
        } finally { conn.disconnect(); }
    }
    static String encode(String s) throws UnsupportedEncodingException { return URLEncoder.encode(s,"UTF-8"); }
    static String quote(String s) { return s.replace("\\", "\\\\").replace("'", "\\'"); }
    List<JSONObject> list(String query) throws Exception {
        List<JSONObject> files=new ArrayList<>(); String page="";
        do {
            JSONObject result=json(API+"?q="+encode(query)+"&fields="+encode("nextPageToken,files(id,name,createdTime,size,md5Checksum,appProperties)")+
                    "&orderBy=createdTime%20desc&pageSize=1000"+(page.isEmpty()?"":"&pageToken="+encode(page)),"GET",null);
            JSONArray array=result.getJSONArray("files"); for(int i=0;i<array.length();i++) files.add(array.getJSONObject(i));
            page=result.optString("nextPageToken","");
        } while(!page.isEmpty());
        return files;
    }
    String folder() throws Exception {
        List<JSONObject> folders=list("trashed = false and mimeType = 'application/vnd.google-apps.folder' and appProperties has { key='zipbackup' and value='folder-v1' }");
        if (!folders.isEmpty()) return folders.get(folders.size()-1).getString("id");
        return json(API+"?fields=id","POST",new JSONObject().put("name","ZipBackUp").put("mimeType","application/vnd.google-apps.folder")
                .put("appProperties",new JSONObject().put("zipbackup","folder-v1"))).getString("id");
    }
    String upload(File file, String folder, String device, String run, String name) throws Exception {
        // A completed upload after process death is recognized by its unique run ID, avoiding duplicate backups.
        List<JSONObject> existing=list("trashed = false and '"+quote(folder)+"' in parents and appProperties has { key='run' and value='"+quote(run)+"' }");
        if (!existing.isEmpty()) return existing.get(0).getString("id");
        JSONObject metadata=new JSONObject().put("name",name).put("parents",new JSONArray().put(folder)).put("mimeType","application/zip")
                .put("appProperties",new JSONObject().put("zipbackup","archive-v1").put("device",device).put("run",run));
        String session;
        HttpURLConnection start=open("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id", "POST");
        try {
            byte[] bytes=metadata.toString().getBytes(StandardCharsets.UTF_8);
            start.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            start.setRequestProperty("X-Upload-Content-Type","application/zip");
            start.setRequestProperty("X-Upload-Content-Length",Long.toString(file.length()));
            start.setDoOutput(true); start.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=start.getOutputStream()) { out.write(bytes); }
            int code=start.getResponseCode(); if(code<200||code>=300) throw new ApiError(code);
            session=start.getHeaderField("Location");
            if(session==null) throw new IOException("Driveがアップロード先を返しませんでした");
        } finally { start.disconnect(); }
        long offset=0, size=file.length(); byte[] chunk=new byte[8*1024*1024];
        try(RandomAccessFile input=new RandomAccessFile(file,"r")) {
            while(offset<size) {
                check.run(); int n=(int)Math.min(chunk.length,size-offset); input.seek(offset); input.readFully(chunk,0,n);
                HttpURLConnection conn=open(session,"PUT");
                try {
                    conn.setDoOutput(true); conn.setFixedLengthStreamingMode(n);
                    conn.setRequestProperty("Content-Type","application/zip");
                    conn.setRequestProperty("Content-Range","bytes "+offset+"-"+(offset+n-1)+"/"+size);
                    try(OutputStream out=conn.getOutputStream()) {
                        for(int pos=0;pos<n;pos+=65536) { check.run(); out.write(chunk,pos,Math.min(65536,n-pos)); }
                    }
                    int code=conn.getResponseCode();
                    if(code==200||code==201) {
                        try(InputStream in=conn.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                            Archive.copy(in,out,check,1024*1024); return new JSONObject(out.toString("UTF-8")).getString("id");
                        }
                    }
                    if(code!=308) throw new ApiError(code);
                    String range=conn.getHeaderField("Range");
                    long next=range==null?0:Long.parseLong(range.substring(range.lastIndexOf('-')+1))+1;
                    if(next<=offset || next>offset+n) throw new IOException("Driveの受信位置が不正です");
                    offset=next;
                    new Settings(context).status("Driveへ送信中 "+(100*offset/size)+"%");
                } finally { conn.disconnect(); }
            }
        }
        throw new IOException("Driveアップロードが完了しませんでした");
    }
    void verify(String id, File file) throws Exception {
        JSONObject remote=json(API+"/"+encode(id)+"?fields=size,md5Checksum","GET",null);
        MessageDigest md=MessageDigest.getInstance("MD5"); // Transport integrity only; ZIP content uses AES authentication.
        try(InputStream in=new FileInputStream(file)) {
            byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1) { check.run(); md.update(b,0,n); }
        }
        StringBuilder hex=new StringBuilder(); for(byte b:md.digest()) hex.append(String.format(Locale.ROOT,"%02x",b&255));
        if(remote.getLong("size")!=file.length()||!hex.toString().equals(remote.optString("md5Checksum")))
            throw new IOException("送信後の整合性確認に失敗しました。古いバックアップは保持します");
        json(API+"/"+encode(id),"PATCH",new JSONObject().put("appProperties",new JSONObject().put("verified","yes")));
    }
    void prune(String folder,String device) throws Exception {
        List<JSONObject> files=list("trashed = false and '"+quote(folder)+"' in parents and appProperties has { key='zipbackup' and value='archive-v1' } and appProperties has { key='device' and value='"+quote(device)+"' } and appProperties has { key='verified' and value='yes' }");
        List<String> ids=new ArrayList<>(); for(JSONObject f:files) ids.add(f.getString("id"));
        for(String id:ArchiveRules.expired(ids,7)) json(API+"/"+encode(id),"PATCH",new JSONObject().put("trashed",true));
    }
}
