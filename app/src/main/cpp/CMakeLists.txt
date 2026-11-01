cmake_minimum_required(VERSION 3.22.1)
project("rikkihub_host")
# Attach-only replacement for Termux's libtermux.so (no fork/exec): terminals attach to workspace PTYs.
add_library(termux SHARED termux_remote.cpp)
target_link_libraries(termux log)
