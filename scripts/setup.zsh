#!/bin/zsh
set -euo pipefail

project_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$project_sdk" && -d "$HOME/Library/Android/sdk" ]]; then
    project_sdk=$HOME/Library/Android/sdk
fi
if [[ -z "$project_sdk" ]]; then
    print -u2 "Set ANDROID_HOME to the Android SDK directory."
    exit 1
fi

sdkmanager=$(command -v sdkmanager || true)
if [[ -z "$sdkmanager" ]]; then
    sdkmanager=$project_sdk/cmdline-tools/latest/bin/sdkmanager
fi
if [[ ! -x "$sdkmanager" ]]; then
    sdkmanager=${project_sdk}/cmdline-tools/*/bin/sdkmanager(N[1])
fi
if [[ ! -x "$sdkmanager" ]]; then
    print -u2 "Install Android SDK Command-line Tools through Android Studio."
    exit 1
fi

"$sdkmanager" --sdk_root="$project_sdk" 'platforms;android-37.0' 'build-tools;37.0.0' 'platform-tools' < /dev/null
print -r -- "sdk.dir=$project_sdk" > local.properties
