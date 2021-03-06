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

package org.wso2.carbon.transport.http.netty.contractimpl.websocket.message;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import org.wso2.carbon.transport.http.netty.common.Constants;
import org.wso2.carbon.transport.http.netty.contract.websocket.WebSocketInitMessage;
import org.wso2.carbon.transport.http.netty.contractimpl.websocket.WebSocketMessageImpl;
import org.wso2.carbon.transport.http.netty.listener.WebSocketSourceHandler;

import java.net.ProtocolException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.websocket.Session;

/**
 * Implementation of {@link WebSocketInitMessage}.
 */
public class WebSocketInitMessageImpl extends WebSocketMessageImpl implements WebSocketInitMessage {

    private final ChannelHandlerContext ctx;
    private final HttpRequest httpRequest;
    private final WebSocketSourceHandler webSocketSourceHandler;

    public WebSocketInitMessageImpl(ChannelHandlerContext ctx, HttpRequest httpRequest,
                                    WebSocketSourceHandler webSocketSourceHandler, Map<String, String> headers) {
        this.ctx = ctx;
        this.httpRequest = httpRequest;
        this.webSocketSourceHandler = webSocketSourceHandler;
        this.headers = headers;
    }

    @Override
    public Session handshake() throws ProtocolException {

        WebSocketServerHandshakerFactory wsFactory =
                new WebSocketServerHandshakerFactory(getWebSocketURL(httpRequest), getSubProtocol(), true);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(httpRequest);
        try {
            handshaker.handshake(ctx.channel(), httpRequest);

            //Replace HTTP handlers  with  new Handlers for WebSocket in the pipeline
            ChannelPipeline pipeline = ctx.pipeline();
            pipeline.addLast(Constants.WEBSOCKET_SOURCE_HANDLER, webSocketSourceHandler);

            pipeline.remove(Constants.IDLE_STATE_HANDLER);
            pipeline.remove(Constants.HTTP_SOURCE_HANDLER);

            return webSocketSourceHandler.getChannelSession();
        } catch (Exception e) {
            /*
            Code 1002 : indicates that an endpoint is terminating the connection
            due to a protocol error.
             */
            handshaker.close(ctx.channel(),
                             new CloseWebSocketFrame(1002,
                                                     "Terminating the connection due to a protocol error."));
            throw new ProtocolException("Error occurred in HTTP to WebSocket Upgrade : " + e.getMessage());
        }
    }

    @Override
    public Session handshake(int idleTimeout) throws ProtocolException {
        WebSocketServerHandshakerFactory wsFactory =
                new WebSocketServerHandshakerFactory(getWebSocketURL(httpRequest), getSubProtocol(), true);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(httpRequest);
        try {
            handshaker.handshake(ctx.channel(), httpRequest);

            //Replace HTTP handlers  with  new Handlers for WebSocket in the pipeline
            ChannelPipeline pipeline = ctx.pipeline();
            pipeline.replace(Constants.IDLE_STATE_HANDLER, Constants.IDLE_STATE_HANDLER,
                             new IdleStateHandler(idleTimeout, idleTimeout, idleTimeout, TimeUnit.MILLISECONDS));
            pipeline.addLast(Constants.WEBSOCKET_SOURCE_HANDLER, webSocketSourceHandler);

            pipeline.remove(Constants.HTTP_SOURCE_HANDLER);

            setProperty(Constants.SRC_HANDLER, webSocketSourceHandler);
            return webSocketSourceHandler.getChannelSession();
        } catch (Exception e) {
            /*
            Code 1002 : indicates that an endpoint is terminating the connection
            due to a protocol error.
             */
            handshaker.close(ctx.channel(),
                             new CloseWebSocketFrame(1002,
                                                     "Terminating the connection due to a protocol error."));
            throw new ProtocolException("Error occurred in HTTP to WebSocket Upgrade : " + e.getMessage());
        }
    }

    @Override
    public void cancelHandShake(int closeCode, String closeReason) {
        WebSocketServerHandshakerFactory wsFactory =
                new WebSocketServerHandshakerFactory(getWebSocketURL(httpRequest), getSubProtocol(), true);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(httpRequest);
        ChannelFuture channelFuture =
                handshaker.close(ctx.channel(), new CloseWebSocketFrame(closeCode, closeReason));
        channelFuture.channel().close();
    }

    /* Get the URL of the given connection */
    private String getWebSocketURL(HttpRequest req) {
        String protocol = Constants.WEBSOCKET_PROTOCOL;
        if (isConnectionSecured) {
            protocol = Constants.WEBSOCKET_PROTOCOL_SECURED;
        }
        String url =   protocol + "://" + req.headers().get("Host") + req.getUri();
        return url;
    }
}
