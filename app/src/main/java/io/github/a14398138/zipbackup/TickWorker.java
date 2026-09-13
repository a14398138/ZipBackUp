package io.github.a14398138.zipbackup;
import android.content.Context;
import androidx.work.*;
public final class TickWorker extends Worker {
    public TickWorker(Context c,WorkerParameters p) { super(c,p); }
    @Override public Result doWork() { if(new Settings(getApplicationContext()).enabled()) Jobs.backup(getApplicationContext()); return Result.success(); }
}
