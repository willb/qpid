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
#
# HA plugin makefile fragment, to be included in Makefile.am
#

dmoduleexec_LTLIBRARIES += ha.la

ha_la_SOURCES =					\
  qpid/ha/Backup.cpp				\
  qpid/ha/Backup.h				\
  qpid/ha/HaBroker.cpp				\
  qpid/ha/HaBroker.h				\
  qpid/ha/HaPlugin.cpp				\
  qpid/ha/Settings.h				\
  qpid/ha/QueueReplicator.h			\
  qpid/ha/QueueReplicator.cpp			\
  qpid/ha/ReplicatingSubscription.h		\
  qpid/ha/ReplicatingSubscription.cpp		\
  qpid/ha/BrokerReplicator.cpp			\
  qpid/ha/BrokerReplicator.h                    \
  qpid/ha/ConnectionExcluder.cpp		\
  qpid/ha/ConnectionExcluder.h

ha_la_LIBADD = libqpidbroker.la
ha_la_LDFLAGS = $(PLUGINLDFLAGS)
