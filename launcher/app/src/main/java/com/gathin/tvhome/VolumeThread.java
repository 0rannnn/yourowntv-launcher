package com.gathin.tvhome;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

/**
 * One background thread for the volume work (VolumeMeter's checks, VolumeHold's and VolumeKeys' steps). Every one of
 * those is a call into the system's audio service, which can take milliseconds (longer on a TV whose volume goes over
 * HDMI-CEC); on the main thread, the one that draws Home and scrolls its apps, they made scrolling stutter.
 */
final class VolumeThread {
    private static Handler handler;

    private VolumeThread() {}

    static synchronized Handler get() {
        if (handler == null) {
            HandlerThread thread = new HandlerThread("volume", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }
}
