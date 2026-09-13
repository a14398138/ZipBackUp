package io.github.a14398138.zipbackup;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MainActivityTest {
    private Switch mobile(View v) {
        if(v instanceof Switch && "モバイルデータ通信を許可".contentEquals(((Switch)v).getText())) return (Switch)v;
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) {
            Switch found=mobile(((ViewGroup)v).getChildAt(i)); if(found!=null) return found;
        }
        return null;
    }
    @Test public void cellularPreferenceSurvivesRecreation() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->{
                Switch setting=mobile(activity.getWindow().getDecorView()); assertNotNull(setting);
                assertFalse(new Settings(activity).mobile()); setting.setChecked(true);
                assertTrue(new Settings(activity).mobile());
            });
            scenario.recreate();
            scenario.onActivity(activity->{
                Switch setting=mobile(activity.getWindow().getDecorView()); assertNotNull(setting); assertTrue(setting.isChecked());
                setting.setChecked(false); assertFalse(new Settings(activity).mobile());
            });
        }
    }
}
