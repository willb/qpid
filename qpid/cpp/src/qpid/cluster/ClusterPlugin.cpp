/*
 *
 * Copyright (c) 2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

#include "config.h"
#include "qpid/cluster/Connection.h"
#include "qpid/cluster/ConnectionCodec.h"
#include "qpid/cluster/ClusterSettings.h"

#include "qpid/cluster/SecureConnectionFactory.h"

#include "qpid/cluster/Cluster.h"
#include "qpid/cluster/ConnectionCodec.h"
#include "qpid/cluster/UpdateClient.h"

#include "qpid/broker/Broker.h"
#include "qpid/Plugin.h"
#include "qpid/Options.h"
#include "qpid/sys/AtomicValue.h"
#include "qpid/log/Statement.h"

#include "qpid/management/ManagementAgent.h"
#include "qpid/broker/Exchange.h"
#include "qpid/broker/Message.h"
#include "qpid/broker/Queue.h"
#include "qpid/broker/SessionState.h"
#include "qpid/client/ConnectionSettings.h"

#include <boost/shared_ptr.hpp>
#include <boost/utility/in_place_factory.hpp>
#include <boost/scoped_ptr.hpp>

namespace qpid {
namespace cluster {

using namespace std;
using broker::Broker;
using management::ManagementAgent;


/** Note separating options from settings to work around boost version differences.
 *  Old boost takes a reference to options objects, but new boost makes a copy.
 *  New boost allows a shared_ptr but that's not compatible with old boost.
 */
struct ClusterOptions : public Options {
    ClusterSettings& settings; 

    ClusterOptions(ClusterSettings& v) : Options("Cluster Options"), settings(v) {
        addOptions()
            ("cluster-name", optValue(settings.name, "NAME"), "Name of cluster to join")
            ("cluster-url", optValue(settings.url,"URL"),
             "Set URL of this individual broker, to be advertized to clients.\n"
             "Defaults to a URL listing all the local IP addresses\n")
            ("cluster-username", optValue(settings.username, ""), "Username for connections between brokers")
            ("cluster-password", optValue(settings.password, ""), "Password for connections between brokers")
            ("cluster-mechanism", optValue(settings.mechanism, ""), "Authentication mechanism for connections between brokers")
#if HAVE_LIBCMAN_H
            ("cluster-cman", optValue(settings.quorum), "Integrate with Cluster Manager (CMAN) cluster.")
#endif
            ("cluster-size", optValue(settings.size, "N"), "Wait for N cluster members before allowing clients to connect.")
            ("cluster-clock-interval", optValue(settings.clockInterval,"N"), "How often to broadcast the current time to the cluster nodes, in milliseconds. A value between 5 and 1000 is recommended.")
            ("cluster-read-max", optValue(settings.readMax,"N"), "Experimental: flow-control limit  reads per connection. 0=no limit.")
            ;
    }
};

typedef boost::shared_ptr<sys::ConnectionCodec::Factory> CodecFactoryPtr;

struct ClusterPlugin : public Plugin {

    ClusterSettings settings;
    ClusterOptions options;
    Cluster* cluster;
    boost::scoped_ptr<ConnectionCodec::Factory> factory;

    ClusterPlugin() : options(settings), cluster(0) {}

    // Cluster needs to be initialized after the store 
    int initOrder() const { return Plugin::DEFAULT_INIT_ORDER+500; }
    
    Options* getOptions() { return &options; }

    void earlyInitialize(Plugin::Target& target) {
        if (settings.name.empty()) return; // Only if --cluster-name option was specified.
        Broker* broker = dynamic_cast<Broker*>(&target);
        if (!broker) return;
        cluster = new Cluster(settings, *broker);
        CodecFactoryPtr simpleFactory(new broker::ConnectionFactory(*broker));
        CodecFactoryPtr clusterFactory(new ConnectionCodec::Factory(simpleFactory, *cluster));
        CodecFactoryPtr secureFactory(new SecureConnectionFactory(clusterFactory));
        broker->setConnectionFactory(secureFactory);
    }

    void disallowManagementMethods(ManagementAgent* agent) {
        if (!agent) return;
        agent->disallowV1Methods();
    }

    void initialize(Plugin::Target& target) {
        Broker* broker = dynamic_cast<Broker*>(&target);
        if (broker && cluster) {
            disallowManagementMethods(broker->getManagementAgent());
            cluster->initialize();
        }
    }
};

static ClusterPlugin instance; // Static initialization.

}} // namespace qpid::cluster
