package io.github.a14398138.zipbackup;

import android.accounts.Account;
import android.content.Context;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

final class Auth {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static AuthorizationRequest request(String account) {
        return AuthorizationRequest.builder().setAccount(new Account(account, "com.google"))
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE))).build();
    }
    static String token(Context c, String account) throws IOException {
        if (account.isEmpty()) throw new Required("Google Driveに接続してください");
        try {
            AuthorizationResult result = Tasks.await(Identity.getAuthorizationClient(c).authorize(request(account)), 60, TimeUnit.SECONDS);
            if (result.hasResolution() || result.getAccessToken() == null || !result.getGrantedScopes().contains(SCOPE))
                throw new Required("Google Driveへの再接続が必要です。アプリを開いてください");
            return result.getAccessToken();
        } catch (Required e) { throw e; }
        catch (Exception e) { throw new Required("Google認証を確認してください。Google Cloudの登録とDriveへの再接続が必要な場合があります"); }
    }
    static final class Required extends IOException { Required(String text) { super(text); } }
}
