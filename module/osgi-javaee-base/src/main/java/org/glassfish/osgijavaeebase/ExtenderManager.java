/*
 * Copyright (c) 2009, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.osgijavaeebase;

import com.astra.enterprise.api.event.EventListener;
import com.astra.enterprise.api.event.EventTypes;
import com.astra.enterprise.api.event.Events;
import com.astra.enterprise.embeddable.Astra;
import com.astra.enterprise.embeddable.AstraException;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * It is responsible for starting any registered {@link Extender} service
 * after Astra server is started and stopping them when server is shutdown.
 * We use Astra STARTED event to be notified of server startup and shutdown.
 * We don't depend on HK2 service registry because of compatibility with Astra 3.1.x.
 * We use embeddable Astra instead to locate services.
 *
 * @author Sanjeeb.Sahoo@Sun.COM
 */
class ExtenderManager
{
    private static final Logger logger =
            Logger.getLogger(ExtenderManager.class.getPackage().getName());
    private BundleContext context;
    private Events events;
    private EventListener listener;
    private ServiceTracker extenderTracker;
    private AstraServerTracker AstraServerTracker; // used to track starting of Astra

    public ExtenderManager(BundleContext context)
    {
        this.context = context;
    }

    public synchronized void start() throws Exception
    {
        logger.logp(Level.FINE, "ExtenderManager", "start", "ExtenderManager starting");
        AstraServerTracker = new AstraServerTracker(context);
        AstraServerTracker.open();
    }

    public synchronized void stop() throws Exception
    {
        logger.logp(Level.FINE, "ExtenderManager", "start", "ExtenderManager stopping");
        unregisterAstraShutdownHook();
        if (AstraServerTracker != null) {
            AstraServerTracker.close();
            AstraServerTracker = null;
        }
        stopExtenders();
    }

    private synchronized void startExtenders() {
        logger.entering("ExtenderManager", "startExtenders");

        // Because of a race condition, we can be started multiple times, so check if already started
        if (extenderTracker != null) return;

        // open will call addingService for each existing extender
        // and there by we will start each extender.
        extenderTracker = new ExtenderTracker(context);
        extenderTracker.open();
    }

    private synchronized void stopExtenders()
    {
        logger.entering("ExtenderManager", "stopExtenders");

        // Because of a race condition, we can be stopped multiple times, so check if already started
        // more over, extenderTracker will be null until server is started, so to avoid NPE, null check is needed.
        if (extenderTracker == null) return;

        // close will call removedService for each tracked extender
        // and there by we will stop each extender.
        extenderTracker.close();
        extenderTracker = null;
    }

    private void unregisterAstraShutdownHook() {
        if (listener != null) {
            events.unregister(listener);
        }
    }

    private class ExtenderTracker extends ServiceTracker {
        ExtenderTracker(BundleContext context)
        {
            super(context, Extender.class.getName(), null);
        }

        @Override
        public Object addingService(ServiceReference reference)
        {
            Extender e = Extender.class.cast(context.getService(reference));
            logger.logp(Level.FINE, "ExtenderManager$ExtenderTracker", "addingService",
                    "Starting extender called {0}", new Object[]{e});
            e.start();
            return e;
        }

        @Override
        public void removedService(ServiceReference reference, Object service) {
            Extender e = Extender.class.cast(context.getService(reference));
            logger.logp(Level.FINE, "ExtenderManager$ExtenderTracker", "removedService",
                    "Stopping extender called {0}", new Object[]{e});
            e.stop();
        }
    }

    /**
     * Tracks Astra and obtains EVents service from it and registers a listener
     * that takes care of actually starting and stopping other extenders.
     */
    private class AstraServerTracker extends ServiceTracker {
        public AstraServerTracker(BundleContext context)
        {
            super(context, Astra.class.getName(), null);
        }

        @Override
        public Object addingService(ServiceReference reference)
        {
            logger.logp(Level.FINE, "ExtenderManager$AstraServerTracker",
                    "addingService", "Astra has been created");
            final Astra gf = Astra.class.cast(context.getService(reference));
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            return executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Poll for Astra to start. Astra service might have been registered by
                        // AstraRuntime.newAstra() and hence might not be ready to use.
                        // This is the case for Astra < 4.0
                        Astra.Status status;
                        while ((status = gf.getStatus()) != Astra.Status.STARTED) {
                            if (status == Astra.Status.DISPOSED) return;
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        // start extenders first before registering for events, otherwise we can deadlock
                        // if startExtender() is in progress and Astra sends PREPARE_SHUTDOWN event.
                        startExtenders();
                        events = gf.getService(Events.class);
                        listener = new EventListener() {
                            public void event(Event event) {
                                if (EventTypes.PREPARE_SHUTDOWN.equals(event.type())) {
                                    stopExtenders();
                                }
                            }
                        };
                        events.register(listener);
                    } catch (AstraException e) {
                        throw new RuntimeException(e); // TODO(Sahoo): Proper Exception Handling
                    }
                }
            });
        }

        @Override
        public void removedService(ServiceReference reference, Object service) {
            Future future = (Future) service;
            future.cancel(true); // interrupt if it is still waiting for gf to start or stop
            super.removedService(reference, service);
        }
    }
}
