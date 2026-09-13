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
import java.io.File;
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

    private final ActivityResultLauncher<Intent> storagePermissionLauncher=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result->{
        render();
        if (hasAllFilesPermission()) {
            openFolderPicker();
        } else {
            show("すべてのファイルへのアクセス権限が許可されませんでした。設定から許可してください。");
        }
    });

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

    private final ActivityResultLauncher<Intent> authResolution=registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result->{
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
            if (hasAllFilesPermission()) {
                FilePickerDialog.show(this, folder -> {
                    restoreTarget = Uri.fromFile(folder).toString();
                    restorePassword();
                });
            } else {
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                this.destinationPicker.launch(pick);
            }
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
    @Override protected void onResume() { super.onResume(); render(); }

    private boolean hasAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void requestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                storagePermissionLauncher.launch(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                storagePermissionLauncher.launch(intent);
            }
        }
    }

    private void openFolderPicker() {
        if (hasAllFilesPermission()) {
            FilePickerDialog.show(this, folder -> {
                prefs.root(Uri.fromFile(folder).toString(), true);
                Jobs.changed(this);
                render();
            });
        } else {
            new AlertDialog.Builder(this)
                .setTitle("すべてのファイルへのアクセス")
                .setMessage("Downloadフォルダや写真・書類を自由に選択するには「すべてのファイルへのアクセス」権限が必要です。権限を許可しますか？\n（許可しない場合は従来の制限されたフォルダ選択を開きます）")
                .setPositiveButton("権限を設定", (d, w) -> requestAllFilesPermission())
                .setNeutralButton("従来の選択画面", (d, w) -> rootPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)))
                .setNegativeButton("キャンセル", null)
                .show();
        }
    }

    private void connected(AuthorizationResult auth) {
        String granted=auth.hasResolution()?"":pendingAccount;
        if(granted.isEmpty()) return;
        prefs.prefs.edit().putString("account",granted).commit();
        pendingAccount=""; Jobs.changed(this); render();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout card(String title) {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16),dp(16),dp(16),dp(16));
        GradientDrawable g=new GradientDrawable(); g.setColor(Color.WHITE); g.setCornerRadius(dp(12));
        g.setStroke(dp(1),Color.parseColor("#E0E0E0")); box.setBackground(g);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0,0,0,dp(12)); box.setLayoutParams(p);
        TextView h=new TextView(this); h.setText(title); h.setTextSize(16); h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(Color.parseColor("#212121")); h.setPadding(0,0,0,dp(8)); box.addView(h); page.addView(box);
        return box;
    }

    private void text(LinearLayout parent,String msg,int size,boolean bold) {
        TextView t=new TextView(this); t.setText(msg); t.setTextSize(size);
        t.setTextColor(Color.parseColor(bold?"#212121":"#616161"));
        if(bold) t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(0,0,0,dp(6)); parent.addView(t);
    }

    private Button button(LinearLayout parent,String label,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setTransformationMethod(null);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0,dp(4),0,dp(4)); b.setLayoutParams(p); b.setOnClickListener(v->action.run()); parent.addView(b); return b;
    }

    private void toggle(LinearLayout parent,String label,boolean checked,CompoundButton.OnCheckedChangeListener action) {
        Switch s=new Switch(this); s.setText(label); s.setChecked(checked); s.setTextSize(15);
        s.setPadding(0,dp(6),0,dp(6)); s.setOnCheckedChangeListener(action); parent.addView(s);
    }

    private void render() {
        if(rendering) return; rendering=true;
        ScrollView scroll=new ScrollView(this);
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16),dp(16),dp(16),dp(24)); page.setBackgroundColor(Color.parseColor("#F5F5F7"));
        scroll.addView(page); setContentView(scroll);
        TextView title=new TextView(this); title.setText("ZipBackUp"); title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextColor(Color.parseColor("#111111"));
        title.setPadding(0,dp(8),0,dp(4)); page.addView(title);
        status=new TextView(this); status.setText(prefs.prefs.getString("status","準備を始めましょう"));
        status.setTextSize(14); status.setTextColor(Color.parseColor("#1976D2")); status.setPadding(0,0,0,dp(12));
        page.addView(status);

        LinearLayout run=card("バックアップの実行");
        button(run,"今すぐバックアップ",()->{
            if(!ready()) return;
            WorkManager.getInstance(this).enqueueUniqueWork(Jobs.BACKUP,ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(BackupWorker.class).build());
            notificationPermission(); prefs.status("バックアップを開始します");
        });
        button(run,"実行中の処理を中止",()->{
            WorkManager.getInstance(this).cancelUniqueWork(Jobs.BACKUP);
            WorkManager.getInstance(this).cancelUniqueWork(Jobs.RESTORE);
            prefs.status("処理を中止しました");
        });

        LinearLayout setup=card("1  初期設定");
        text(setup,prefs.account().isEmpty()?"未接続":"接続中："+prefs.account(),14,true);
        button(setup,prefs.account().isEmpty()?"Google Driveに接続":"別のアカウントに変更",()->{
            accountPicker.launch(AccountPicker.newChooseAccountIntent(new AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(Collections.singletonList("com.google")).build()));
        });
        text(setup,"アプリ専用のフォルダ（ZipBackUp）を作成し、暗号化ZIPのみ保存します。他のファイルは読み取れません。",13,false);
        button(setup,prefs.prefs.contains("password")?"暗号化パスワードを変更":"暗号化パスワードを設定",this::passwordDialog);
        text(setup,"パスワードは端末内で保護して保存します。紛失すると復元できません。ZIP内のファイル名は暗号化されないため見えます。",13,false);

        LinearLayout folders=card("2  対象フォルダ");
        text(folders,"写真・動画・Download・文書など、端末内のフォルダを追加してください。他アプリ内部のデータは対象外です。",14,false);

        if (!hasAllFilesPermission()) {
            button(folders, "🔑 すべてのファイルへのアクセスを許可（推奨）", this::requestAllFilesPermission);
            text(folders, "許可すると、Downloadフォルダや端末内のあらゆるフォルダを自由に選択・バックアップできるようになります。", 12, false);
        }

        for(String root:prefs.roots()) {
            String name;
            if (root.startsWith("file://")) {
                File f = new File(Uri.parse(root).getPath());
                name = f.getName().isEmpty() ? f.getPath() : f.getName();
            } else {
                DocumentFile doc=DocumentFile.fromTreeUri(this,Uri.parse(root));
                name=doc==null?"アクセスできないフォルダ":doc.getName();
            }
            button(folders,"削除："+name,()->new AlertDialog.Builder(this).setMessage("このフォルダをバックアップ対象から外しますか？ 元のファイルは残ります。")
                    .setPositiveButton("外す",(d,w)->{prefs.root(root,false); Jobs.changed(this); render();}).setNegativeButton("戻る",null).show());
        }
        button(folders,"フォルダを追加",this::openFolderPicker);

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
