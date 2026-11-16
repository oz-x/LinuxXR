# Generates loader-info.c: offset of `pokedata_workaround` from `_start`
# in the proot loader (replaces src/loader/loader-info.awk).
# Inputs: NM, LOADER, OUTPUT
execute_process(COMMAND "${NM}" "${LOADER}" OUTPUT_VARIABLE syms RESULT_VARIABLE rc)
if(NOT rc EQUAL 0)
  message(FATAL_ERROR "llvm-nm failed on ${LOADER}")
endif()
string(REGEX MATCH "([0-9a-fA-F]+) [tT] pokedata_workaround" _ "${syms}")
set(pw "${CMAKE_MATCH_1}")
string(REGEX MATCH "([0-9a-fA-F]+) [tT] _start" _ "${syms}")
set(st "${CMAKE_MATCH_1}")
if(pw STREQUAL "" OR st STREQUAL "")
  message(FATAL_ERROR "pokedata_workaround/_start not found in loader")
endif()
math(EXPR off "0x${pw} - 0x${st}")
file(WRITE "${OUTPUT}" "#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=${off};\n")
