/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
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
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.agent.testhelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Properties;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.internal.runners.InitializationError;
import org.junit.internal.runners.JUnit4ClassRunner;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.jetty.webapp.WebAppContext;

public class FakeBootstrapperServer extends JUnit4ClassRunner {
    private Server server;

    public FakeBootstrapperServer(Class<?> testClass) throws InitializationError {
        super(testClass);
    }

    public void run(RunNotifier runNotifier) {
        try {
            // could be smarter if this is too slow, start only if not started already
            // shut down on JVM shut down instead
            start();
        } catch (Exception e) {
            runNotifier.fireTestFailure(new Failure(getDescription(), e));
        }
        try {
            super.run(runNotifier);
        } finally {
            try {
                stop();
            } catch (Exception e) {
                runNotifier.fireTestFailure(new Failure(getDescription(), e));
            }
        }
    }

    public void start() throws Exception {
        server = new Server(9090);
        WebAppContext wac = new WebAppContext(".", "/go");
        addFakeAgentBinaryServlet(wac, "/admin/agent", new File("testdata/test-agent.jar"));
        addFakeAgentBinaryServlet(wac, "/admin/agent-launcher.jar", new File("testdata/agent-launcher.jar"));
        addFakeAgentBinaryServlet(wac, "/admin/agent-plugins.zip", new File("testdata/agent-plugins.zip"));
        addlatestAgentStatusCall(wac);
        addStopServlet(server, wac);
        addDefaultServlet(wac);
        server.addHandler(wac);
        server.setStopAtShutdown(true);
        server.start();
    }

    public static final class AgentStatusApi extends HttpServlet {
        public static String status = "disabled";
        public static Properties pluginProps = new Properties();

        @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setHeader("Plugins-Status", status);
            pluginProps.setProperty("Active Mock Bundle 1", "1.1.1");
            pluginProps.setProperty("Active Mock Bundle 2", "2.2.2");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pluginProps.store(baos, "Go Plugins for Testing");
            resp.getOutputStream().write(baos.toByteArray());
            baos.close();
        }
    }

    private void addlatestAgentStatusCall(WebAppContext wac) {
        wac.addServlet(AgentStatusApi.class, "/admin/latest-agent.status");
    }

    public static final class BreakpointFriendlyFilter implements Filter {
        public void init(FilterConfig filterConfig) throws ServletException {

        }

        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
            String requestURI = ((HttpServletRequest) servletRequest).getRequestURI();
            filterChain.doFilter(servletRequest, servletResponse);
        }

        public void destroy() {

        }
    }

    private void addDefaultServlet(WebAppContext wac) {
        wac.addFilter(BreakpointFriendlyFilter.class, "*", 0);
    }

    private static void addFakeAgentBinaryServlet(WebAppContext wac, final String pathSpec, final File file) {
        ServletHolder holder = new ServletHolder();
        holder.setServlet(new AgentBinariesServlet(file));
        wac.addServlet(holder, pathSpec);
    }

    private static void addStopServlet(Server server, WebAppContext wac) {
        ServletHolder holder = new ServletHolder();
        holder.setServlet(new StopTestingServerServlet(server));
        wac.addServlet(holder, "/jetty/stop");
    }

    public void stop() throws Exception {
        server.stop();
        server.join();
    }

}

class StopTestingServerServlet extends HttpServlet {
    private final Server stoppingServer;

    public StopTestingServerServlet(Server jettyServer) {
        stoppingServer = jettyServer;
    }

    public void service(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        try {
            Thread thread = new Thread() {
                public void run() {
                    try {
                        stoppingServer.stop();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };
            thread.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}