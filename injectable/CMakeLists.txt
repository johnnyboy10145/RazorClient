cmake_minimum_required(VERSION 3.21)

cmake_policy(SET CMP0091 NEW)
project(RazorClientNative LANGUAGES CXX RC)

if(NOT MSVC)
    message(FATAL_ERROR "The CMake build supports Visual Studio/MSVC only. Use build.ps1 -Toolchain LLVM for LLVM-MinGW.")
endif()

set(RAZORCLIENT_MINIMUM_WINDOWS_SDK "10.0.20348.0")
if(CMAKE_VS_WINDOWS_TARGET_PLATFORM_VERSION AND
   CMAKE_VS_WINDOWS_TARGET_PLATFORM_VERSION VERSION_LESS RAZORCLIENT_MINIMUM_WINDOWS_SDK)
    message(FATAL_ERROR
        "Windows SDK ${RAZORCLIENT_MINIMUM_WINDOWS_SDK} or newer is required; "
        "CMake selected ${CMAKE_VS_WINDOWS_TARGET_PLATFORM_VERSION}.")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")

set(RAZORCLIENT_GENERATED_DIR "${CMAKE_BINARY_DIR}/generated" CACHE PATH
    "Directory containing generated RazorClient headers and resources.rc")
set(RAZORCLIENT_OUTPUT_DIR "${CMAKE_BINARY_DIR}/dist" CACHE PATH
    "Directory for RazorClient.exe and razorclient-bootstrap.dll")
set(RAZORCLIENT_TOOL_OUTPUT_DIR "${CMAKE_BINARY_DIR}/tools" CACHE PATH
    "Directory for native build helpers, import libraries, and debug databases")
set(RAZORCLIENT_JNI_INCLUDE "" CACHE PATH "JDK JNI include directory")
set(RAZORCLIENT_JNI_PLATFORM_INCLUDE "" CACHE PATH "JDK Windows JNI include directory")
set(RAZORCLIENT_NLOHMANN_FALLBACK
    "${CMAKE_CURRENT_SOURCE_DIR}/tools/deps/nlohmann-json-3.11.3/single_include"
    CACHE PATH "Checksum-pinned nlohmann/json fallback include directory")

if(NOT RAZORCLIENT_JNI_INCLUDE)
    if(DEFINED ENV{JAVA_HOME})
        set(RAZORCLIENT_JNI_INCLUDE "$ENV{JAVA_HOME}/include" CACHE PATH
            "JDK JNI include directory" FORCE)
    elseif(DEFINED ENV{ProgramFiles})
        set(RAZORCLIENT_JNI_INCLUDE "$ENV{ProgramFiles}/Java/jdk-21.0.10/include" CACHE PATH
            "JDK JNI include directory" FORCE)
    endif()
endif()
if(NOT RAZORCLIENT_JNI_PLATFORM_INCLUDE AND RAZORCLIENT_JNI_INCLUDE)
    set(RAZORCLIENT_JNI_PLATFORM_INCLUDE "${RAZORCLIENT_JNI_INCLUDE}/win32" CACHE PATH
        "JDK Windows JNI include directory" FORCE)
endif()

if(NOT EXISTS "${RAZORCLIENT_JNI_INCLUDE}/jni.h" OR
   NOT EXISTS "${RAZORCLIENT_JNI_PLATFORM_INCLUDE}/jni_md.h")
    message(FATAL_ERROR "Valid RAZORCLIENT_JNI_INCLUDE and RAZORCLIENT_JNI_PLATFORM_INCLUDE paths are required.")
endif()

set(RAZORCLIENT_RESOURCE_SCRIPT "${RAZORCLIENT_GENERATED_DIR}/resources.rc")
set_source_files_properties("${RAZORCLIENT_RESOURCE_SCRIPT}" PROPERTIES GENERATED TRUE)

