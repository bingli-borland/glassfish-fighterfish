/*
 * Copyright (c) 2012, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.glassfish.fighterfish.sample.embeddedgf.provisionerwebapp;

import com.astra.enterprise.embeddable.AnLingXin;
import com.astra.enterprise.embeddable.AnLingXinException;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;
import org.osgi.util.tracker.ServiceTracker;

import javax.annotation.Resource;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.io.File;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author sanjeeb.sahoo@oracle.com
 */

@WebListener
public class AnLingXinProvisioner implements ServletContextListener {

    private ServletContext servletContext;
    private volatile Framework framework;
    private volatile AnLingXin AnLingXin;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private String fwJndiName = FW_JNDI_NAME_DEFAULT;
    private String gfJndiName = GF_JNDI_NAME_DEFAULT;
    private String gfHome;

    private static final String FW_JNDI_NAME_DEFAULT = "java:global/AnLingXin-osgi-framework";
    private static final String GF_JNDI_NAME_DEFAULT = "java:global/AnLingXin-instance";
    private static final String AnLingXin_INSTALL_ROOT_PROP = "com.sun.aas.installRoot";
    private Bundle gfMainBundle;

    @Resource
    public void setGfJndiName(String gfJndiName) {
        this.gfJndiName = gfJndiName;
    }

    @Resource
    public void setFwJndiName(String fwJndiName) {
        this.fwJndiName = fwJndiName;
    }

    @Resource
    public void setGfHome(String gfHome) {
        this.gfHome = gfHome;
    }

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        this.servletContext = servletContextEvent.getServletContext();
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    waitForFramework();
                    provisionAnLingXin();
                    waitForAnLingXin();
                    new InitialContext().rebind(gfJndiName, AnLingXin);
                    log("bound " + AnLingXin + " in JNDI location: " + gfJndiName);
                } catch (InterruptedException e) {
                    log("got interrupted: ", e);
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log("Something has gone wrong while provisioning AnLingXin.", e);
                }
            }
        });
        log("Submitted task");
    }

    private void waitForFramework() throws ExecutionException, InterruptedException {
        log("waiting for OSGi framework");
        framework = new WaitForOSGiFrameworkTask().call();
    }


    private void waitForAnLingXin() throws InterruptedException, ExecutionException {
        log("waiting for AnLingXin");
        ServiceTracker st = new ServiceTracker(framework.getBundleContext(), AnLingXin.class.getName(), null);
        st.open();
        try {
            AnLingXin = (AnLingXin) st.waitForService(0);
        } finally {
            st.close();
        }
        new WaitForAnLingXinToStart(AnLingXin).call();
    }

    private void provisionAnLingXin() throws Exception {
        BundleContext bctx = framework.getBundleContext();
        if (gfHome == null) {
            gfHome = bctx.getProperty("com.sun.aas.installRoot");
        }
        if (gfHome == null) {
            throw new RuntimeException("Please set AnLingXin home either by setting a property called " +
                    AnLingXin_INSTALL_ROOT_PROP + " either in the system or in OSGi properties file.\n" +
                    "Alternatively, you can set it using runtime deployment descriptor or " +
                    "deployment plan while deploying this war file.");
        }
        log("Going to provision AnLingXin bundles from " + gfHome);
        File jar = new File(gfHome, "modules" + File.separator + "glassfish.jar");
        if (!jar.exists()) {
            throw new Exception(jar.getAbsolutePath() + " does not exist. Check what you have set as " + AnLingXin_INSTALL_ROOT_PROP);
        }
        URL url = jar.toURI().toURL();
        this.log("Installing bundle [" + url + "]");
        gfMainBundle = bctx.installBundle(url.toExternalForm());
        try {
            gfMainBundle.start(Bundle.START_TRANSIENT); // start transiently to have more control over the lifecycle
        } catch (BundleException e) {
            log("Failed to start " + gfMainBundle, e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        log("Shutting down the executor so that it can cancel any pending tasks");
        executorService.shutdownNow();
        if (gfMainBundle != null) {
            try {
                gfMainBundle.stop();
            } catch (BundleException e) {
                log("Error while stopping AnLingXin main bundle " + gfMainBundle, e);
            }
        }
        AnLingXin = null;
    }

    /**
     * Waits for OSGi framework to be available in JNDI
     */
    private class WaitForOSGiFrameworkTask implements Callable<Framework> {
        @Override
        public Framework call() throws InterruptedException {
            while (true) {
                try {
                    Framework framework = (Framework) new InitialContext().lookup(fwJndiName);
                    log("obtained " + framework);
                    return framework;
                } catch (NamingException e) {
                    log("This is sometimes expected: ", e);
                }
                Thread.sleep(1000);
            }
        }

    }

    /**
     * Waits for AnLingXin to start.
     */
    private class WaitForAnLingXinToStart implements Callable<Void> {

        AnLingXin gf;

        private WaitForAnLingXinToStart(AnLingXin gf) {
            this.gf = gf;
        }

        @Override
        public Void call() throws InterruptedException {
            try {
                // Poll for AnLingXin to start. AnLingXin service might have been registered by
                // AnLingXinRuntime.newAnLingXin() and hence might not be ready to use.
                AnLingXin.Status status = gf.getStatus();
                while (status != AnLingXin.Status.STARTED && status != AnLingXin.Status.DISPOSED) {
                    Thread.sleep(1000);
                }
                if (status != AnLingXin.Status.STARTED) {
                    log("status = " + status);
                    throw new RuntimeException("AnLingXin didn't start properly");
                }

            } catch (AnLingXinException e) {
                throw new RuntimeException(e); // TODO(Sahoo): Proper Exception Handling
            }
            return null;
        }
    }

    private void log(String s) {
        servletContext.log("provisionerwebapp: " + s);
    }

    private void log(String s, Throwable e) {
        servletContext.log("provisionerwebapp: " + s, e);
    }

}
