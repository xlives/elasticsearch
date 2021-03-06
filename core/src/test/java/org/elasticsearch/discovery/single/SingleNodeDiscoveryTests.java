/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.discovery.single;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.Closeable;
import java.util.Stack;
import java.util.concurrent.CountDownLatch;

import static org.elasticsearch.test.ClusterServiceUtils.createClusterService;
import static org.hamcrest.Matchers.equalTo;

public class SingleNodeDiscoveryTests extends ESTestCase {

    public void testInitialJoin() throws Exception {
        final Settings settings = Settings.EMPTY;
        final Version version = Version.CURRENT;
        final ThreadPool threadPool = new TestThreadPool(getClass().getName());
        final Stack<Closeable> stack = new Stack<>();
        try {
            final MockTransportService transportService =
                    MockTransportService.createNewService(settings, version, threadPool, null);
            stack.push(transportService);
            transportService.start();
            final DiscoveryNode node = transportService.getLocalNode();
            final ClusterService clusterService = createClusterService(threadPool, node);
            stack.push(clusterService);
            final SingleNodeDiscovery discovery =
                    new SingleNodeDiscovery(Settings.EMPTY, clusterService);
            discovery.startInitialJoin();

            // we are racing against the initial join which is asynchronous so we use an observer
            final ClusterState state = clusterService.state();
            final ThreadContext threadContext = threadPool.getThreadContext();
            final ClusterStateObserver observer =
                    new ClusterStateObserver(state, clusterService, null, logger, threadContext);
            if (state.nodes().getMasterNodeId() == null) {
                final CountDownLatch latch = new CountDownLatch(1);
                observer.waitForNextChange(new ClusterStateObserver.Listener() {
                    @Override
                    public void onNewClusterState(ClusterState state) {
                        latch.countDown();
                    }

                    @Override
                    public void onClusterServiceClose() {
                        latch.countDown();
                    }

                    @Override
                    public void onTimeout(TimeValue timeout) {
                        assert false;
                    }
                }, s -> s.nodes().getMasterNodeId() != null);

                latch.await();
            }

            final DiscoveryNodes nodes = clusterService.state().nodes();
            assertThat(nodes.getSize(), equalTo(1));
            assertThat(nodes.getMasterNode().getId(), equalTo(node.getId()));
        } finally {
            while (!stack.isEmpty()) {
                IOUtils.closeWhileHandlingException(stack.pop());
            }
            terminate(threadPool);
        }
    }

}
