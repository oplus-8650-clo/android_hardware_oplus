package co.aospa.oplus.touchsettings;

import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_GROUP_KEY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;

import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.settingslib.drawer.EntriesProvider;
import com.android.settingslib.drawer.EntryController;
import com.android.settingslib.drawer.ProviderSwitch;

import java.util.ArrayList;
import java.util.List;

import vendor.oplus.hardware.touch.IOplusTouch;

public final class TouchSettingsProvider extends EntriesProvider {
    private static final String TAG = "OplusTouchSettings";
    private static final String DISPLAY_CATEGORY = "com.android.settings.category.ia.display";
    private static final String TOUCH_CONTROLS_GROUP = "oplus_touch_controls";
    private static final String TOUCH_SERVICE = IOplusTouch.DESCRIPTOR + "/default";
    private static final int PRIMARY_PANEL = 0;
    // OplusTouch game switch and glove mode node IDs.
    private static final int GAME_SWITCH = 26;
    private static final int GLOVE_MODE = 192;

    @Override
    protected List<EntryController> createEntryControllers() {
        return List.of(
                new TouchSwitch("oplus_high_touch_polling_rate", GAME_SWITCH,
                        R.string.polling_title, R.string.polling_description),
                new TouchSwitch("oplus_glove_mode", GLOVE_MODE,
                        R.string.glove_title, R.string.glove_description));
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // ContentProvider.call does not enforce the provider's read/write permission.
        if (Binder.getCallingUid() != Process.SYSTEM_UID
                || UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            return null;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final Bundle result = super.call(method, arg, extras);
            if (result != null && METHOD_GET_ENTRY_DATA.equals(method)) {
                final ArrayList<Bundle> entries = result.getParcelableArrayList(
                        EXTRA_ENTRY_DATA, Bundle.class);
                if (entries != null) {
                    final ArrayList<Bundle> available = new ArrayList<>(entries);
                    available.removeIf(entry -> {
                        final TouchSwitch control = (TouchSwitch) getController(
                                entry.getString(META_DATA_PREFERENCE_KEYHINT));
                        return control.readState() == null;
                    });
                    result.putParcelableArrayList(EXTRA_ENTRY_DATA, available);
                }
            }
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static IOplusTouch getTouch() {
        return IOplusTouch.Stub.asInterface(ServiceManager.checkService(TOUCH_SERVICE));
    }

    static Boolean parseState(String response) {
        if (response == null) {
            return null;
        }
        // Avalon appends high_frame_support and high_frame_value after the game mode.
        int separator = response.indexOf(',');
        String mode = separator < 0 ? response : response.substring(0, separator);
        return switch (mode.trim()) {
            case "0" -> false;
            case "1" -> true;
            default -> null;
        };
    }

    private final class TouchSwitch extends EntryController implements ProviderSwitch {
        private final String key;
        private final int node;
        private final int title;
        private final int summary;

        TouchSwitch(String key, int node, int title, int summary) {
            this.key = key;
            this.node = node;
            this.title = title;
            this.summary = summary;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        protected MetaData getMetaData() {
            if (node != GAME_SWITCH) {
                return new MetaData(DISPLAY_CATEGORY).setTitle(title).setSummary(summary);
            }
            return new MetaData(DISPLAY_CATEGORY) {
                @Override
                protected Bundle build() {
                    final Bundle bundle = super.build();
                    bundle.putString(META_DATA_PREFERENCE_GROUP_KEY, TOUCH_CONTROLS_GROUP);
                    return bundle;
                }
            }.setTitle(title).setSummary(summary);
        }

        private Boolean readState(IOplusTouch touch) throws RemoteException {
            if (touch == null || touch.isTouchNodeSupport(PRIMARY_PANEL, node) <= 0) {
                return null;
            }
            return parseState(touch.touchReadNodeFile(PRIMARY_PANEL, node));
        }

        synchronized Boolean readState() {
            try {
                return readState(getTouch());
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Could not read touch node " + node, e);
                return null;
            }
        }

        @Override
        public boolean isSwitchChecked() {
            return Boolean.TRUE.equals(readState());
        }

        @Override
        public synchronized boolean onSwitchCheckedChanged(boolean checked) {
            try {
                final IOplusTouch touch = getTouch();
                final Boolean current = readState(touch);
                if (current == null) {
                    return false;
                }
                if (current == checked) {
                    return true;
                }
                int result = touch.touchWriteNodeFile(PRIMARY_PANEL, node, checked ? "1" : "0");
                return result >= 0 && Boolean.valueOf(checked).equals(
                        parseState(touch.touchReadNodeFile(PRIMARY_PANEL, node)));
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Could not update touch node " + node, e);
                return false;
            }
        }

        @Override
        public String getSwitchErrorMessage(boolean attemptedChecked) {
            return getContext().getString(R.string.write_failed);
        }
    }
}
