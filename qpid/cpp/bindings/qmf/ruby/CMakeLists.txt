#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

##------------------------------------------------------
## Use Swig to generate a literal binding to the C++ API
##------------------------------------------------------
set_source_files_properties(${CMAKE_CURRENT_SOURCE_DIR}/ruby.i PROPERTIES CPLUSPLUS ON)
set_source_files_properties(${CMAKE_CURRENT_SOURCE_DIR}/ruby.i PROPERTIES SWIG_FLAGS "-I${qpid-cpp_SOURCE_DIR}/include")

swig_add_module(qmfengine_ruby ruby ${CMAKE_CURRENT_SOURCE_DIR}/ruby.i)
swig_link_libraries(qmfengine_ruby qmf qmfconsole ${RUBY_LIBRARY})

set_source_files_properties(${swig_generated_file_fullname} PROPERTIES COMPILE_FLAGS "-I${RUBY_INCLUDE_DIR} -I${qpid-cpp_SOURCE_DIR}/include")

##----------------------------------
## Install the complete Ruby binding
##----------------------------------
install(FILES ${CMAKE_CURRENT_BINARY_DIR}/libqmfengine_ruby.so
        RENAME qmfengine.so
        DESTINATION ${RUBY_ARCH_DIR}
        COMPONENT ${QPID_COMPONENT_CLIENT}
        )
