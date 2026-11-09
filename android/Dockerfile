# Builds the FitStream APK without a local Android SDK.
#   docker build -f android/Dockerfile -t fitstream-android android
#   docker run --rm -v "$PWD/android:/src" -v fitstream-gradle:/root/.gradle fitstream-android
# Output: android/app/build/outputs/apk/debug/app-debug.apk
FROM gradle:9.8.2-jdk21

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

RUN apt-get update && apt-get install -y --no-install-recommends unzip curl && rm -rf /var/lib/apt/lists/* \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && yes | sdkmanager --licenses > /dev/null \
 && sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" > /dev/null

WORKDIR /src
CMD ["gradle", "--no-daemon", "assembleDebug"]
