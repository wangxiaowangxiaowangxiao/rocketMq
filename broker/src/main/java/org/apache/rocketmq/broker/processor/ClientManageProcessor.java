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
package org.apache.rocketmq.broker.processor;

import io.netty.channel.ChannelHandlerContext;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.broker.client.ClientChannelInfo;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.common.filter.ExpressionType;
import org.apache.rocketmq.common.protocol.RequestCode;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.body.CheckClientRequestBody;
import org.apache.rocketmq.common.protocol.header.UnregisterClientRequestHeader;
import org.apache.rocketmq.common.protocol.header.UnregisterClientResponseHeader;
import org.apache.rocketmq.common.protocol.heartbeat.ConsumerData;
import org.apache.rocketmq.common.protocol.heartbeat.HeartbeatData;
import org.apache.rocketmq.common.protocol.heartbeat.ProducerData;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.common.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.common.sysflag.TopicSysFlag;
import org.apache.rocketmq.filter.FilterFactory;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.netty.AsyncNettyRequestProcessor;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;

public class ClientManageProcessor extends AsyncNettyRequestProcessor implements NettyRequestProcessor {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);
    private final BrokerController brokerController;

    public ClientManageProcessor(final BrokerController brokerController) {
        this.brokerController = brokerController;
    }

    @Override
    public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request)
        throws RemotingCommandException {
        switch (request.getCode()) {
            case RequestCode.HEART_BEAT:
                return this.heartBeat(ctx, request);
            case RequestCode.UNREGISTER_CLIENT:
                return this.unregisterClient(ctx, request);
            case RequestCode.CHECK_CLIENT_CONFIG:
                return this.checkClientConfig(ctx, request);
            default:
                break;
        }
        return null;
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }

    /**
     * broker侧的ClientManageProcessor#heartBeat负责处理心跳注册信息。
     * 1、ClientManageProcessor#heartBeat创建%RETRY%consumer_group的topic信息。
     * 2、ClientManageProcessor#heartBeat通过registerConsumer注册consumer信息。
     * 3、ClientManageProcessor#heartBeat通过registerProducer注册producer信息。
     */
    public RemotingCommand heartBeat(ChannelHandlerContext ctx, RemotingCommand request) {
        RemotingCommand response = RemotingCommand.createResponseCommand(null);
        HeartbeatData heartbeatData = HeartbeatData.decode(request.getBody(), HeartbeatData.class);
        ClientChannelInfo clientChannelInfo = new ClientChannelInfo(
            ctx.channel(),
            heartbeatData.getClientID(),
            request.getLanguage(),
            request.getVersion()
        );

        for (ConsumerData data : heartbeatData.getConsumerDataSet()) {
            SubscriptionGroupConfig subscriptionGroupConfig =
                this.brokerController.getSubscriptionGroupManager().findSubscriptionGroupConfig(
                    data.getGroupName());
            boolean isNotifyConsumerIdsChangedEnable = true;
            if (null != subscriptionGroupConfig) {
                isNotifyConsumerIdsChangedEnable = subscriptionGroupConfig.isNotifyConsumerIdsChangedEnable();
                int topicSysFlag = 0;
                if (data.isUnitMode()) {
                    topicSysFlag = TopicSysFlag.buildSysFlag(false, true);
                }
                // 重试队列是以consumer group为维度的，创建重试队列topic信息
                // topic名的格式为%RETRY%consumer_group
                String newTopic = MixAll.getRetryTopic(data.getGroupName());
                // 创建重试队列对应的topic=%RETRY%consumer_group
                this.brokerController.getTopicConfigManager().createTopicInSendMessageBackMethod(
                    newTopic,
                    subscriptionGroupConfig.getRetryQueueNums(),
                    PermName.PERM_WRITE | PermName.PERM_READ, topicSysFlag);
            }

            // 注册consumer信息
            boolean changed = this.brokerController.getConsumerManager().registerConsumer(
                data.getGroupName(),
                clientChannelInfo,
                data.getConsumeType(),
                data.getMessageModel(),
                data.getConsumeFromWhere(),
                data.getSubscriptionDataSet(),
                isNotifyConsumerIdsChangedEnable
            );

            if (changed) {
                log.info("registerConsumer info changed {} {}",
                    data.toString(),
                    RemotingHelper.parseChannelRemoteAddr(ctx.channel())
                );
            }
        }

        // 注册producer信息
        for (ProducerData data : heartbeatData.getProducerDataSet()) {
            this.brokerController.getProducerManager().registerProducer(data.getGroupName(),
                clientChannelInfo);
        }
        response.setCode(ResponseCode.SUCCESS);
        response.setRemark(null);
        return response;
    }

    public RemotingCommand unregisterClient(ChannelHandlerContext ctx, RemotingCommand request)
        throws RemotingCommandException {
        final RemotingCommand response =
            RemotingCommand.createResponseCommand(UnregisterClientResponseHeader.class);
        final UnregisterClientRequestHeader requestHeader =
            (UnregisterClientRequestHeader) request
                .decodeCommandCustomHeader(UnregisterClientRequestHeader.class);

        ClientChannelInfo clientChannelInfo = new ClientChannelInfo(
            ctx.channel(),
            requestHeader.getClientID(),
            request.getLanguage(),
            request.getVersion());
        {
            final String group = requestHeader.getProducerGroup();
            if (group != null) {
                this.brokerController.getProducerManager().unregisterProducer(group, clientChannelInfo);
            }
        }

        {
            final String group = requestHeader.getConsumerGroup();
            if (group != null) {
                SubscriptionGroupConfig subscriptionGroupConfig =
                    this.brokerController.getSubscriptionGroupManager().findSubscriptionGroupConfig(group);
                boolean isNotifyConsumerIdsChangedEnable = true;
                if (null != subscriptionGroupConfig) {
                    isNotifyConsumerIdsChangedEnable = subscriptionGroupConfig.isNotifyConsumerIdsChangedEnable();
                }
                this.brokerController.getConsumerManager().unregisterConsumer(group, clientChannelInfo, isNotifyConsumerIdsChangedEnable);
            }
        }

        response.setCode(ResponseCode.SUCCESS);
        response.setRemark(null);
        return response;
    }

    public RemotingCommand checkClientConfig(ChannelHandlerContext ctx, RemotingCommand request)
        throws RemotingCommandException {
        final RemotingCommand response = RemotingCommand.createResponseCommand(null);

        CheckClientRequestBody requestBody = CheckClientRequestBody.decode(request.getBody(),
            CheckClientRequestBody.class);

        if (requestBody != null && requestBody.getSubscriptionData() != null) {
            SubscriptionData subscriptionData = requestBody.getSubscriptionData();

            if (ExpressionType.isTagType(subscriptionData.getExpressionType())) {
                response.setCode(ResponseCode.SUCCESS);
                response.setRemark(null);
                return response;
            }

            if (!this.brokerController.getBrokerConfig().isEnablePropertyFilter()) {
                response.setCode(ResponseCode.SYSTEM_ERROR);
                response.setRemark("The broker does not support consumer to filter message by " + subscriptionData.getExpressionType());
                return response;
            }

            try {
                FilterFactory.INSTANCE.get(subscriptionData.getExpressionType()).compile(subscriptionData.getSubString());
            } catch (Exception e) {
                log.warn("Client {}@{} filter message, but failed to compile expression! sub={}, error={}",
                    requestBody.getClientId(), requestBody.getGroup(), requestBody.getSubscriptionData(), e.getMessage());
                response.setCode(ResponseCode.SUBSCRIPTION_PARSE_FAILED);
                response.setRemark(e.getMessage());
                return response;
            }
        }

        response.setCode(ResponseCode.SUCCESS);
        response.setRemark(null);
        return response;
    }
}
