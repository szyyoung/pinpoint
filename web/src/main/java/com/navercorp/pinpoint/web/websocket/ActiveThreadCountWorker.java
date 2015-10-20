/*
 *
 *  * Copyright 2014 NAVER Corp.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 */

package com.navercorp.pinpoint.web.websocket;

import com.navercorp.pinpoint.rpc.packet.stream.StreamClosePacket;
import com.navercorp.pinpoint.rpc.packet.stream.StreamCode;
import com.navercorp.pinpoint.rpc.packet.stream.StreamCreateFailPacket;
import com.navercorp.pinpoint.rpc.packet.stream.StreamResponsePacket;
import com.navercorp.pinpoint.rpc.stream.*;
import com.navercorp.pinpoint.thrift.dto.command.TCmdActiveThreadCount;
import com.navercorp.pinpoint.thrift.dto.command.TCmdActiveThreadCountRes;
import com.navercorp.pinpoint.thrift.dto.command.TCommandTransferResponse;
import com.navercorp.pinpoint.thrift.dto.command.TRouteResult;
import com.navercorp.pinpoint.web.service.AgentService;
import com.navercorp.pinpoint.web.vo.AgentActiveThreadCount;
import com.navercorp.pinpoint.web.vo.AgentInfo;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @Author Taejin Koo
 */
public class ActiveThreadCountWorker implements PinpointWebSocketHandlerWorker {

    private static final ClientStreamChannelMessageListener LOGGING = LoggingStreamChannelMessageListener.CLIENT_LISTENER;
    private static final TCmdActiveThreadCount COMMAND_INSTANCE = new TCmdActiveThreadCount();
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Object lock = new Object();
    private final AgentService agentService;

    private final String applicationName;
    private final String agentId;

    private final PinpointWebSocketResponseAggregator responseAggregator;
    private final WorkerActiveManager workerActiveManager;
    private final AgentActiveThreadCount defaultFailedResponse;
    private final MessageListener messageListener;
    private final StateChangeListener stateChangeListener;

    private volatile boolean started = false;
    private volatile boolean active = false;
    private volatile boolean stopped = false;

    private StreamChannel streamChannel;

    public ActiveThreadCountWorker(AgentService agentService, AgentInfo agentInfo, PinpointWebSocketResponseAggregator webSocketResponseAggregator, WorkerActiveManager workerActiveManager) {
        this(agentService, agentInfo.getApplicationName(), agentInfo.getAgentId(), webSocketResponseAggregator, workerActiveManager);
    }

    public ActiveThreadCountWorker(AgentService agentService, String applicationName, String agentId, PinpointWebSocketResponseAggregator webSocketResponseAggregator, WorkerActiveManager workerActiveManager) {
        this.agentService = agentService;

        this.applicationName = applicationName;
        this.agentId = agentId;

        this.responseAggregator = webSocketResponseAggregator;
        this.workerActiveManager = workerActiveManager;

        this.defaultFailedResponse = new AgentActiveThreadCount(agentId);

        this.messageListener = new MessageListener();
        this.stateChangeListener = new StateChangeListener();
    }

    @Override
    public void start(AgentInfo agentInfo) {
        if (!applicationName.equals(agentInfo.getApplicationName())) {
            return;
        }

        if (!agentId.equals(agentInfo.getAgentId())) {
            return;
        }

        synchronized (lock) {
            if (!started) {
                started = true;

                logger.info("ActiveThreadCountWorker start. applicationName:{}, agentId:{}", applicationName, agentId);
                this.active = active0(agentInfo);
            } else {
            }
        }

    }

