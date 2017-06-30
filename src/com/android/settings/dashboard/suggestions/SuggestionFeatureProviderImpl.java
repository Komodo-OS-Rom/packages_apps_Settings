/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.dashboard.suggestions;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.Settings.AmbientDisplayPickupSuggestionActivity;
import com.android.settings.Settings.AmbientDisplaySuggestionActivity;
import com.android.settings.Settings.DoubleTapPowerSuggestionActivity;
import com.android.settings.Settings.DoubleTwistSuggestionActivity;
import com.android.settings.Settings.NightDisplaySuggestionActivity;
import com.android.settings.Settings.SwipeToNotificationSuggestionActivity;
import com.android.settings.core.instrumentation.MetricsFeatureProvider;
import com.android.settings.gestures.DoubleTapPowerPreferenceController;
import com.android.settings.gestures.DoubleTapScreenPreferenceController;
import com.android.settings.gestures.DoubleTwistPreferenceController;
import com.android.settings.gestures.PickupGesturePreferenceController;
import com.android.settings.gestures.SwipeToNotificationPreferenceController;
import com.android.settings.overlay.FeatureFactory;
import com.android.settings.support.NewDeviceIntroSuggestionActivity;
import com.android.settingslib.drawer.Tile;
import com.android.settingslib.suggestions.SuggestionParser;

import java.util.List;

public class SuggestionFeatureProviderImpl implements SuggestionFeatureProvider {

    private static final String TAG = "SuggestionFeature";
    private static final int EXCLUSIVE_SUGGESTION_MAX_COUNT = 3;

    private static final String SHARED_PREF_FILENAME = "suggestions";

    // Suggestion category name and expiration threshold for first impression type. Needs to keep
    // in sync with suggestion_ordering.xml
    private static final String CATEGORY_FIRST_IMPRESSION =
            "com.android.settings.suggested.category.FIRST_IMPRESSION";
    private static final long FIRST_IMPRESSION_EXPIRE_DAY_IN_MILLIS = 14 * DateUtils.DAY_IN_MILLIS;

    private final SuggestionRanker mSuggestionRanker;
    private final MetricsFeatureProvider mMetricsFeatureProvider;
    private final AmbientDisplayConfiguration mAmbientDisplayConfig;

    @Override
    public boolean isSmartSuggestionEnabled(Context context) {
        return false;
    }

    @Override
    public boolean isSuggestionCompleted(Context context, @NonNull ComponentName component) {
        final String className = component.getClassName();
        if (className.equals(NightDisplaySuggestionActivity.class.getName())) {
            return hasUsedNightDisplay(context);
        }
        if (className.equals(NewDeviceIntroSuggestionActivity.class.getName())) {
            return NewDeviceIntroSuggestionActivity.isSuggestionComplete(context);
        } else if (className.equals(DoubleTapPowerSuggestionActivity.class.getName())) {
            return DoubleTapPowerPreferenceController
                    .isSuggestionComplete(context, getSharedPrefs(context));
        } else if (className.equals(DoubleTwistSuggestionActivity.class.getName())) {
            return DoubleTwistPreferenceController
                    .isSuggestionComplete(context, getSharedPrefs(context));
        } else if (className.equals(AmbientDisplaySuggestionActivity.class.getName())) {
            return DoubleTapScreenPreferenceController
                    .isSuggestionComplete(context, getSharedPrefs(context));
        } else if (className.equals(AmbientDisplayPickupSuggestionActivity.class.getName())) {
            return PickupGesturePreferenceController
                    .isSuggestionComplete(context, getSharedPrefs(context));
        } else if (className.equals(SwipeToNotificationSuggestionActivity.class.getName())) {
            return SwipeToNotificationPreferenceController
                    .isSuggestionComplete(context, getSharedPrefs(context));
        }
        return false;
    }

    @Override
    public SharedPreferences getSharedPrefs(Context context) {
        return context.getSharedPreferences(SHARED_PREF_FILENAME, Context.MODE_PRIVATE);
    }

    public SuggestionFeatureProviderImpl(Context context) {
        final Context appContext = context.getApplicationContext();
        mSuggestionRanker = new SuggestionRanker(
                new SuggestionFeaturizer(new EventStore(appContext)));
        mMetricsFeatureProvider = FeatureFactory.getFactory(appContext)
                .getMetricsFeatureProvider();
        mAmbientDisplayConfig = new AmbientDisplayConfiguration(appContext);
    }

    @Override
    public void rankSuggestions(final List<Tile> suggestions, List<String> suggestionIds) {
        mSuggestionRanker.rankSuggestions(suggestions, suggestionIds);
    }

    @Override
    public void filterExclusiveSuggestions(List<Tile> suggestions) {
        if (suggestions == null) {
            return;
        }
        for (int i = suggestions.size() - 1; i >= EXCLUSIVE_SUGGESTION_MAX_COUNT; i--) {
            Log.d(TAG, "Removing exclusive suggestion");
            suggestions.remove(i);
        }
    }

    @Override
    public void dismissSuggestion(Context context, SuggestionParser parser, Tile suggestion) {
        if (parser == null || suggestion == null || context == null) {
            return;
        }
        mMetricsFeatureProvider.action(
                context, MetricsProto.MetricsEvent.ACTION_SETTINGS_DISMISS_SUGGESTION,
                getSuggestionIdentifier(context, suggestion));

        boolean isSmartSuggestionEnabled = isSmartSuggestionEnabled(context);
        if (isSmartSuggestionEnabled) {
            // Disable smart suggestion if we are still showing first impression suggestions.
            isSmartSuggestionEnabled = !isShowingFirstImpressionSuggestion(context);
        }
        if (!parser.dismissSuggestion(suggestion, isSmartSuggestionEnabled)) {
            return;
        }
        context.getPackageManager().setComponentEnabledSetting(
                suggestion.intent.getComponent(),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private boolean isShowingFirstImpressionSuggestion(Context context) {
        final String keySetupTime = CATEGORY_FIRST_IMPRESSION + SuggestionParser.SETUP_TIME;
        final long currentTime = System.currentTimeMillis();
        final SharedPreferences sharedPrefs = getSharedPrefs(context);
        if (!sharedPrefs.contains(keySetupTime)) {
            return true;
        }
        final long setupTime = sharedPrefs.getLong(keySetupTime, 0);
        final long elapsedTime = currentTime - setupTime;
        Log.d(TAG, "Day " + elapsedTime / DateUtils.DAY_IN_MILLIS + " for first impression");
        return elapsedTime <= FIRST_IMPRESSION_EXPIRE_DAY_IN_MILLIS;
    }

    @Override
    public String getSuggestionIdentifier(Context context, Tile suggestion) {
        if (suggestion.intent == null || suggestion.intent.getComponent() == null
                || context == null) {
            return "unknown_suggestion";
        }
        String packageName = suggestion.intent.getComponent().getPackageName();
        if (packageName.equals(context.getPackageName())) {
            // Since Settings provides several suggestions, fill in the class instead of the
            // package for these.
            packageName = suggestion.intent.getComponent().getClassName();
        }
        return packageName;
    }

    @VisibleForTesting
    boolean hasUsedNightDisplay(Context context) {
        final ContentResolver cr = context.getContentResolver();
        final long lastActivatedTimeMillis = Secure.getLong(cr,
                Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME, -1);
        return lastActivatedTimeMillis > 0;
    }
}
