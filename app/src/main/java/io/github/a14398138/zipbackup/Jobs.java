package io.github.a14398138.zipbackup;

import android.content.Context;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

final class Jobs {
    static final String BACKUP="backup", PERIODIC="schedule", RESTORE="restore";
    static Constraints constraints(Settings s) {
        return new Constraints.Builder().setRequiredNetworkType(s.mobile()?NetworkType.CONNECTED:NetworkType.UNMETERED)
                .setRequiresCharging(s.charging()).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build();
    }
    static void schedule(Context c) {
        Settings s=new Settings(c); WorkManager wm=WorkManager.getInstance(c);
        if(!s.enabled()) { wm.cancelUniqueWork(PERIODIC); return; }
        wm.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(TickWorker.class,s.hours(),TimeUnit.HOURS)
                        .setInitialDelay(s.hours(),TimeUnit.HOURS).setConstraints(constraints(s)).build());
    }
    static void backup(Context c) {
        WorkManager.getInstance(c).enqueueUniqueWork(BACKUP,ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(BackupWorker.class).setConstraints(constraints(new Settings(c)))
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build());
        new Settings(c).status("実行待ち：通信・充電・電池残量の条件が整うと開始します");
    }
    static void changed(Context c) {
        WorkManager.getInstance(c).cancelUniqueWork(BACKUP); schedule(c);
    }
}
