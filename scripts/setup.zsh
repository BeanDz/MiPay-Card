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

android_cli=$project_sdk/cmdline-tools/latest/bin/android
if [[ ! -x "$android_cli" ]]; then
    print -u2 "Install Android SDK Command-line Tools (latest) through Android Studio."
    exit 1
fi

for package in 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'; do
    "$android_cli" --sdk="$project_sdk" sdk install "$package" < /dev/null
done
print -r -- "sdk.dir=$project_sdk" > local.properties
