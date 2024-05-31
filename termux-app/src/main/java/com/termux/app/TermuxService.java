package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A service holding a list of {@link TerminalSession} in {@link #mTerminalSessions} and background {@link TermuxAppShell}
 * in {@link #mTermuxTasks}, showing a foreground notification while running so that it is not terminated.
 * The user interacts with the session through {@link TermuxActivity}, but this service may outlive
 * the activity when the user or the system disposes of the activity. In that case the user may
 * restart {@link TermuxActivity} later to yet again access the sessions.
 * <p/>
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, {@link Service#startForeground(int, Notification)}.
 * <p/>
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * {@link #buildNotification()}.
 */
public final class TermuxService extends Service {

    public static final String ACTION_STOP_SERVICE = "com.termux.service.action.service_stop";
    public static final String ACTION_SERVICE_EXECUTE = "com.termux.service.action.service_execute";
    public static final String ACTION_ON_BOOT = "com.termux.app.ACTION_ON_BOOT";
    public static final String ACTION_WAKE_LOCK = "com.termux.service_wake_lock";
    public static final String ACTION_WAKE_UNLOCK = "com.termux.service_wake_unlock";

    public static final String TERMUX_EXECUTE_EXTRA_ARGUMENTS = "com.termux.execute.arguments";
    public static final String TERMUX_EXECUTE_WORKDIR = "com.termux.execute.workdir";

    public static final String NOTIFICATION_CHANNEL_ID = "com.termu.service.notification_channel";


    /**
     * This service is only bound from inside the same process and never uses IPC.
     */
    class LocalBinder extends Binder {
        public final TermuxService service = TermuxService.this;
    }

    private final IBinder mBinder = new LocalBinder();

    private final Handler mHandler = new Handler();


    /**
     * The full implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    private TermuxTerminalSessionActivityClient mTerminalSessionClient;

    /**
     * The foreground TermuxSessions which this service manages.
     * Note that this list is observed by an activity, like TermuxActivity.mTermuxSessionListViewController,
     * so any changes must be made on the UI thread and followed by a call to
     * {@link ArrayAdapter#notifyDataSetChanged()}.
     */
    public final List<TerminalSession> mTerminalSessions = new ArrayList<>();

    /**
     * The background TermuxTasks which this service manages.
     */
    public final List<TermuxAppShell> mTermuxTasks = new ArrayList<>();

    /**
     * The wake lock and wifi lock are always acquired and released together.
     */
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    boolean mWantsToStop = false;

    private static final String LOG_TAG = "TermuxService";

    @SuppressLint("Wakelock")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e("termux", "TermuxService.onStartCommand: " + intent);
        setupNotificationChannel();
        startForeground(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());

        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_STOP_SERVICE:
                    Log.d(LOG_TAG, "ACTION_STOP_SERVICE intent received");
                    actionStopService();
                    break;
                case ACTION_WAKE_LOCK:
                    Log.d(LOG_TAG, "ACTION_WAKE_LOCK intent received");
                    actionAcquireWakeLock();
                    break;
                case ACTION_WAKE_UNLOCK:
                    Log.d(LOG_TAG, "ACTION_WAKE_UNLOCK intent received");
                    actionReleaseWakeLock(true);
                    break;
                case ACTION_SERVICE_EXECUTE:
                    Log.d(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received");
                    actionServiceExecute(intent);
                    break;
                case ACTION_ON_BOOT:
                    runOnBoot();
                    break;
                default:
                    Log.e(LOG_TAG, "Invalid action: \"" + intent.getAction() + "\"");
                    break;
            }
        }

        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        actionReleaseWakeLock(false);
        if (!mWantsToStop) {
            killAllTermuxExecutionCommands();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(LOG_TAG, "onBind");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.v(LOG_TAG, "onUnbind");
        // Since we cannot rely on {@link TermuxActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mTerminalSessionClient != null) {
            unsetTermuxTerminalSessionClient();
        }
        return false;
    }

    private void requestStopService() {
        Log.v(LOG_TAG, "Requesting to stop service");
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf(-1);
    }

    /**
     * Process action to stop service.
     */
    private void actionStopService() {
        mWantsToStop = true;
        killAllTermuxExecutionCommands();
        requestStopService();
    }

    private synchronized void killAllTermuxExecutionCommands() {
        for (TerminalSession session : mTerminalSessions) {
            session.finishIfRunning();
        }
        for (TermuxAppShell session : mTermuxTasks) {
            session.kill();
        }
    }

    /**
     * Process action to acquire Power and Wi-Fi WakeLocks.
     */
    @SuppressLint({"WakelockTimeout", "BatteryLife"})
    private void actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Log.v(LOG_TAG, "Ignoring acquiring WakeLocks since they are already held");
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
        mWakeLock.acquire();

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, getClass().getName());
        mWifiLock.acquire();

        if (!TermuxPermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            TermuxPermissionUtils.requestDisableBatteryOptimizations(this);
        }

        updateNotification();
    }

    /**
     * Process action to release Power and Wi-Fi WakeLocks.
     */
    private void actionReleaseWakeLock(boolean updateNotification) {
        if (mWakeLock == null && mWifiLock == null) {
            Log.d(LOG_TAG, "Ignoring releasing WakeLocks since none are already held");
            return;
        }

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mWifiLock != null) {
            mWifiLock.release();
            mWifiLock = null;
        }

        if (updateNotification) {
            updateNotification();
        }
    }

    private void actionServiceExecute(Intent intent) {
        Uri executableUri = intent.getData();
        if (executableUri == null) {
            Log.e(TermuxConstants.LOG_TAG, "No executable URI");
            return;
        }

        var executable = new File(executableUri.getPath());
        var arguments = intent.getStringArrayExtra(TermuxService.TERMUX_EXECUTE_EXTRA_ARGUMENTS);

        executeBackgroundTask(executable, arguments);
    }

    private void executeBackgroundTask(File executable, String[] arguments) {
        var newTermuxTask = TermuxAppShell.execute(executable, arguments, this);
        if (newTermuxTask != null) {
            mTermuxTasks.add(newTermuxTask);
            updateNotification();
        }
    }

    public void onAppShellExited(@NonNull final TermuxAppShell termuxTask, int exitCode) {
        Log.i(LOG_TAG, "The onTermuxTaskExited() callback called for TermuxTask command");
        mTermuxTasks.remove(termuxTask);
        updateNotification();
    }

    /**
     * Create a {@link TerminalSession}.
     * Currently called by {@link TermuxTerminalSessionActivityClient#addNewSession(boolean, String)} to add a new {@link TerminalSession}.
     */
    public @NonNull TerminalSession createTermuxSession(String executablePath,
                                             String[] arguments,
                                             String stdin,
                                             String workingDirectory,
                                             boolean isFailSafe,
                                             String sessionName) {
        TerminalSessionClient sessionClient = new TerminalSessionClient() {
            @Override
            public void onTextChanged(@NonNull TerminalSession changedSession) {
                if (mTerminalSessionClient != null) {
                    mTerminalSessionClient.onTextChanged(changedSession);
                }
            }

            @Override
            public void onTitleChanged(@NonNull TerminalSession changedSession) {
                if (mTerminalSessionClient != null) {
                    mTerminalSessionClient.onTitleChanged(changedSession);
                }
            }

            @Override
            public void onSessionFinished(@NonNull TerminalSession finishedSession) {
                if (mTerminalSessionClient != null) {
                    mTerminalSessionClient.onSessionFinished(finishedSession);
                }
            }

            @Override
            public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
                if (mTerminalSessionClient != null) {
                    mTerminalSessionClient.onCopyTextToClipboard(session, text);
                }
            }

            @Override
            public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
                if (mTerminalSessionClient != null) {
                    mTerminalSessionClient.onPasteTextFromClipboard(session);
                }
            }

            @Override
            public void onBell(@NonNull TerminalSession session) {
                if (mTerminalSessionClient != null) {
                    mTerminalSessionClient.onBell(session);
                }
            }

            @Override
            public void onColorsChanged(@NonNull TerminalSession session) {
                if (mTerminalSessionClient != null) {
                    mTerminalSessionClient.onColorsChanged(session);
                }
            }

            @Override
            public void onTerminalCursorStateChange(boolean state) {
                if (mTerminalSessionClient != null) {
                    mTerminalSessionClient.onTerminalCursorStateChange(state);
                }
            }
        };

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        var newTermuxSession = TermuxShellUtils.executeTerminalSession(
            sessionClient,
            this,
            executablePath,
            isFailSafe
        );

        mTerminalSessions.add(newTermuxSession);

        if (mTerminalSessionClient != null) {
            mTerminalSessionClient.termuxSessionListNotifyUpdated();
        }

        updateNotification();

        return newTermuxSession;
    }

    /**
     * Remove a TermuxSession.
     */
    public synchronized int removeTermuxSession(TerminalSession sessionToRemove) {
        int index = getIndexOfSession(sessionToRemove);
        if (index >= 0) {
            var session = mTerminalSessions.get(index);
            // If process is still running, then ignore the call:
            if (!session.isRunning()) {
                this.onTermuxSessionExited(session);
            }
        }
        return index;
    }

    /**
     * Callback received when a {@link TerminalSession} finishes.
     */
    public void onTermuxSessionExited(@NonNull final TerminalSession termuxSession) {
        mTerminalSessions.remove(termuxSession);
        if (mTerminalSessionClient != null) {
            mTerminalSessionClient.termuxSessionListNotifyUpdated();
        }
        updateNotification();
    }

    public synchronized void setTermuxTerminalSessionClient(TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        mTerminalSessionClient = termuxTerminalSessionActivityClient;
    }

    private Notification buildNotification() {
        Resources res = getResources();

        // Set pending intent to be launched when notification is clicked
        Intent notificationIntent = new Intent(this, TermuxActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Set notification text
        int sessionCount = getTermuxSessionsSize();
        int taskCount = mTermuxTasks.size();
        String notificationText = sessionCount + " session" + (sessionCount == 1 ? "" : "s");
        if (taskCount > 0) {
            notificationText += ", " + taskCount + " task" + (taskCount == 1 ? "" : "s");
        }

        final boolean wakeLockHeld = mWakeLock != null;
        if (wakeLockHeld) notificationText += " (wake lock held)";

        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        // int priority = (wakeLockHeld) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW;
        // TODO: This has moved to the notification channel - see setupNotificationChannel(), where we currently
        //       always use NotificationManager.IMPORTANCE_HIGH

        Intent exitIntent = new Intent(this, TermuxService.class).setAction(TermuxService.ACTION_STOP_SERVICE);

        // Set Wakelock button actions
        String newWakeAction = wakeLockHeld ? TermuxService.ACTION_WAKE_UNLOCK : TermuxService.ACTION_WAKE_LOCK;
        Intent toggleWakeLockIntent = new Intent(this, TermuxService.class).setAction(newWakeAction);
        String actionTitle = res.getString(wakeLockHeld ? R.string.notification_action_wake_unlock : R.string.notification_action_wake_lock);
        int wakeLockIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;

        return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentText(notificationText)
            .setContentIntent(contentIntent)
            .setShowWhen(false)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setColor(0xFF607D8B)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(Icon.createWithResource("", android.R.drawable.ic_delete), res.getString(R.string.notification_action_exit), PendingIntent.getService(this, 0, exitIntent, PendingIntent.FLAG_IMMUTABLE)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource("", wakeLockIcon), actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, PendingIntent.FLAG_IMMUTABLE)).build())
            .build();
    }

    private void setupNotificationChannel() {
        var channel = new NotificationChannel(TermuxService.NOTIFICATION_CHANNEL_ID, "Termux", NotificationManager.IMPORTANCE_HIGH);
        var notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    /**
     * Update the shown foreground service notification after making any changes that affect it.
     */
    private synchronized void updateNotification() {
        if (mWakeLock == null && mTerminalSessions.isEmpty() && mTermuxTasks.isEmpty()) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            requestStopService();
        } else {
            // TODO: Remove notification to avoid having to have and request dangerous notification permission,
            // https://developer.android.com/develop/ui/views/notifications/notification-permission ?
            //((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());
        }
    }

    public synchronized boolean isTermuxSessionsEmpty() {
        return mTerminalSessions.isEmpty();
    }

    public synchronized int getTermuxSessionsSize() {
        return mTerminalSessions.size();
    }

    public synchronized List<TerminalSession> getTermuxSessions() {
        return mTerminalSessions;
    }

    @Nullable
    public synchronized TerminalSession getTermuxSession(int index) {
        if (index >= 0 && index < mTerminalSessions.size()) {
            return mTerminalSessions.get(index);
        }
        return null;
    }

    @Nullable
    public synchronized TerminalSession getTermuxSessionForTerminalSession(TerminalSession terminalSession) {
        if (terminalSession == null) return null;

        for (int i = 0; i < mTerminalSessions.size(); i++) {
            if (mTerminalSessions.get(i).equals(terminalSession))
                return mTerminalSessions.get(i);
        }

        return null;
    }

    public synchronized TerminalSession getLastTermuxSession() {
        return mTerminalSessions.isEmpty() ? null : mTerminalSessions.get(mTerminalSessions.size() - 1);
    }

    public synchronized int getIndexOfSession(TerminalSession terminalSession) {
        if (terminalSession == null) return -1;

        for (int i = 0; i < mTerminalSessions.size(); i++) {
            if (mTerminalSessions.get(i).equals(terminalSession))
                return i;
        }
        return -1;
    }

    public synchronized TerminalSession getTerminalSessionForHandle(String sessionHandle) {
        TerminalSession terminalSession;
        for (int i = 0, len = mTerminalSessions.size(); i < len; i++) {
            terminalSession = mTerminalSessions.get(i);
            if (terminalSession.mHandle.equals(sessionHandle))
                return terminalSession;
        }
        return null;
    }

    public boolean wantsToStop() {
        return mWantsToStop;
    }

    public void unsetTermuxTerminalSessionClient() {
        this.mTerminalSessionClient = null;
    }

    private void runOnBoot() {
        Log.e("termux", "RUN ON BOOT");
        for (var scriptDirSuffix : new String[]{"/.config/termux/boot", "/.termux/boot"}) {
            var bootScriptsPath = TermuxConstants.HOME_PATH + scriptDirSuffix;
            var bootScriptsDir = new File(bootScriptsPath);
            var files = bootScriptsDir.listFiles();
            Log.e("termux", "BOOT SCRIPTS in " + bootScriptsPath + ": " + (files == null ? null : Arrays.toString(files)));
            if (files == null) continue;
            Arrays.sort(files, Comparator.comparing(File::getName));
            Log.w(TermuxConstants.LOG_TAG, "Found " + files.length + " boot scripts in " + bootScriptsPath);
            for (var scriptFile : files) {
                if (scriptFile.canRead()) {
                    scriptFile.setReadable(true);
                }
                if (scriptFile.canExecute()) {
                    scriptFile.setExecutable(true);
                }

                executeBackgroundTask(scriptFile, new String[0]);
            }
        }
    }

}