package com.taskflow.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

public class NotificationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String title  = intent.getStringExtra("title");
        String body   = intent.getStringExtra("body");
        int    notifId = intent.getIntExtra("notifId", 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "taskflow_channel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title != null ? title : "Task Reminder")
            .setContentText(body  != null ? body  : "You have a task due soon")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true);

        NotificationManager nm =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifId, builder.build());
    }
}
