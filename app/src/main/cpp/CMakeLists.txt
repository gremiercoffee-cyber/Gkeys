cmake_minimum_required(VERSION 3.22.1)

project(gkeys_native)

set(LATINIME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/aosp-latinime)

file(GLOB_RECURSE LATINIME_CPP
    ${LATINIME_DIR}/*.cpp
)

list(FILTER LATINIME_CPP EXCLUDE REGEX ".*/tests/.*")

add_library(jni_latinime SHARED ${LATINIME_CPP})

target_include_directories(jni_latinime PRIVATE
    ${LATINIME_DIR}
    ${LATINIME_DIR}/src
)

target_compile_options(jni_latinime PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-unused-private-field
    -Wno-sign-compare
    -Wno-missing-field-initializers
)

find_library(log-lib log)
target_link_libraries(jni_latinime ${log-lib})