    @Override
    public boolean reactive(AgentInfo agentInfo) {
        synchronized (lock) {
            if (isTurnOn()) {
                if (active) {
                    return true;
                }

                logger.info("ActiveThreadCountWorker reactive. applicationName:{}, agentId:{}", applicationName, agentId);
                active = active0(agentInfo);
                return active;
            }
        }

        return false;
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (isTurnOn()) {
                stopped = true;

                logger.info("ActiveThreadCountWorker stop. applicationName:{}, agentId:{}, streamChannel:{}", applicationName, agentId, streamChannel);

                try {
                    closeStreamChannel();
                } catch (Exception e) {
                }
                return;
            } else {
            }
        }
    }

    private boolean active0(AgentInfo agentInfo) {
        synchronized (lock) {
            boolean active = false;
            try {
                ClientStreamChannelContext clientStreamChannelContext = agentService.openStream(agentInfo, COMMAND_INSTANCE, messageListener, stateChangeListener);
                if (clientStreamChannelContext == null) {
                    defaultFailedResponse.setFail(StreamCode.CONNECTION_NOT_FOUND.name());
                    workerActiveManager.addReactiveWorker(agentInfo);
                } else {
                    if (clientStreamChannelContext.getCreateFailPacket() == null) {
                        streamChannel = clientStreamChannelContext.getStreamChannel();
                        active = true;
                    } else {
                        StreamCreateFailPacket createFailPacket = clientStreamChannelContext.getCreateFailPacket();
                        defaultFailedResponse.setFail(createFailPacket.getCode().name());
                    }
                }
            } catch (TException exception) {
                defaultFailedResponse.setFail(TRouteResult.NOT_SUPPORTED_REQUEST.name());
            }

            return active;
        }
    }

    private boolean isTurnOn() {
        if (started && !stopped) {
            return true;
        } else {
            return false;
        }
    }

    private void closeStreamChannel() {
        if (streamChannel != null) {
            streamChannel.close();
        }
        defaultFailedResponse.setFail(StreamCode.STATE_CLOSED.name());
    }

    public String getAgentId() {
        return agentId;
    }

    public AgentActiveThreadCount getDefaultFailedResponse() {
        return defaultFailedResponse;
    }

    private void setStreamChannel(ClientStreamChannel streamChannel) {
        this.streamChannel = streamChannel;
    }

    private class MessageListener implements ClientStreamChannelMessageListener {

        @Override
        public void handleStreamData(ClientStreamChannelContext streamChannelContext, StreamResponsePacket packet) {
            LOGGING.handleStreamData(streamChannelContext, packet);

            TBase response = agentService.deserializeResponse(packet.getPayload(), null);
            AgentActiveThreadCount activeThreadCount = getAgentActiveThreadCount(response);
            responseAggregator.response(activeThreadCount);
        }

        @Override
        public void handleStreamClose(ClientStreamChannelContext streamChannelContext, StreamClosePacket packet) {
            LOGGING.handleStreamClose(streamChannelContext, packet);

            defaultFailedResponse.setFail(StreamCode.STATE_CLOSED.name());
        }

        private AgentActiveThreadCount getAgentActiveThreadCount(TBase routeResponse) {
            AgentActiveThreadCount agentActiveThreadCount = new AgentActiveThreadCount(agentId);

            if (routeResponse != null && (routeResponse instanceof TCommandTransferResponse)) {
                byte[] payload = ((TCommandTransferResponse) routeResponse).getPayload();
                TBase<?, ?> activeThreadCountResponse = agentService.deserializeResponse(payload, null);

                if (activeThreadCountResponse != null && (activeThreadCountResponse instanceof TCmdActiveThreadCountRes)) {
                    agentActiveThreadCount.setResult((TCmdActiveThreadCountRes) activeThreadCountResponse);
                } else {
                    agentActiveThreadCount.setFail("ROUTE_ERROR:" + TRouteResult.NOT_SUPPORTED_RESPONSE.name());
                }
            } else {
                agentActiveThreadCount.setFail("ROUTE_ERROR:" + TRouteResult.BAD_RESPONSE.name());
            }

            return agentActiveThreadCount;
        }

    }

    private class StateChangeListener implements StreamChannelStateChangeEventHandler<ClientStreamChannel> {

        @Override
        public void eventPerformed(ClientStreamChannel streamChannel, StreamChannelStateCode updatedStateCode) throws Exception {
            logger.info("eventPerformed streamChannel:{}, stateCode:{}", streamChannel, updatedStateCode);

            switch (updatedStateCode) {
                case CLOSED:
                case ILLEGAL_STATE:
                    if (isTurnOn()) {
                        active = false;
                        workerActiveManager.addReactiveWorker(agentId);
                        defaultFailedResponse.setFail(StreamCode.STATE_CLOSED.name());
                    }
                    break;
            }
        }

        @Override
        public void exceptionCaught(ClientStreamChannel streamChannel, StreamChannelStateCode updatedStateCode, Throwable e) {
            logger.warn("exceptionCaught message:{}, streamChannel:{}, stateCode:{}", e.getMessage(), streamChannel, updatedStateCode, e);
        }

    }

}
