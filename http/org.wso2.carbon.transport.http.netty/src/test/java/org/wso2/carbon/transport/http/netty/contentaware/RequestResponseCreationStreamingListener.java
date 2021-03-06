/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.carbon.transport.http.netty.contentaware;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.MessageUtil;
import org.wso2.carbon.transport.http.netty.common.Constants;
import org.wso2.carbon.transport.http.netty.config.SenderConfiguration;
import org.wso2.carbon.transport.http.netty.config.TransportProperty;
import org.wso2.carbon.transport.http.netty.config.TransportsConfiguration;
import org.wso2.carbon.transport.http.netty.contract.HttpClientConnector;
import org.wso2.carbon.transport.http.netty.contract.HttpConnectorListener;
import org.wso2.carbon.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.carbon.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.carbon.transport.http.netty.contract.ServerConnectorException;
import org.wso2.carbon.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.carbon.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.carbon.transport.http.netty.message.HTTPMessageUtil;
import org.wso2.carbon.transport.http.netty.util.TestUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A class which read and write content through streams
 */
public class RequestResponseCreationStreamingListener implements HttpConnectorListener {

    private static final Logger logger = LoggerFactory.getLogger(RequestResponseCreationStreamingListener.class);

    private TransportsConfiguration configuration;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    public RequestResponseCreationStreamingListener(TransportsConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void onMessage(HTTPCarbonMessage httpRequest) {
        executor.execute(() -> {
            try {
                InputStream inputStream = httpRequest.getInputStream();

                CarbonMessage newMsg = MessageUtil.cloneCarbonMessageWithOutData(httpRequest);
                OutputStream outputStream = newMsg.getOutputStream();
                byte[] bytes = IOUtils.toByteArray(inputStream);
                outputStream.write(bytes);
                outputStream.flush();
                newMsg.setEndOfMsgAdded(true);
                newMsg.setProperty(Constants.HOST, TestUtil.TEST_HOST);
                newMsg.setProperty(Constants.PORT, TestUtil.TEST_SERVER_PORT);

                HTTPCarbonMessage httpCarbonMessage = HTTPMessageUtil.convertCarbonMessage(newMsg);

                Map<String, Object> transportProperties = new HashMap<>();
                Set<TransportProperty> transportPropertiesSet = configuration.getTransportProperties();
                if (transportPropertiesSet != null && !transportPropertiesSet.isEmpty()) {
                    transportProperties = transportPropertiesSet.stream().collect(
                            Collectors.toMap(TransportProperty::getName, TransportProperty::getValue));

                }

                SenderConfiguration senderConfiguration = HTTPMessageUtil.getSenderConfiguration(configuration);

                HttpWsConnectorFactory httpWsConnectorFactory = new HttpWsConnectorFactoryImpl();
                HttpClientConnector clientConnector =
                        httpWsConnectorFactory.createHttpClientConnector(transportProperties, senderConfiguration);
                HttpResponseFuture future = clientConnector.send(httpCarbonMessage);
                future.setHttpConnectorListener(new HttpConnectorListener() {
                    @Override
                    public void onMessage(HTTPCarbonMessage httpMessage) {
                        executor.execute(() -> {
                            InputStream inputStream = httpMessage.getInputStream();

                            CarbonMessage newMsg = MessageUtil.cloneCarbonMessageWithOutData(httpMessage);
                            OutputStream outputStream = newMsg.getOutputStream();
                            try {
                                byte[] bytes = IOUtils.toByteArray(inputStream);
                                outputStream.write(bytes);
                                outputStream.flush();
                            } catch (IOException e) {
                                throw new RuntimeException("Cannot read Input Stream from Response", e);
                            }
                            newMsg.setEndOfMsgAdded(true);

                            HTTPCarbonMessage httpCarbonMessage1 = HTTPMessageUtil.convertCarbonMessage(newMsg);

                            try {
                                httpRequest.respond(httpCarbonMessage1);
                            } catch (ServerConnectorException e) {
                                logger.error("Error occurred during message notification: " + e.getMessage());
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable throwable) {

                    }
                });

            } catch (Exception e) {
                logger.error("Error while reading stream", e);
            }
        });
    }

    @Override
    public void onError(Throwable throwable) {

    }
}
