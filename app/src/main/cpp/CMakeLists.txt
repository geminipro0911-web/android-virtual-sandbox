cmake_minimum_required(VERSION 3.22.1)

project("virtual_sandbox_core")

add_library(
    virtual_core
    SHARED
    io_redirect.cpp
)

find_library(
    log-lib
    log
)

target_include_directories(
    virtual_core
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
    virtual_core
    ${log-lib}
)
