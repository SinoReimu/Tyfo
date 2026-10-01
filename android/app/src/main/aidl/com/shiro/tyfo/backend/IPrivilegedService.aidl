package com.shiro.tyfo.backend;

import android.os.Bundle;
import android.os.SharedMemory;
import android.view.Surface;

interface IPrivilegedService {
    int getServiceUid();

    boolean injectMotionEvent(in byte[] parceledEvent, int displayId, int mode);
    boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState, int displayId, int mode);

    int createVirtualDisplay(String name, int width, int height, int dpi, int flags);
    void releaseVirtualDisplay();
    void setVirtualDisplaySurface(in Surface surface);

    SharedMemory captureScreen(int displayId, int maxWidth, int quality);

    int getTopTaskIdOnDisplay(int displayId, String callerPackage);
    boolean moveTaskToDisplay(int taskId, int targetDisplayId);

    void setDisplayImePolicy(int displayId, int policy);

    int probeCapabilities();

    void destroy();

    boolean startActivityOnDisplay(String intentUri, int displayId);

    /** Force-stop a package (shell uid). Used to dismiss split-screen by killing the
        adjacent Settings pane after ADB pairing completes. */
    void forceStopPackage(String packageName);

    /** Keep the VD's own power group awake (while a task runs) even when the phone screen is off. */
    void setVirtualDisplayAwake(boolean awake);

    /** Install (non-null) or clear (null) the system-wide activity controller. The binder must
        speak the android.app.IActivityController wire protocol. */
    boolean setActivityController(IBinder controller);

    /** Top task on a display that isn't callerPackage's: {taskId, basePackage, topPackage}, or null. */
    Bundle getTopTaskOnDisplay(int displayId, String callerPackage);

    /** Remove every non-home task on a display (same as swiping them away in Recents); returns their ids. */
    int[] removeTasksOnDisplay(int displayId);
}
