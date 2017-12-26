/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.metrics.core;

import java.io.IOException;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import io.servicecomb.metrics.common.RegistryMetric;
import io.servicecomb.metrics.core.monitor.DefaultSystemMonitor;
import io.servicecomb.metrics.core.monitor.RegistryMonitor;
import io.servicecomb.metrics.core.monitor.SystemMonitor;
import io.servicecomb.metrics.core.publish.DefaultDataSource;
import io.servicecomb.metrics.core.publish.DefaultMetricsPublisher;

public class TestPublisher {

  @Test
  public void test() throws IOException {
    SystemMonitor systemMonitor = new DefaultSystemMonitor();
    RegistryMonitor registryMonitor = new RegistryMonitor(systemMonitor);
    DefaultDataSource dataSource = new DefaultDataSource(registryMonitor);
    DefaultMetricsPublisher publisher = new DefaultMetricsPublisher(dataSource);

    RegistryMetric registryMetric = publisher.metrics();
    Map<String, Number> metricsMap = registryMetric.toMap();
    Assert.assertEquals(metricsMap.size(), 30);

    registryMetric = publisher.metricsWithWindowTimeIndex(0);
    metricsMap = registryMetric.toMap();
    Assert.assertEquals(metricsMap.size(), 30);
  }
}
