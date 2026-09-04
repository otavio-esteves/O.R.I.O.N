FROM eclipse-temurin:17-jdk-jammy@sha256:400014962ad7224461f945bb1cc3d7d5a1927ce15b8245b72d9cedcda554cd2a

ARG ANDROID_COMMAND_LINE_TOOLS_VERSION=15859902
ARG ANDROID_COMMAND_LINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ANDROID_PLATFORM=37.0
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG ANDROID_NDK=29.0.14206865
ARG ANDROID_CMAKE=4.0.2

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/workspace/.gradle \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      git \
      libjson-validator-perl \
      perl \
      unzip \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" /tmp/android-cli \
    && curl --fail --location --retry 3 \
      "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS_VERSION}_latest.zip" \
      --output /tmp/android-cli/tools.zip \
    && echo "${ANDROID_COMMAND_LINE_TOOLS_SHA256}  /tmp/android-cli/tools.zip" | sha256sum --check - \
    && unzip -q /tmp/android-cli/tools.zip -d /tmp/android-cli/unpacked \
    && mv /tmp/android-cli/unpacked/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/android-cli

# Building this image constitutes acceptance of the Android SDK licenses by the builder.
# Android 17's base SDK package uses the fractional package ID android-37.0.
# Current command-line tools install that package through the Android CLI.
RUN yes | android sdk install \
      "platform-tools" \
      "platforms/android-${ANDROID_PLATFORM}" \
      "build-tools/${ANDROID_BUILD_TOOLS}" \
      "ndk/${ANDROID_NDK}" \
      "cmake/${ANDROID_CMAKE}" \
    && test -f "${ANDROID_HOME}/platforms/android-${ANDROID_PLATFORM}/android.jar" \
    && test -x "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/aapt2"

WORKDIR /workspace

ENTRYPOINT ["./gradlew"]
CMD ["help"]
