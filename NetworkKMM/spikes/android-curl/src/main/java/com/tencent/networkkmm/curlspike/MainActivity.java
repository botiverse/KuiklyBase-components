package com.tencent.networkkmm.curlspike;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class MainActivity extends Activity {
    private static final String TAG = "NetworkKMMCurlSpike";

    static {
        System.loadLibrary("networkkmmcurlspike");
    }

    private static native String runProbe(String caInfoPath);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView resultView = new TextView(this);
        resultView.setText("Running Android curl spike...");
        resultView.setTextSize(16);
        resultView.setPadding(32, 64, 32, 32);
        setContentView(resultView);

        new Thread(() -> {
            try {
                File caFile = copyAsset("cacert.pem");
                String result = runProbe(caFile.getAbsolutePath());
                Log.i(TAG, "SLOCK_ANDROID_CURL_SPIKE " + result);
                runOnUiThread(() -> resultView.setText(result));
            } catch (Throwable error) {
                String result = "completed passed=false error=" + error;
                Log.e(TAG, "SLOCK_ANDROID_CURL_SPIKE " + result, error);
                runOnUiThread(() -> resultView.setText(result));
            }
        }, "android-curl-spike").start();
    }

    private File copyAsset(String name) throws Exception {
        File output = new File(getFilesDir(), name);
        try (InputStream input = getAssets().open(name);
             FileOutputStream stream = new FileOutputStream(output)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                stream.write(buffer, 0, count);
            }
        }
        return output;
    }
}
