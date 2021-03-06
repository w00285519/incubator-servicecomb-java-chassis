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

package org.apache.servicecomb.core.definition.schema;

import javax.inject.Inject;

import org.apache.servicecomb.core.definition.MicroserviceMeta;
import org.apache.servicecomb.core.definition.MicroserviceVersionMeta;
import org.apache.servicecomb.core.definition.SchemaMeta;
import org.apache.servicecomb.core.definition.SchemaUtils;
import org.apache.servicecomb.core.definition.loader.SchemaListenerManager;
import org.apache.servicecomb.serviceregistry.RegistryUtils;
import org.apache.servicecomb.serviceregistry.api.registry.Microservice;
import org.apache.servicecomb.serviceregistry.client.ServiceRegistryClient;
import org.apache.servicecomb.serviceregistry.consumer.AppManager;
import org.apache.servicecomb.serviceregistry.consumer.MicroserviceVersion;
import org.apache.servicecomb.serviceregistry.consumer.MicroserviceVersionRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.swagger.models.Swagger;

@Component
public class ConsumerSchemaFactory extends AbstractSchemaFactory<ConsumerSchemaContext> {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConsumerSchemaFactory.class);

  @Inject
  protected SchemaListenerManager schemaListenerManager;

  AppManager appManager;

  private final Object lock = new Object();

  public void setSchemaListenerManager(SchemaListenerManager schemaListenerManager) {
    this.schemaListenerManager = schemaListenerManager;
  }

  public void setAppManager(AppManager appManager) {
    this.appManager = appManager;
  }

  // 透明rpc场景，因为每次指定schema调用，所以可以懒加载
  // 而rest场景，是根据path反向查找schema，所以必须所有的schema都存在才行
  // 两种场景可能都会来初始化，为避免管理复杂度，改为全部全量初始化
  //
  // 用于rest consumer注册的场景，此时启动流程已经完成，需要主动通知listener
  // microserviceName可能是本app内的微服务
  // 也可能是appid:name形式的其他app的微服务
  public MicroserviceMeta getOrCreateMicroserviceMeta(String microserviceName, String versionRule) {
    MicroserviceMeta microserviceMeta = microserviceMetaManager.findValue(microserviceName);
    if (microserviceMeta != null) {
      return microserviceMeta;
    }

    // 极小概率事件，不必做microservice级的锁分解了
    synchronized (lock) {
      microserviceMeta = microserviceMetaManager.findValue(microserviceName);
      if (microserviceMeta != null) {
        return microserviceMeta;
      }

      microserviceMeta = new MicroserviceMeta(microserviceName);
      if (appManager == null) {
        appManager = RegistryUtils.getServiceRegistry().getAppManager();
      }
      MicroserviceVersionRule microserviceVersionRule =
          appManager.getOrCreateMicroserviceVersionRule(microserviceMeta.getAppId(),
              microserviceName,
              versionRule);
      MicroserviceVersion microserviceVersion = microserviceVersionRule.getLatestMicroserviceVersion();
      if (microserviceVersion == null) {
        throw new IllegalStateException(
            String.format("Probably invoke a service before it is registered, appId=%s, name=%s",
                microserviceMeta.getAppId(),
                microserviceName));
      }

      if (MicroserviceVersionMeta.class.isInstance(microserviceVersion)) {
        microserviceMeta = ((MicroserviceVersionMeta) microserviceVersion).getMicroserviceMeta();
      } else {
        getOrCreateConsumerSchema(microserviceMeta, microserviceVersion.getMicroservice());
        schemaListenerManager.notifySchemaListener(microserviceMeta);
      }

      LOGGER.info("Found schema ids from service center, {}:{}:{}:{}",
          microserviceMeta.getAppId(),
          microserviceName,
          versionRule,
          microserviceVersion.getMicroservice().getSchemas());

      microserviceMetaManager.register(microserviceName, microserviceMeta);
      return microserviceMeta;
    }
  }

  // 允许consumerIntf与schemaId对应的interface原型不同，用于支持context类型的参数
  // consumerIntf为null，表示原型与契约相同
  public void getOrCreateConsumerSchema(MicroserviceMeta microserviceMeta, Microservice microservice) {
    for (String schemaId : microservice.getSchemas()) {
      ConsumerSchemaContext context = new ConsumerSchemaContext();
      context.setMicroserviceMeta(microserviceMeta);
      context.setMicroservice(microservice);
      context.setSchemaId(schemaId);
      context.setProviderClass(null);

      getOrCreateSchema(context);
    }
  }

  @Override
  protected SchemaMeta createSchema(ConsumerSchemaContext context) {
    // 尝试从规划的目录或服务中心加载契约
    Swagger swagger = loadSwagger(context);

    // 注册契约
    return schemaLoader.registerSchema(context.getMicroserviceMeta(), context.getSchemaId(), swagger);
  }

  @Override
  protected Swagger loadSwagger(ConsumerSchemaContext context) {
    Swagger swagger = super.loadSwagger(context);
    if (swagger != null) {
      return swagger;
    }

    ServiceRegistryClient client = RegistryUtils.getServiceRegistryClient();
    String schemaContent = client.getSchema(context.getMicroservice().getServiceId(), context.getSchemaId());
    LOGGER.info("load schema from service center, microservice={}:{}:{}, schemaId={}, result={}",
        context.getMicroservice().getAppId(),
        context.getMicroservice().getServiceName(),
        context.getMicroservice().getVersion(),
        context.getSchemaId(),
        !StringUtils.isEmpty(schemaContent));
    if (schemaContent != null) {
      return SchemaUtils.parseSwagger(schemaContent);
    }

    throw new Error(
        String.format("no schema in local, and can not get schema from service center, %s:%s",
            context.getMicroserviceName(),
            context.getSchemaId()));
  }
}
