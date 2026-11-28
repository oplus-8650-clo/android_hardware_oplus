/*
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.oplus.touchwake;

import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_GROUP_KEY;

import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.settingslib.drawer.EntriesProvider;
import com.android.settingslib.drawer.EntryController;
import com.android.settingslib.drawer.ProviderSwitch;

import java.util.List;

public final class TouchWakeSettingsProvider extends EntriesProvider {
    static final String DOUBLE_TAP = "oplus_double_tap_to_wake";
    static final String SINGLE_TAP = "oplus_single_tap_to_wake";
    static final int DOUBLE_TAP_DEFAULT = 1;
    static final int SINGLE_TAP_DEFAULT = 0;

    private static final String DISPLAY_CATEGORY = "com.android.settings.category.ia.display";
    private static final String TOUCH_CONTROLS_GROUP = "oplus_touch_controls";
    // High touch polling rate takes the first slot through default ordering.
    private static final int DOUBLE_TAP_ORDER = 1;
    private static final int SINGLE_TAP_ORDER = 2;

    @Override
    protected List<EntryController> createEntryControllers() {
        return List.of(
                new TouchControlsGroup(),
                new WakeSwitch(DOUBLE_TAP, DOUBLE_TAP_DEFAULT, R.string.double_tap_title,
                        DOUBLE_TAP_ORDER),
                new WakeSwitch(SINGLE_TAP, SINGLE_TAP_DEFAULT, R.string.single_tap_title,
                        SINGLE_TAP_ORDER));
    }

    private static final class TouchControlsGroup extends EntryController {
        @Override
        public String getKey() {
            return TOUCH_CONTROLS_GROUP;
        }

        @Override
        protected MetaData getMetaData() {
            return new MetaData(DISPLAY_CATEGORY).setTitle(R.string.touch_controls_title);
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // ContentProvider.call does not enforce the manifest's provider permission.
        if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
            return null;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return super.call(method, arg, extras);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private final class WakeSwitch extends EntryController implements ProviderSwitch {
        private final String mKey;
        private final int mDefaultValue;
        private final int mTitle;
        private final int mOrder;

        WakeSwitch(String key, int defaultValue, int title, int order) {
            mKey = key;
            mDefaultValue = defaultValue;
            mTitle = title;
            mOrder = order;
        }

        @Override
        public String getKey() {
            return mKey;
        }

        @Override
        protected MetaData getMetaData() {
            return new MetaData(DISPLAY_CATEGORY) {
                @Override
                protected Bundle build() {
                    final Bundle bundle = super.build();
                    bundle.putString(META_DATA_PREFERENCE_GROUP_KEY, TOUCH_CONTROLS_GROUP);
                    return bundle;
                }
            }.setTitle(mTitle).setOrder(mOrder);
        }

        @Override
        public boolean isSwitchChecked() {
            return Settings.Secure.getInt(getContext().getContentResolver(), mKey,
                    mDefaultValue) != 0;
        }

        @Override
        public boolean onSwitchCheckedChanged(boolean checked) {
            return Settings.Secure.putInt(getContext().getContentResolver(), mKey, checked ? 1 : 0);
        }

        @Override
        public String getSwitchErrorMessage(boolean attemptedChecked) {
            return getContext().getString(R.string.write_failed);
        }
    }
}
