package com.yuv.display;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ConfigurationInfo;

public class GLES20Support {

    public static boolean detectOpenGLES20(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ConfigurationInfo info = am.getDeviceConfigurationInfo();
        Utils.LOGE("info.reqGlEsVersion = " + info.reqGlEsVersion);
        return (info.reqGlEsVersion >= 0x20000);
    }

    public static Dialog getNoSupportGLES20Dialog(final Activity activity) {
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setCancelable(false);
        b.setTitle("error");
        b.setMessage("No support OPENGL");
        b.setNegativeButton("Exit", new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                activity.finish();
            }
        });
        return b.create();
    }
}
