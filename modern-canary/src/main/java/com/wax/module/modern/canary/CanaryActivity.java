package com.wax.module.modern.canary;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Independent test UI. It never enables WA X's legacy features or modifies WhatsApp data.
 * Modern feature readiness requires a separately verified in-target heartbeat.
 */
public final class CanaryActivity extends Activity implements CanaryApplication.StateListener {
    private TextView state;
    private Switch monitor;
    private Switch customTime;
    private CanaryApplication application;
    private boolean rendering;

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        application = (CanaryApplication) getApplication();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setGravity(Gravity.TOP);

        TextView heading = new TextView(this);
        heading.setText("WA X — Modern API 102");
        heading.setTextSize(24f);
        root.addView(heading);

        TextView warning = new TextView(this);
        warning.setText("Experimental API102 module. Separate from production WA X. "
                + "CustomTime is opt-in and experimental; the rest of the legacy features remain disabled.");
        warning.setPadding(0, dp(14), 0, dp(24));
        root.addView(warning);

        state = new TextView(this);
        state.setTextSize(16f);
        state.setText("Connecting to framework…");
        root.addView(state);

        monitor = new Switch(this);
        monitor.setText("Write remote diagnostic preference (API 102)");
        monitor.setPadding(0, dp(22), 0, dp(12));
        monitor.setEnabled(false);
        monitor.setOnCheckedChangeListener((button, checked) -> {
            if (!rendering) application.setMonitoringEnabled(checked);
        });
        root.addView(monitor);

        customTime = new Switch(this);
        customTime.setText("EXPERIMENTAL: Enable CustomTime display (restart WhatsApp after change)");
        customTime.setPadding(0, dp(10), 0, dp(14));
        customTime.setEnabled(false);
        customTime.setOnCheckedChangeListener((button, checked) -> {
            if (!rendering) application.setCustomTimeEnabled(checked);
        });
        root.addView(customTime);
        Button refresh = new Button(this);
        refresh.setText("Refresh framework state");
        refresh.setOnClickListener(v -> application.refresh());
        root.addView(refresh);

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        application.addListener(this);
        application.refresh();
    }

    @Override
    protected void onStop() {
        application.removeListener(this);
        super.onStop();
    }

    @Override
    public void onStateChanged(CanaryApplication.Status status) {
        rendering = true;
        state.setText(status.message);
        monitor.setEnabled(status.connected);
        monitor.setChecked(status.monitoringEnabled);
        customTime.setEnabled(status.connected);
        customTime.setChecked(status.customTimeEnabled);
        rendering = false;
    }
}
