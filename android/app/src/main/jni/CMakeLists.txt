cmake_minimum_required(VERSION 3.22)
project("opencyvis-adb")

set(CMAKE_CXX_STANDARD 17)

add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

# 16 KB page-size devices: NDK r27 still defaults to 4 KB alignment. max-page-size aligns the
# LOAD segments; common-page-size makes lld pad the end of RELRO to 16 KB too, which Android 17
# also checks (PAGE_SIZE_APP_COMPAT_FLAG_RELRO_NOT_ALIGNED).
add_link_options(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-Os -flto -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
else ()
    add_definitions(-DDEBUG)
endif ()

find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

add_library(adb SHARED
    adb_pairing.cpp)

target_link_libraries(adb log boringssl::crypto_static cxx::cxx)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET adb POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libadb.so")
endif ()
