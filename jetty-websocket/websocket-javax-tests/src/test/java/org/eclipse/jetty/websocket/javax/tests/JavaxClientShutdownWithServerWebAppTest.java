//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.Configurations;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketClientContainerProvider;
import org.eclipse.jetty.websocket.javax.client.JavaxWebSocketShutdownContainer;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketContainer;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class JavaxClientShutdownWithServerWebAppTest
{
    private WSServer server;
    private HttpClient httpClient;

    @FunctionalInterface
    interface ThrowingRunnable
    {
        void run() throws Exception;
    }

    public void start(ThrowingRunnable configuration) throws Exception
    {
        server = new WSServer();
        configuration.run();
        server.start();
        httpClient = new HttpClient();
        httpClient.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        httpClient.stop();
        server.stop();
    }

    @WebServlet("/")
    public static class ContextHandlerShutdownServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        {
            ContainerProvider.getWebSocketContainer();
        }
    }

    public WSServer.WebApp createWebSocketWebapp(String contextName) throws Exception
    {
        WSServer.WebApp app = server.createWebApp(contextName);

        // Exclude the Javax WebSocket configuration from the webapp.
        Configuration[] configurations = Configurations.getKnown().stream()
            .filter(configuration -> !(configuration instanceof JavaxWebSocketConfiguration))
            .toArray(Configuration[]::new);
        app.getWebAppContext().setConfigurations(configurations);

        // Copy over the individual jars required for Javax WebSocket.
        app.createWebInf();
        app.copyLib(JavaxWebSocketClientContainerProvider.class, "websocket-javax-client.jar");
        app.copyLib(JavaxWebSocketContainer.class, "websocket-javax-common.jar");
        app.copyLib(ContainerLifeCycle.class, "jetty-util.jar");
        app.copyLib(CoreClientUpgradeRequest.class, "websocket-core-client.jar");
        app.copyLib(WebSocketComponents.class, "websocket-core-common.jar");
        app.copyLib(Response.class, "jetty-client.jar");
        app.copyLib(ByteBufferPool.class, "jetty-io.jar");
        app.copyLib(BadMessageException.class, "jetty-http.jar");

        return app;
    }

    @Test
    public void websocketProvidedByServer() throws Exception
    {
        start(() ->
        {
            WSServer.WebApp app1 = server.createWebApp("app1");
            app1.createWebInf();
            app1.copyClass(ContextHandlerShutdownServlet.class);
            app1.deploy();

            WSServer.WebApp app2 = server.createWebApp("app2");
            app2.createWebInf();
            app2.copyClass(ContextHandlerShutdownServlet.class);
            app2.deploy();

            WSServer.WebApp app3 = server.createWebApp("app3");
            app3.createWebInf();
            app3.copyClass(ContextHandlerShutdownServlet.class);
            app3.deploy();
        });

        // Before connecting to the server there is only the containers created for the server component of each WebApp.
        assertThat(server.isRunning(), is(true));
        assertThat(server.getContainedBeans(WebSocketContainer.class).size(), is(3));

        // After hitting each WebApp with a request we now have an additional 3 client containers.
        ContentResponse response = httpClient.GET(server.getServerUri().resolve("/app1"));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        response = httpClient.GET(server.getServerUri().resolve("/app2"));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        response = httpClient.GET(server.getServerUri().resolve("/app3"));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(server.getContainedBeans(WebSocketContainer.class).size(), is(6));

        // All the websocket containers are removed on stopping of the server.
        server.stop();
        assertThat(server.isRunning(), is(false));
        assertThat(server.getContainedBeans(WebSocketContainer.class).size(), is(0));
    }

    @Test
    public void websocketProvidedByWebApp() throws Exception
    {
        start(() ->
        {
            WSServer.WebApp app1 = createWebSocketWebapp("app1");
            app1.copyClass(ContextHandlerShutdownServlet.class);
            app1.deploy();

            WSServer.WebApp app2 = createWebSocketWebapp("app2");
            app2.copyClass(ContextHandlerShutdownServlet.class);
            app2.deploy();

            WSServer.WebApp app3 = createWebSocketWebapp("app3");
            app3.copyClass(ContextHandlerShutdownServlet.class);
            app3.deploy();
        });

        // Before connecting to the server there is only the containers created for the server component of each WebApp.
        assertThat(server.isRunning(), is(true));
        assertThat(server.getContainedBeans(WebSocketContainer.class).size(), is(0));

        // After hitting each WebApp with a request we now have an additional 3 client containers.
        ContentResponse response = httpClient.GET(server.getServerUri().resolve("/app1"));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        response = httpClient.GET(server.getServerUri().resolve("/app2"));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        response = httpClient.GET(server.getServerUri().resolve("/app3"));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        // Collect the toString result of the ShutdownContainers from the dump.
        List<String> results = Arrays.stream(server.getServer().dump().split("\n"))
            .filter(line -> line.contains("+> " + JavaxWebSocketShutdownContainer.class.getSimpleName()))
            .collect(Collectors.toList());

        // We only have 3 Shutdown Containers and they all contain only 1 item to be shutdown.
        assertThat(results.size(), is(3));
        for (String result : results)
        {
            assertThat(result, containsString("size=1"));
        }
    }
}
