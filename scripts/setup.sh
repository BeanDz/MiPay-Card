#!/bin/sh
set -eu

project_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$project_sdk" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    project_sdk=$HOME/Library/Android/sdk
fi
if [ -z "$project_sdk" ]; then
    echo "Set ANDROID_HOME to the Android SDK directory." >&2
    exit 1
fi

sdkmanager=$(command -v sdkmanager || true)
if [ -z "$sdkmanager" ]; then
    sdkmanager=$project_sdk/cmdline-tools/latest/bin/sdkmanager
fi
if [ ! -x "$sdkmanager" ]; then
    for candidate in "$project_sdk"/cmdline-tools/*/bin/sdkmanager; do
        if [ -x "$candidate" ]; then
            sdkmanager=$candidate
            break
        fi
    done
fi
if [ ! -x "$sdkmanager" ]; then
    echo "Install Android SDK Command-line Tools through Android Studio." >&2
    exit 1
fi

"$sdkmanager" --sdk_root="$project_sdk" 'platforms;android-37.0' 'build-tools;37.0.0' 'platform-tools' < /dev/null
printf 'sdk.dir=%s\n' "$project_sdk" > local.properties
