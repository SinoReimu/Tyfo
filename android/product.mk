# Include in device makefile to add Tyfo to system image
# Add to device/<vendor>/<device>/device.mk:
#   $(call inherit-product, path/to/tyfo/android/product.mk)

LOCAL_PATH := $(call my-dir)

PRODUCT_PACKAGES += Tyfo

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/privapp-permissions-tyfo.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-tyfo.xml
