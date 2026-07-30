cmake_minimum_required(VERSION 3.22.1)
project(pegase_llm)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(LLAMA_SRC ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(pegase_llm SHARED llama_jni.cpp)

target_include_directories(pegase_llm PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include)

find_library(log-lib log)
target_link_libraries(pegase_llm llama ${log-lib})
