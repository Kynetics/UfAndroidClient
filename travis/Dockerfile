FROM gradle:5.1.1-jdk8

USER root

ENV SDK_URL="https://dl.google.com/android/repository/sdk-tools-linux-3859397.zip" \
    ANDROID_HOME="/usr/local/android-sdk" \
    ANDROID_VERSION=28 \
    ANDROID_BUILD_TOOLS_VERSION=28.0.3 \
    ANDROID_HIDDEN_URL="https://github.com/anggrayudi/android-hidden-api/raw/master/android-28/android.jar" \
    ANDROID_HIDDEN_URL="https://github.com/anggrayudi/android-hidden-api/files/2709802/android.zip"\

ARG PROJECT_DIR="project"
ARG ANDROID_JAR="$ANDROID_HOME/platforms/android-28/android.jar"


# Download Android SDK
RUN mkdir "$ANDROID_HOME" .android \
    && cd "$ANDROID_HOME" \
    && curl -o sdk.zip $SDK_URL \
    && unzip sdk.zip \
    && rm sdk.zip \
    && mkdir "$ANDROID_HOME/licenses" || true \
    && echo "24333f8a63b6825ea9c5514f83c2829b004d1fee" > "$ANDROID_HOME/licenses/android-sdk-license" \
    && yes | $ANDROID_HOME/tools/bin/sdkmanager --licenses

#RUN apt-get update \
#    && apt-get install build-essential -y \
#    && apt-get install file -y \
#    && apt-get install apt-utils -y

# Install Android Build Tool and Libraries
RUN $ANDROID_HOME/tools/bin/sdkmanager --update
RUN $ANDROID_HOME/tools/bin/sdkmanager "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" \
    "platforms;android-${ANDROID_VERSION}" \
    "platform-tools" \
    && ls "$ANDROID_HOME/platforms/android-28" \
    && rm $ANDROID_JAR \
    && curl -L -o $ANDROID_JAR $ANDROID_HIDDEN_URL
