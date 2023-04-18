LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := Rethink

ifeq ($(TARGET_BUILD_VARIANT), userdebug)
    LOCAL_SRC_FILES := app/build/outputs/apk/fdroidHeadless/debug/app-fdroid-headless-debug.apk
else
    LOCAL_SRC_FILES := app/build/outputs/apk/fdroidHeadless/release/app-fdroid-headless-release.apk
endif
LOCAL_CERTIFICATE := testkey
LOCAL_PRODUCT_MODULE := true
LOCAL_PRIVILEGED_MODULE := true
LOCAL_MODULE_CLASS := APPS
LOCAL_DEX_PREOPT := false
LOCAL_REQUIRED_MODULES := privapp_whitelist_com.celzero.bravedns com.celzero.bravedns_whitelist
include $(BUILD_PREBUILT)