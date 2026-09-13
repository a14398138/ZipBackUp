package io.github.a14398138.zipbackup;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.AccountPicker;
import java.util.*;

public final class MainActivity extends ComponentActivity {
    private io.github.a14398138.zipbackup.Settings prefs;
    private LinearLayout page;
    private TextView status;
    private String pendingAccount="", restoreSource="", restoreTarget="";
    private boolean rendering;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener listener=(s,key)->{
        if("status".equals(key)&&status!=null) status.setText(s.getString("status","準備を始めましょう"));
    };
    private final ActivityResultLauncher<String> notifications=registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted->{});
    private final ActivityResultLauncher<Intent> accountPicker=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result->{
        if(result.getResultCode()!=RESULT_OK||result.getData()==null) return;
        String name=result.getData().getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
        if(name==null) return; pendingAccount=name;
        Identity.getAuthorizationClient(this).authorize(Auth.request(name)).addOnSuccessListener(this, auth->{
            try {
                if(auth.hasResolution()) this.authResolution.launch(new IntentSenderRequest.Builder(Objects.requireNonNull(auth.getPendingIntent()).getIntentSender()).build());
                else connected(auth);
            } catch(Exception e) { show("Google接続を開始できませんでした"); }
        }).addOnFailureListener(this,e->show("Google接続に失敗しました。READMEのGoogle Cloud設定と、APKの署名SHA-1を確認してください。"));
    });
    private final ActivityResultLauncher<IntentSenderRequest> authResolution=registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result->{
        if(result.getResultCode()!=RESULT_OK) return;
        try { connected(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.getData())); }
        catch(Exception e) { show("Googleの許可を確認できませんでした。もう一度接続してください"); }
    });
    private final ActivityResultLauncher<Intent> rootPicker=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result->{
        if(result.getResultCode()!=RESULT_OK||result.getData()==null||result.getData().getData()==null) return;
        Uri uri=result.getData().getData();
        if(!"com.android.externalstorage.documents".equals(uri.getAuthority())) { show("端末内またはSDカードのフォルダを選択してください"); return; }
        try {
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            prefs.root(uri.toString(),true); Jobs.changed(this); render();
        } catch(Exception e) { show("フォルダのアクセス権を保存できませんでした"); }
    });
    private final ActivityResultLauncher<Intent> zipPicker=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result->{
        if(result.getResultCode()!=RESULT_OK||result.getData()==null||result.getData().getData()==null) return;
        try {
            Uri uri=result.getData().getData(); getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            restoreSource=uri.toString();
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            this.destinationPicker.launch(pick);
        } catch(Exception e) { show("ZIPのアクセス権を保存できません。Driveから端末にダウンロードして選び直してください"); }
    });
    private final ActivityResultLauncher<Intent> destinationPicker=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result->{
        if(result.getResultCode()!=RESULT_OK||result.getData()==null||result.getData().getData()==null) return;
        try {
            Uri uri=result.getData().getData();
            if(!"com.android.externalstorage.documents".equals(uri.getAuthority())) { show("復元先は端末内かSDカードを選択してください"); return; }
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            restoreTarget=uri.toString(); restorePassword();
        } catch(Exception e) { show("復元先のアクセス権を保存できませんでした"); }
    });
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs=new io.github.a14398138.zipbackup.Settings(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(state!=null) { pendingAccount=state.getString("account",""); restoreSource=state.getString("source",""); restoreTarget=state.getString("target",""); }
        render(); Jobs.schedule(this);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("account",pendingAccount); state.putString("source",restoreSource); state.putString("target",restoreTarget);
    }
    @Override public void onStart() { super.onStart(); prefs.prefs.registerOnSharedPreferenceChangeListener(listener); }
    @Override public void onStop() { prefs.prefs.unregisterOnSharedPreferenceChangeListener(listener); super.onStop(); }
    private void connected(AuthorizationResult auth) {
        if(pendingAccount.isEmpty()||auth.getAccessToken()==null||!auth.getGrantedScopes().contains(Auth.SCOPE)) { show("Driveへのアクセス許可が必要です"); return; }
        prefs.prefs.edit().putString("account",pendingAccount).commit(); Jobs.changed(this); render(); show("Google Driveに接続しました");
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent,String value,int size,boolean bold) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(28,44,36));
        if(bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setPadding(0,dp(5),0,dp(7)); parent.addView(t); return t;
    }
    private LinearLayout card(String title) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18),dp(12),dp(18),dp(16));
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(20)); box.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(14); page.addView(box,lp); text(box,title,19,true); return box;
    }
    private void button(LinearLayout parent,String label,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); parent.addView(b,new LinearLayout.LayoutParams(-1,-2)); b.setOnClickListener(v->action.run());
    }
    private void toggle(LinearLayout parent,String label,boolean value,java.util.function.Consumer<Boolean> changed) {
        Switch sw=new Switch(this); sw.setText(label); sw.setTextSize(16); sw.setPadding(0,dp(12),0,dp(12)); sw.setChecked(value); parent.addView(sw);
        sw.setOnCheckedChangeListener((b,on)->{if(!rendering) changed.accept(on);});
    }
    private void render() {
        rendering=true;
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20),dp(20),dp(20),dp(24)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{ view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets; });
        setContentView(scroll);
        text(page,"ZipBackUp",32,true); text(page,"大切なファイルを、暗号化してDriveへ。",15,false);
        LinearLayout current=card("バックアップの状態"); status=text(current,prefs.prefs.getString("status","初期設定を完了してください"),16,false);
        button(current,"今すぐバックアップ",()->{if(ready()) { notificationPermission(); Jobs.backup(this); }});
        button(current,"実行中・待機中の処理を中止",()->{
            WorkManager wm=WorkManager.getInstance(this); wm.cancelUniqueWork(Jobs.BACKUP); wm.cancelUniqueWork(Jobs.RESTORE);
            prefs.status("中止を要求しました。自動バックアップの設定は維持します");
        });
        LinearLayout setup=card("1  保存先と暗号化");
        text(setup,prefs.account().isEmpty()?"Google Drive：未接続":prefs.account(),15,false);
        button(setup,"Google Driveに接続・アカウント変更",()->{
            try { accountPicker.launch(AccountPicker.newChooseAccountIntent(new AccountPicker.AccountChooserOptions.Builder().setAllowableAccountsTypes(Collections.singletonList("com.google")).build())); }
            catch(Exception e) { show("Google Play開発者サービスを有効にしてください"); }
        });
        button(setup,prefs.prefs.contains("password")?"暗号化パスワードを変更":"暗号化パスワードを設定",this::passwordDialog);
        text(setup,"パスワードは端末内で保護して保存します。紛失すると復元できません。ZIP内のファイル名は暗号化されないため見えます。",13,false);
        LinearLayout folders=card("2  対象フォルダ");
        text(folders,"写真・動画・文書など、端末内のフォルダを追加してください。他アプリ内部のデータは対象外です。",14,false);
        for(String root:prefs.roots()) {
            DocumentFile doc=DocumentFile.fromTreeUri(this,Uri.parse(root)); String name=doc==null?"アクセスできないフォルダ":doc.getName();
            button(folders,"削除："+name,()->new AlertDialog.Builder(this).setMessage("このフォルダをバックアップ対象から外しますか？ 元のファイルは残ります。")
                    .setPositiveButton("外す",(d,w)->{prefs.root(root,false); Jobs.changed(this); render();}).setNegativeButton("戻る",null).show());
        }
        button(folders,"フォルダを追加",()->rootPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)));
        LinearLayout schedule=card("3  自動バックアップ");
        toggle(schedule,"自動バックアップ",prefs.enabled(),on->{
            if(on&&!ready()) { render(); return; } prefs.prefs.edit().putBoolean("enabled",on).commit(); Jobs.schedule(this); if(on) notificationPermission();
        });
        toggle(schedule,"モバイルデータ通信を許可",prefs.mobile(),on->{prefs.prefs.edit().putBoolean("mobile",on).commit(); Jobs.changed(this); render();});
        text(schedule,prefs.mobile()?"Wi-Fi・モバイル通信で送信します。通信量は毎回のZIP全体分です。":"Wi-Fi（従量制ではない接続）で送信します。",13,false);
        toggle(schedule,"充電中のみ実行",prefs.charging(),on->{prefs.prefs.edit().putBoolean("charging",on).commit(); Jobs.changed(this);});
        text(schedule,"実行間隔",14,true);
        Spinner interval=new Spinner(this); String[] labels={"6時間ごと","12時間ごと","1日ごと","1週間ごと"}; int[] values={6,12,24,168};
        interval.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        for(int i=0;i<values.length;i++) if(values[i]==prefs.hours()) interval.setSelection(i);
        schedule.addView(interval); interval.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int pos,long id) { if(values[pos]!=prefs.hours()) { prefs.prefs.edit().putInt("hours",values[pos]).commit(); Jobs.schedule(MainActivity.this); } }
        });
        text(schedule,"手動実行にも通信・充電条件を適用します。実行時刻はAndroidの省電力制御により遅れることがあります。設定変更時は実行中のバックアップを中止します。",13,false);
        text(schedule,"最新7回分を保持。新しいZIPの検証後、古いZIPをDriveのゴミ箱へ移動します。ゴミ箱内のファイルも容量を使います。",13,false);
        LinearLayout restore=card("復元と履歴");
        text(restore,"DriveからZIPを端末へダウンロードして選択してください。指定先に新しい復元フォルダを作成します。",14,false);
        button(restore,"ZIPから復元",()->zipPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)));
        button(restore,"履歴を見る",()->show(prefs.prefs.getString("history","まだ履歴がありません")));
        button(restore,"端末のアプリ設定",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        button(restore,"初期設定ガイド",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/a14398138/ZipBackUp/blob/main/docs/SETUP.md"))));
        text(page,"AES-256 ZIP • 保存先：Google Drive / ZipBackUp\nバックアップ用ZIPの一時保存には端末の空き容量が必要です。",12,false);
        rendering=false;
    }
    private boolean ready() {
        if(prefs.account().isEmpty()||prefs.roots().isEmpty()||!prefs.prefs.contains("password")) { show("Google接続・パスワード・対象フォルダの3つを設定してください"); return false; }
        return true;
    }
    private void notificationPermission() { if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS); }
    private EditText password(LinearLayout parent,String hint) {
        EditText edit=new EditText(this); edit.setHint(hint); edit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); parent.addView(edit); return edit;
    }
    private void passwordDialog() {
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20),0,dp(20),0);
        EditText first=password(content,"16文字以上の長いパスワード"), second=password(content,"確認のため再入力");
        text(content,"別の安全な場所にも控えてください。変更前のZIPには以前のパスワードが必要です。",14,false);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("暗号化パスワード").setView(content).setPositiveButton("保存",null).setNegativeButton("戻る",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String a=first.getText().toString(), b=second.getText().toString();
            if(a.length()<16||!a.equals(b)) { first.setError("16文字以上で、確認欄と同じ値を入力してください"); return; }
            char[] chars=a.toCharArray();
            try { Vault.save(this,chars); Jobs.changed(this); first.setText(""); second.setText(""); dialog.dismiss(); render(); }
            catch(Exception e) { show("パスワードを保存できませんでした"); }
            finally { Arrays.fill(chars,'\0'); }
        })); dialog.show();
    }
    private void restorePassword() {
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20),0,dp(20),0);
        EditText pass=password(content,"このZIPを作成したときのパスワード");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("ZIPから復元").setView(content).setPositiveButton("復元",null).setNegativeButton("戻る",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            char[] chars=pass.getText().toString().toCharArray();
            try {
                if(chars.length==0) { pass.setError("パスワードを入力してください"); return; }
                OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(RestoreWorker.class).setInputData(new Data.Builder().putString("source",restoreSource).putString("target",restoreTarget).build()).build();
                prefs.prefs.edit().putString("restore-password-"+work.getId(),Vault.encrypt(chars)).commit();
                WorkManager.getInstance(this).enqueueUniqueWork(Jobs.RESTORE,ExistingWorkPolicy.REPLACE,work);
                notificationPermission(); pass.setText(""); dialog.dismiss(); prefs.status("復元を開始します");
            } catch(Exception e) { show("復元を開始できませんでした"); }
            finally { Arrays.fill(chars,'\0'); }
        })); dialog.show();
    }
    private void show(String message) { new AlertDialog.Builder(this).setMessage(message).setPositiveButton("閉じる",null).show(); }
}