function(razorclient_configure_target target)
    target_compile_features(${target} PRIVATE cxx_std_17)
    target_compile_definitions(${target} PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
    target_compile_options(${target} PRIVATE
        "$<$<COMPILE_LANGUAGE:CXX>:/permissive-;/EHsc;/Gy;/Gw>")
    set_property(TARGET ${target} PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
    set_target_properties(${target} PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${RAZORCLIENT_OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_DEBUG "${RAZORCLIENT_OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY_RELEASE "${RAZORCLIENT_OUTPUT_DIR}"
        PDB_OUTPUT_DIRECTORY "${RAZORCLIENT_TOOL_OUTPUT_DIR}"
        PDB_OUTPUT_DIRECTORY_DEBUG "${RAZORCLIENT_TOOL_OUTPUT_DIR}"
        PDB_OUTPUT_DIRECTORY_RELEASE "${RAZORCLIENT_TOOL_OUTPUT_DIR}"
        COMPILE_PDB_OUTPUT_DIRECTORY "${RAZORCLIENT_TOOL_OUTPUT_DIR}"
        COMPILE_PDB_OUTPUT_DIRECTORY_DEBUG "${RAZORCLIENT_TOOL_OUTPUT_DIR}"
        COMPILE_PDB_OUTPUT_DIRECTORY_RELEASE "${RAZORCLIENT_TOOL_OUTPUT_DIR}")
endfunction()

add_executable(payload_encrypt native/tools/payload_encrypt.cpp)
razorclient_configure_target(payload_encrypt)
target_include_directories(payload_encrypt PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/native")
target_link_libraries(payload_encrypt PRIVATE kernel32 advapi32)
set_target_properties(payload_encrypt PROPERTIES
    OUTPUT_NAME "payload-encrypt"
    RUNTIME_OUTPUT_DIRECTORY "${RAZORCLIENT_TOOL_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${RAZORCLIENT_TOOL_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${RAZORCLIENT_TOOL_OUTPUT_DIR}")

add_library(razorclient-bootstrap SHARED native/bootstrap/bootstrap.cpp)
razorclient_configure_target(razorclient-bootstrap)
target_include_directories(razorclient-bootstrap PRIVATE
    "${RAZORCLIENT_JNI_INCLUDE}"
    "${RAZORCLIENT_JNI_PLATFORM_INCLUDE}"
    "${RAZORCLIENT_GENERATED_DIR}")
target_compile_definitions(razorclient-bootstrap PRIVATE
    $<$<CONFIG:Release>:RAZORCLIENT_RELEASE>)
target_link_libraries(razorclient-bootstrap PRIVATE
    kernel32 user32 gdi32 advapi32 opengl32 bcrypt)
set_target_properties(razorclient-bootstrap PROPERTIES
    PREFIX ""
    OUTPUT_NAME "razorclient-bootstrap"
    ARCHIVE_OUTPUT_DIRECTORY "${RAZORCLIENT_TOOL_OUTPUT_DIR}"
    ARCHIVE_OUTPUT_DIRECTORY_DEBUG "${RAZORCLIENT_TOOL_OUTPUT_DIR}"
    ARCHIVE_OUTPUT_DIRECTORY_RELEASE "${RAZORCLIENT_TOOL_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${RAZORCLIENT_TOOL_OUTPUT_DIR}")

find_package(nlohmann_json CONFIG QUIET)
if(NOT TARGET nlohmann_json::nlohmann_json AND
   NOT EXISTS "${RAZORCLIENT_NLOHMANN_FALLBACK}/nlohmann/json.hpp")
    message(FATAL_ERROR
        "nlohmann/json is unavailable. Install nlohmann-json:x64-windows-static with vcpkg "
        "or restore the checksum-pinned fallback header.")
endif()

add_executable(RazorClient WIN32
    native/launcher/main.cpp
    "${RAZORCLIENT_RESOURCE_SCRIPT}")
razorclient_configure_target(RazorClient)
target_include_directories(RazorClient PRIVATE
    "${RAZORCLIENT_GENERATED_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/native"
    "${CMAKE_CURRENT_SOURCE_DIR}/native/launcher")
if(TARGET nlohmann_json::nlohmann_json)
    target_link_libraries(RazorClient PRIVATE nlohmann_json::nlohmann_json)
else()
    target_include_directories(RazorClient PRIVATE "${RAZORCLIENT_NLOHMANN_FALLBACK}")
endif()
target_compile_definitions(RazorClient PRIVATE
    $<$<CONFIG:Release>:RAZORCLIENT_RELEASE>)
target_link_libraries(RazorClient PRIVATE
    user32 kernel32 gdi32 winhttp psapi crypt32 advapi32
    shell32 shlwapi bcrypt wintrust dwmapi)
