/*
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.oplus.touchwake;

import static co.aospa.oplus.touchwake.TouchWakeSettingsProvider.DOUBLE_TAP;
import static co.aospa.oplus.touchwake.TouchWakeSettingsProvider.DOUBLE_TAP_DEFAULT;
import static co.aospa.oplus.touchwake.TouchWakeSettingsProvider.SINGLE_TAP;
import static co.aospa.oplus.touchwake.TouchWakeSettingsProvider.SINGLE_TAP_DEFAULT;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.pocket.PocketManager;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

import vendor.oplus.hardware.touch.IOplusTouch;

public final class TouchWakeKeyHandler implements DeviceKeyHandler {
    private static final String TAG = "OplusTouchWake";
    private static final String TOUCH_SERVICE = IOplusTouch.DESCRIPTOR + "/default";
    private static final int PRIMARY_PANEL = 0;
    private static final int GESTURE_ENABLE_NODE = 1;
    private static final int COORDINATE_NODE = 2;
    private static final int GESTURE_MASK_NODE = 21;
    private static final int GESTURE_DOUBLE_TAP = 1;
    private static final int GESTURE_SINGLE_TAP = 16;
    private static final int DOUBLE_TAP_MASK = 1 << GESTURE_DOUBLE_TAP;
    private static final int SINGLE_TAP_MASK = 1 << GESTURE_SINGLE_TAP;
    private static final int TAP_MASK = DOUBLE_TAP_MASK | SINGLE_TAP_MASK;

    private final Context mContext;
    private final Handler mHandler;
    private final PowerManager mPowerManager;
    private int mEnabledTaps;

    public TouchWakeKeyHandler(Context context) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());

        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                applySettings();
            }
        };
        final ContentResolver resolver = context.getContentResolver();
        resolver.registerContentObserver(Settings.Secure.getUriFor(DOUBLE_TAP), false,
                observer, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.Secure.getUriFor(SINGLE_TAP), false,
                observer, UserHandle.USER_ALL);

        final IntentFilter filter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applySettings();
            }
        }, UserHandle.SYSTEM, filter, null, mHandler, Context.RECEIVER_NOT_EXPORTED);

        try {
            ServiceManager.registerForNotifications(TOUCH_SERVICE, new IServiceCallback.Stub() {
                @Override
                public void onRegistration(String name, IBinder binder) {
                    mHandler.post(TouchWakeKeyHandler.this::applySettings);
                }
            });
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Could not register for OplusTouch availability", e);
        }
        mHandler.post(this::applySettings);
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_F4) {
            return event;
        }
        final InputDevice device = event.getDevice();
        if (device == null || !"touchpanel".equals(device.getName())) {
            return event;
        }

        // The stock driver sends the same F4 press for both gestures.
        if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0
                && !event.isCanceled() && (event.getFlags() & KeyEvent.FLAG_FALLBACK) == 0) {
            mHandler.post(this::handleTap);
        }
        return null;
    }

    private void applySettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        final boolean doubleTap = Settings.Secure.getIntForUser(resolver, DOUBLE_TAP,
                DOUBLE_TAP_DEFAULT, UserHandle.USER_CURRENT) != 0;
        final boolean singleTap = Settings.Secure.getIntForUser(resolver, SINGLE_TAP,
                SINGLE_TAP_DEFAULT, UserHandle.USER_CURRENT) != 0;
        mEnabledTaps = (doubleTap ? DOUBLE_TAP_MASK : 0) | (singleTap ? SINGLE_TAP_MASK : 0);

        try {
            final IOplusTouch touch = getTouch();
            if (touch == null) {
                return;
            }
            final String response = touch.touchReadNodeFile(PRIMARY_PANEL, GESTURE_MASK_NODE);
            if (response == null) {
                return;
            }
            // The stock HAL reads hexadecimal and writes decimal masks.
            final String hex = response.trim();
            final int prefix = hex.startsWith("0x") || hex.startsWith("0X") ? 2 : 0;
            final int current = Integer.parseUnsignedInt(hex.substring(prefix), 16);
            final int desired = (current & ~TAP_MASK) | mEnabledTaps;
            if (mEnabledTaps != 0
                    && touch.touchWriteNodeFile(PRIMARY_PANEL, GESTURE_ENABLE_NODE, "1") < 0) {
                Log.w(TAG, "Could not enable hardware gestures");
                return;
            }
            if ((mEnabledTaps != 0 || desired != current)
                    && touch.touchWriteNodeFile(PRIMARY_PANEL, GESTURE_MASK_NODE,
                            Integer.toUnsignedString(desired)) < 0) {
                Log.w(TAG, "Could not update the touchscreen gesture mask");
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Could not apply the touch wake settings", e);
        }
    }

    private static IOplusTouch getTouch() {
        return IOplusTouch.Stub.asInterface(ServiceManager.checkService(TOUCH_SERVICE));
    }

    private void handleTap() {
        if (mEnabledTaps == 0 || mPowerManager.isInteractive()) {
            return;
        }
        final int gestureMask = switch (readGesture()) {
            case GESTURE_DOUBLE_TAP -> DOUBLE_TAP_MASK;
            case GESTURE_SINGLE_TAP -> SINGLE_TAP_MASK;
            default -> 0;
        };
        if ((mEnabledTaps & gestureMask) != 0) {
            wakeDisplay();
        }
    }

    private void wakeDisplay() {
        if (mPowerManager.isInteractive()) {
            return;
        }
        final TelecomManager telecom = mContext.getSystemService(TelecomManager.class);
        final PocketManager pocket = mContext.getSystemService(PocketManager.class);
        if ((telecom != null && (telecom.isInCall() || telecom.isRinging()))
                || (pocket != null && pocket.isDeviceInPocket())) {
            return;
        }
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_TAP, TAG);
    }

    private int readGesture() {
        try {
            final IOplusTouch touch = getTouch();
            if (touch == null) {
                return 0;
            }
            final String coordinate = touch.touchReadNodeFile(PRIMARY_PANEL, COORDINATE_NODE);
            final int separator = coordinate == null ? -1 : coordinate.indexOf(',');
            if (separator < 0) {
                return 0;
            }

            // The coordinate record starts with the gesture ID, followed by x:y pairs.
            return Integer.parseInt(coordinate.substring(0, separator).trim());
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Could not read the touchscreen gesture", e);
            return 0;
        }
    }
}
