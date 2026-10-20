cmake_minimum_required(VERSION 3.22.1)
project("velvet")

add_library(velvet SHARED native-lib.cpp)

target_link_libraries(
    velvet
    aaudio
    android
    log
)
